package io.github.skhokhlov.rewriterunner.lst

import io.github.skhokhlov.rewriterunner.AetherContext
import io.github.skhokhlov.rewriterunner.RunnerLogger
import io.github.skhokhlov.rewriterunner.lst.utils.ClasspathResolutionResult
import io.github.skhokhlov.rewriterunner.lst.utils.GradleConfigData
import io.github.skhokhlov.rewriterunner.lst.utils.GradleProjectData
import io.github.skhokhlov.rewriterunner.lst.utils.StaticBuildFileParser
import io.github.skhokhlov.rewriterunner.lst.utils.hasBuildGradle
import io.github.skhokhlov.rewriterunner.lst.utils.hasGradleBuildInSubdir
import io.github.skhokhlov.rewriterunner.lst.utils.hasMavenPomInSubdir
import io.github.skhokhlov.rewriterunner.lst.utils.resolveGradleCommand
import io.github.skhokhlov.rewriterunner.lst.utils.resolveMavenCommand
import io.github.skhokhlov.rewriterunner.lst.utils.runProcess
import java.nio.file.Path
import kotlin.io.path.exists
import org.eclipse.aether.artifact.DefaultArtifact
import org.eclipse.aether.resolution.ArtifactRequest
import org.eclipse.aether.resolution.ArtifactResolutionException

/**
 * Stage 2 of the LST classpath-resolution pipeline: invoke the project's build tool
 * as a subprocess to obtain a fully-resolved dependency list, then download any missing
 * JARs via Maven Resolver (Eclipse Aether).
 *
 * **Why Stage 2?**
 * Stage 1 ([ProjectBuildStage]) extracts the exact on-disk classpath but requires the
 * project to be fully buildable. Stage 2 runs lighter subprocess commands
 * (`mvn dependency:tree` / `gradle dependencies`) that work even when the project
 * cannot be compiled — for example when compiler plugins are missing or the source
 * contains errors.
 *
 * **Maven projects:** Runs `mvnw dependency:tree` (or `mvn` when no wrapper is
 * present). The tree output is parsed to extract `group:artifact:version` coordinates.
 * Resolved artifacts are fetched directly (no POM traversal needed because Maven
 * has already computed the transitive closure).
 *
 * **Gradle projects:** Runs `gradle dependencies` for the root project and all
 * declared subprojects. Parsed coordinates are resolved directly via Aether.
 * Unlike Stage 1, this does not require the project to compile successfully.
 *
 * **Mixed Maven+Gradle projects:** Both subprocess paths are attempted and their
 * coordinates are combined before resolution.
 *
 * **Partial resolution:** When some dependencies cannot be downloaded (e.g. private
 * repositories, network issues), a [org.eclipse.aether.resolution.DependencyResolutionException]
 * is thrown with partial results. Stage 2 logs a warning and returns whatever JARs
 * were resolved. Missing types appear as `JavaType.Unknown` in the LST rather than
 * causing a hard failure.
 *
 * **Failure behaviour:** When no subprocess succeeds, or when Maven Resolver
 * produces an empty result, [resolveClasspath] returns an empty list, causing
 * [LstBuilder] to fall through to [BuildFileParseStage] (Stage 3).
 *
 * **Extensibility:** The class is `open` with `open` / `protected open` methods so
 * tests can subclass it to inject a fake classpath without triggering network access.
 *
 * @param aetherContext Shared Maven Resolver context holding the Aether RepositorySystem,
 *   session, and configured remote repositories. Create one via [AetherContext.build].
 */
open class DependencyResolutionStage(
    private val aetherContext: AetherContext,
    protected val logger: RunnerLogger
) {
    private val staticParser = StaticBuildFileParser(logger)

    /**
     * Resolves the project's compile classpath by running subprocesses
     * (`mvn dependency:tree` and/or `gradle dependencies`) and downloading
     * the resulting coordinates via Maven Resolver.
     *
     * Supports Maven-only, Gradle-only, and mixed Maven+Gradle projects, as well
     * as projects where build files live in subdirectories. Both subprocess paths
     * are attempted and their coordinates are combined before resolution.
     *
     * @return [io.github.skhokhlov.rewriterunner.lst.utils.ClasspathResolutionResult] containing the resolved JAR paths and, for Gradle
     *   projects where the `gradle dependencies` task succeeded, per-project configuration data
     *   for constructing [GradleProject] markers. Returns an empty classpath (never throws) when
     *   no subprocess succeeds or resolution fails completely.
     */
    open fun resolveClasspath(projectDir: Path): ClasspathResolutionResult {
        val coords = mutableListOf<String>()
        var gradleProjectData: Map<String, GradleProjectData>? = null

        // Maven subprocess: run mvn dependency:tree
        if (projectDir.resolve("pom.xml").exists() || hasMavenPomInSubdir(projectDir)) {
            logger.debug("Stage 2: Maven project detected — running dependency:tree")
            val rawOutput = runMavenDependencyTreeOutput(projectDir)
            if (rawOutput != null) {
                coords += parseMavenDependencyTreeOutput(rawOutput)
            }
        }

        // Gradle subprocess: run gradle dependencies
        if (hasBuildGradle(projectDir) || hasGradleBuildInSubdir(projectDir)) {
            logger.debug("Stage 2: Gradle project detected — running dependencies task")
            val rawOutput = runGradleDependenciesRawOutput(projectDir)
            if (rawOutput != null) {
                val gradleCoords = parseGradleDependencyTaskOutput(rawOutput)
                if (gradleCoords.isNotEmpty()) {
                    coords += gradleCoords
                    gradleProjectData = parseGradleDependencyTaskOutputByProject(rawOutput)
                } else {
                    logger.info("Gradle dependencies task returned no coordinates")
                }
            }
        }

        if (coords.isEmpty()) {
            logger.info("No dependencies found via subprocess")
            return ClasspathResolutionResult(emptyList(), gradleProjectData)
        }

        val classpath = resolveArtifactsDirectly(coords.distinct())
        return ClasspathResolutionResult(classpath, gradleProjectData)
    }

    /**
     * Resolves a fully-traversed coordinate list directly via [ArtifactRequest], skipping
     * POM downloads. Used when [runGradleDependenciesTask] returns the complete transitive
     * graph, so there is no need to re-traverse it through Maven Resolver.
     */
    protected open fun resolveArtifactsDirectly(coordinates: List<String>): List<Path> {
        logger.info(
            "Resolving ${coordinates.size} fully-resolved coordinates directly (skipping POM traversal)"
        )
        val requests = coordinates.map {
            ArtifactRequest(DefaultArtifact(it), aetherContext.remoteRepos, null)
        }
        return try {
            aetherContext.system.resolveArtifacts(aetherContext.session, requests)
                .mapNotNull { it.artifact?.path }
        } catch (e: ArtifactResolutionException) {
            handlePartialResolution(e.results.mapNotNull { it.artifact?.path }, e.message)
        }
    }

    private fun handlePartialResolution(partial: List<Path>, errorMessage: String?): List<Path> {
        val firstError = errorMessage?.lineSequence()?.firstOrNull { it.isNotBlank() }
        return if (partial.isNotEmpty()) {
            logger.warn(
                "Partial classpath resolution " +
                    "(${partial.size} JAR(s); some deps missing): $firstError"
            )
            partial
        } else {
            logger.warn("Could not resolve project classpath: $firstError")
            emptyList()
        }
    }

    // ─── Maven subprocess ─────────────────────────────────────────────────────

    /**
     * Runs `mvn dependency:tree` (using `mvnw` wrapper when present) and returns the raw
     * stdout output, or `null` on failure (non-zero exit, process error, or timeout).
     */
    protected open fun runMavenDependencyTreeOutput(projectDir: Path): String? {
        val mvnCmd = resolveMavenCommand(projectDir)
        val output = StringBuilder()
        val result = runProcess(
            projectDir,
            listOf(mvnCmd, "dependency:tree"),
            captureStdout = output,
            logger = logger
        ) ?: return null
        if (result != 0) {
            logger.warn("Maven dependency:tree failed with exit code $result")
            return null
        }
        return output.toString()
    }

    /**
     * Parses the text output of `mvn dependency:tree` and extracts
     * `group:artifact:version` coordinates.
     *
     * Handles standard tree output lines such as:
     * ```
     * [INFO] +- org.springframework:spring-core:jar:6.1.0:compile
     * [INFO] \- com.fasterxml.jackson.core:jackson-databind:jar:2.16.0:compile
     * ```
     *
     * @return Deduplicated list of `groupId:artifactId:version` coordinates.
     */
    internal fun parseMavenDependencyTreeOutput(output: String): List<String> {
        val coordinates = mutableSetOf<String>()
        // Lines like: [INFO] +- org.springframework:spring-core:jar:6.1.0:compile
        val pattern = Regex(
            """^\[INFO\]\s+[\s|+\\-]+([a-zA-Z][\w.\-]+:[a-zA-Z][\w.\-]+):(?:jar|war|pom|ear|zip|aar|bundle):([^:]+):\w+\s*$"""
        )
        for (line in output.lines()) {
            val match = pattern.find(line) ?: continue
            val groupArtifact = match.groupValues[1]
            val version = match.groupValues[2]
            if (version.isNotBlank() && version != "FAILED") {
                coordinates.add("$groupArtifact:$version")
            }
        }
        return coordinates.toList()
    }

    // ─── Gradle dependency resolution ─────────────────────────────────────────

    /**
     * Runs `gradle dependencies` for the root project and all declared subprojects,
     * then parses the resolved dependency tree to extract coordinates.
     *
     * Returns the list of resolved coordinates, or null if the task cannot be run
     * (no Gradle wrapper, non-zero exit, or no coordinates found in the output).
     */
    protected open fun runGradleDependenciesTask(projectDir: Path): List<String>? {
        val rawOutput = runGradleDependenciesRawOutput(projectDir) ?: return null
        val coords = parseGradleDependencyTaskOutput(rawOutput)
        if (coords.isEmpty()) {
            logger.info("Gradle dependencies task returned no coordinates")
            return null
        }
        return coords
    }

    /**
     * Runs the `gradle dependencies` task and returns the raw stdout output, or null on failure.
     *
     * Separated from [runGradleDependenciesTask] so that [resolveClasspath] can parse the output
     * both for the flat coordinates list (via [parseGradleDependencyTaskOutput]) and for
     * per-project data (via [parseGradleDependencyTaskOutputByProject]).
     */
    protected open fun runGradleDependenciesRawOutput(projectDir: Path): String? {
        val gradleCmd = resolveGradleCommand(projectDir)
        val subprojects = staticParser.discoverSubprojects(projectDir)
        logger.debug(
            "Stage 2: discovered ${subprojects.size} subproject(s): " +
                subprojects.joinToString(", ").ifEmpty { "(none)" }
        )
        // Build task list: root 'dependencies' + ':sub:dependencies' for each subproject.
        val tasks = mutableListOf("dependencies")
        subprojects.mapTo(tasks) { "$it:dependencies" }

        val output = StringBuilder()
        val result = runProcess(
            projectDir,
            listOf(
                gradleCmd,
                "-i",
                "-S",
                "--no-parallel",
                "--no-daemon",
                "--no-configuration-cache"
            ) + tasks,
            captureStdout = output,
            logger = logger
        ) ?: return null

        if (result != 0) {
            logger.warn("Gradle dependencies task failed with exit code $result")
            return null
        }

        val rawOutput = output.toString()
        val subprojectCount = subprojects.size
        logger.info(
            "Gradle dependencies task succeeded (root + $subprojectCount subproject(s))"
        )
        return rawOutput
    }

    /**
     * Parses the text output of `gradle dependencies` into a map of Gradle project path
     * → [GradleProjectData] containing per-configuration dependency info.
     *
     * Splits the output at `> Task :<path>:dependencies` boundaries to extract per-project
     * configuration data. The root project maps to `":"`.
     */
    internal fun parseGradleDependencyTaskOutputByProject(
        output: String
    ): Map<String, GradleProjectData> {
        // Match task headers like "> Task :dependencies" or "> Task :api:dependencies"
        // Captures the prefix portion (everything before ":dependencies"), e.g. ":" or ":api:"
        val taskHeaderPattern =
            Regex("""^> Task (:[a-zA-Z\d:._\-]*)dependencies""", RegexOption.MULTILINE)
        val matches = taskHeaderPattern.findAll(output).toList()

        if (matches.isEmpty()) {
            // No task headers present — treat the whole output as the root project
            return mapOf(":" to parseProjectDependencySegment(output))
        }

        val result = mutableMapOf<String, GradleProjectData>()
        for ((index, match) in matches.withIndex()) {
            // prefix = ":" for root ("> Task :dependencies"), ":api:" for "> Task :api:dependencies"
            val prefix = match.groupValues[1]
            val projectPath = prefix.trimEnd(':').ifEmpty { ":" }
            val segmentStart = match.range.last + 1
            val segmentEnd = if (index + 1 <
                matches.size
            ) {
                matches[index + 1].range.first
            } else {
                output.length
            }
            val segment = output.substring(segmentStart, segmentEnd)
            result[projectPath] = parseProjectDependencySegment(segment)
        }
        return result
    }

    /**
     * Parses a single project's dependency task output segment into [GradleProjectData].
     * Each configuration block (header line + dependency tree) becomes a [io.github.skhokhlov.rewriterunner.lst.utils.GradleConfigData].
     */
    private fun parseProjectDependencySegment(segment: String): GradleProjectData {
        val configurationsByName = mutableMapOf<String, GradleConfigData>()
        var currentConfig: String? = null
        val requestedDeps = mutableListOf<String>()
        val resolvedDeps = mutableListOf<String>()

        fun flushConfig() {
            val config = currentConfig ?: return
            configurationsByName[config] =
                GradleConfigData(requestedDeps.toList(), resolvedDeps.toList())
        }

        // Config header: e.g. "compileClasspath - Compile classpath for source set 'main'."
        // Must not start with tree chars (+, \, |, space) — those are dependency lines.
        val configHeaderPattern = Regex("""^([a-zA-Z][\w\-]*)\s+-\s+.+$""")
        val depPattern = Regex(
            """^[\s|+\\-]+([a-zA-Z][\w.\-]*:[a-zA-Z][\w.\-]+):([\w.\-]+)(?:\s*->\s*([\w.\-]+))?"""
        )

        for (line in segment.lines()) {
            val trimmed = line.trimStart()
            // Config headers don't start with tree characters
            if (!trimmed.startsWith("+") && !trimmed.startsWith("\\") &&
                !trimmed.startsWith("|") && !trimmed.startsWith(" ")
            ) {
                val configMatch = configHeaderPattern.find(line)
                if (configMatch != null) {
                    flushConfig()
                    currentConfig = configMatch.groupValues[1]
                    requestedDeps.clear()
                    resolvedDeps.clear()
                    continue
                }
            }

            val depMatch = depPattern.find(line) ?: continue
            val groupArtifact = depMatch.groupValues[1]
            val declaredVersion = depMatch.groupValues[2]
            val resolvedVersion = depMatch.groupValues[3].takeIf { it.isNotBlank() }
            if (declaredVersion.isNotBlank() && declaredVersion != "FAILED") {
                requestedDeps.add("$groupArtifact:$declaredVersion")
                resolvedDeps.add("$groupArtifact:${resolvedVersion ?: declaredVersion}")
            }
        }
        flushConfig()

        return GradleProjectData(configurationsByName, emptyList())
    }

    /**
     * Parses the text output of `gradle dependencies` and extracts
     * `group:artifact:version` coordinates.
     *
     * Handles resolved version overrides (`1.0 -> 2.0`) by using the final
     * resolved version. Deduplicates results.
     */
    internal fun parseGradleDependencyTaskOutput(output: String): List<String> {
        val coordinates = mutableSetOf<String>()
        // Dependency lines start with tree-drawing characters (+---, \---, |    etc.)
        // followed by group:artifact:declaredVersion, optionally overridden by -> resolvedVersion.
        val depPattern = Regex(
            """^[\s|+\\-]+([a-zA-Z][\w.\-]*:[a-zA-Z][\w.\-]+):([\w.\-]+)(?:\s*->\s*([\w.\-]+))?"""
        )
        for (line in output.lines()) {
            val match = depPattern.find(line) ?: continue
            val groupArtifact = match.groupValues[1]
            val declaredVersion = match.groupValues[2]
            val resolvedVersion = match.groupValues[3].takeIf { it.isNotBlank() }
            val version = resolvedVersion ?: declaredVersion
            // Skip FAILED resolutions (unresolvable dependencies) and blank versions.
            if (version.isNotBlank() && version != "FAILED") {
                coordinates.add("$groupArtifact:$version")
            }
        }
        return coordinates.toList()
    }
}
