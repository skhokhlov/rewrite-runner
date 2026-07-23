package io.github.skhokhlov.rewriterunner.lst

import io.github.skhokhlov.rewriterunner.AetherContext
import io.github.skhokhlov.rewriterunner.MavenCoordinates
import io.github.skhokhlov.rewriterunner.ParseFailure
import io.github.skhokhlov.rewriterunner.RunnerLogger
import io.github.skhokhlov.rewriterunner.UsedExecutionStage
import io.github.skhokhlov.rewriterunner.config.ToolConfigDefaults
import io.github.skhokhlov.rewriterunner.lst.utils.BuildToolKind
import io.github.skhokhlov.rewriterunner.lst.utils.ClasspathResolutionResult
import io.github.skhokhlov.rewriterunner.lst.utils.GradleConfigData
import io.github.skhokhlov.rewriterunner.lst.utils.GradleProjectData
import io.github.skhokhlov.rewriterunner.lst.utils.StaticBuildFileParser
import io.github.skhokhlov.rewriterunner.lst.utils.discoverBuildUnitResult
import io.github.skhokhlov.rewriterunner.lst.utils.discoverBuildUnits
import io.github.skhokhlov.rewriterunner.lst.utils.resolveGradleCommand
import io.github.skhokhlov.rewriterunner.lst.utils.resolveMavenCommand
import io.github.skhokhlov.rewriterunner.lst.utils.runProcess
import java.nio.file.Path
import java.time.Duration
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
 * **Failure behaviour:** When no subprocess succeeds, capped discovery leaves some units
 * uncovered, any discovered unit fails, or Maven Resolver produces an empty result,
 * [resolve] returns null, causing [LstBuilder] to fall through to
 * [BuildFileParseStage] (Stage 3).
 *
 * **Extensibility:** The class is `open` with `open` / `protected open` methods so
 * tests can subclass it to inject a fake classpath without triggering network access.
 *
 * @param aetherContext Shared Maven Resolver context holding the Aether RepositorySystem,
 *   session, and configured remote repositories. Create one via [AetherContext.build].
 */
open class DependencyResolutionStage(
    private val aetherContext: AetherContext,
    protected val logger: RunnerLogger,
    private val processTimeout: Duration = ToolConfigDefaults.SUBPROCESS_RUN_TIMEOUT
) : ClasspathStage {
    private val staticParser = StaticBuildFileParser(logger)
    private var commandRoot: Path? = null

    // ─── shared Gradle dependency-line parsing ──────────────────────────────

    /**
     * Parsed result of a single Gradle dependency-tree line.
     * Used by both [parseProjectDependencySegment] and [parseGradleDependencyTaskOutput].
     */
    private data class GradleDepLine(
        val groupArtifact: String,
        val declaredVersion: String,
        val resolvedVersion: String?
    )

    /**
     * Attempts to parse a single Gradle dependency-tree line (e.g.
     * `\--- com.google.guava:guava:32.1.2-jre` or `+--- foo:bar:1.0 -> 2.0`).
     *
     * Returns `null` when the line doesn't match the expected format, or when
     * the declared version is blank or `"FAILED"` (unresolvable dependency).
     */
    private fun parseGradleDepLine(line: String): GradleDepLine? {
        val match = gradleDepPattern.find(line) ?: return null
        val groupArtifact = match.groupValues[1]
        val declaredVersion = match.groupValues[2]
        val resolvedVersion = match.groupValues[3].takeIf { it.isNotBlank() }
        if (declaredVersion.isBlank() || declaredVersion == "FAILED") return null
        return GradleDepLine(groupArtifact, declaredVersion, resolvedVersion)
    }

    /** Regex that matches a single Gradle dependency-tree line. */
    private val gradleDepPattern = Regex(
        """^[\s|+\\-]+([a-zA-Z][\w.\-]*:[a-zA-Z][\w.\-]+):([\w.\-]+)(?:\s*->\s*([\w.\-]+))?"""
    )

    /**
     * Best-effort: runs `gradle dependencies` to collect per-project configuration data for
     * [org.openrewrite.gradle.marker.GradleProject] marker attachment. Used by [LstBuilder] when
     * Stage 1 already provided the compile classpath and only marker data is still needed.
     *
     * Returns `null` when the project is not a Gradle project, when the subprocess fails, or
     * when no dependency data could be parsed.
     */
    open fun collectGradleProjectData(projectDir: Path): Map<String, GradleProjectData>? {
        val priorCommandRoot = commandRoot
        commandRoot = projectDir
        try {
            val gradleUnits = discoverBuildUnits(projectDir, logger = logger)
                .filter { it.tool == BuildToolKind.GRADLE }
            if (gradleUnits.isEmpty()) return null

            val merged = linkedMapOf<String, GradleProjectData>()
            gradleUnits.forEach { unit ->
                try {
                    val rawOutput = runGradleDependenciesRawOutput(unit.dir) ?: return@forEach
                    val parsed = parseGradleDependencyTaskOutputByProject(rawOutput)
                    merged.putAll(rekeyGradleProjectData(projectDir, unit.dir, parsed))
                } catch (e: Exception) {
                    logger.warn(
                        "Could not collect Gradle project data for markers from ${unit.dir}: " +
                            e.message
                    )
                }
            }
            return merged.takeIf { it.isNotEmpty() }
        } finally {
            commandRoot = priorCommandRoot
        }
    }

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
     *   for constructing [org.openrewrite.gradle.marker.GradleProject] markers. Returns null
     *   when no subprocess succeeds or resolution fails completely.
     */
    override fun resolve(
        projectDir: Path,
        parseFailures: MutableList<ParseFailure>
    ): ClasspathResolutionResult? {
        logger.info("Stage 2: resolving dependencies via Maven Resolver")
        val priorCommandRoot = commandRoot
        commandRoot = projectDir
        return try {
            val coords = mutableListOf<String>()
            val gradleProjectData = linkedMapOf<String, GradleProjectData>()

            val discovery = discoverBuildUnitResult(projectDir, logger = logger)
            var completedUnits = 0

            discovery.units.forEach { unit ->
                when (unit.tool) {
                    BuildToolKind.MAVEN -> {
                        logger.debug(
                            "Stage 2: Maven build unit at ${unit.dir} — running dependency:tree"
                        )
                        val rawOutput = runMavenDependencyTreeOutput(unit.dir)
                        if (rawOutput != null) {
                            completedUnits++
                            coords += parseMavenDependencyTreeOutput(rawOutput)
                        }
                    }

                    BuildToolKind.GRADLE -> {
                        logger.debug(
                            "Stage 2: Gradle build unit at ${unit.dir} — running dependencies task"
                        )
                        val rawOutput = runGradleDependenciesRawOutput(unit.dir)
                        if (rawOutput != null) {
                            completedUnits++
                            val gradleCoords = parseGradleDependencyTaskOutput(rawOutput)
                            if (gradleCoords.isNotEmpty()) {
                                coords += gradleCoords
                                val parsedData =
                                    parseGradleDependencyTaskOutputByProject(rawOutput)
                                gradleProjectData.putAll(
                                    rekeyGradleProjectData(projectDir, unit.dir, parsedData)
                                )
                            } else {
                                logger.info("Gradle dependencies task returned no coordinates")
                            }
                        }
                    }

                    BuildToolKind.NONE -> Unit
                }
            }

            if (discovery.truncated || completedUnits != discovery.units.size) {
                val reason = if (discovery.truncated) {
                    "discovery was capped at ${discovery.units.size} build unit(s)"
                } else {
                    "only $completedUnits/${discovery.units.size} build unit(s) completed"
                }
                logger.warn(
                    "Stage 2 did not cover the full project: $reason; " +
                        "falling through to Stage 3"
                )
                return null
            }

            if (coords.isEmpty()) {
                logger.info("No dependencies found via subprocess")
                logger.warn(
                    "Stage 2 (dependency resolution) failed: no JARs resolved, " +
                        "falling through to Stage 3"
                )
                return null
            }

            val classpath = resolveArtifactsDirectly(coords.distinct(), parseFailures)
            if (classpath.isEmpty()) {
                // Intentional: Gradle marker data belongs only to a winning Stage 2 result.
                logger.warn(
                    "Stage 2 (dependency resolution) failed: no JARs resolved, " +
                        "falling through to Stage 3"
                )
                return null
            }
            return ClasspathResolutionResult(
                classpath,
                gradleProjectData.takeIf { it.isNotEmpty() },
                stageUsed = UsedExecutionStage.DEPENDENCY_RESOLUTION
            )
        } catch (e: Exception) {
            logger.warn("Stage 2 threw an exception: ${e.message}")
            null
        } finally {
            commandRoot = priorCommandRoot
        }
    }

    private fun rekeyGradleProjectData(
        rootDir: Path,
        unitDir: Path,
        data: Map<String, GradleProjectData>
    ): Map<String, GradleProjectData> {
        val unitPath = gradleProjectPath(rootDir, unitDir)
        if (unitPath == ":") return data
        return data.mapKeys { (projectPath, _) ->
            if (projectPath == ":") unitPath else unitPath + projectPath
        }
    }

    private fun gradleProjectPath(rootDir: Path, projectDir: Path): String {
        if (rootDir == projectDir) return ":"
        val relative = rootDir.relativize(projectDir).toString().replace('\\', '/')
        return relative.split('/')
            .filter { it.isNotBlank() }
            .joinToString(separator = ":", prefix = ":")
    }

    /**
     * Resolves a fully-traversed coordinate list directly via [ArtifactRequest], skipping
     * POM downloads. Used when [runGradleDependenciesTask] returns the complete transitive
     * graph, so there is no need to re-traverse it through Maven Resolver.
     *
     * **Note:** This 1-arg form does **not** filter malformed coordinates. The 2-arg
     * overload performs filtering and then delegates here. Tests historically override
     * this method to stub out the Aether call.
     */
    protected open fun resolveArtifactsDirectly(coordinates: List<String>): List<Path> {
        logger.info(
            "Resolving ${coordinates.size} fully-resolved coordinates directly " +
                "(skipping POM traversal)"
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

    /**
     * Filter [coordinates] through [MavenCoordinates.tryParse] before delegating to the
     * 1-arg [resolveArtifactsDirectly], so malformed coords (e.g. illegal URI chars from
     * a corrupted `mvn dependency:tree` line) no longer abort the entire stage. Each
     * skipped coord is appended to [parseFailures] with
     * `parser = "DependencyResolutionStage"`.
     */
    protected open fun resolveArtifactsDirectly(
        coordinates: List<String>,
        parseFailures: MutableList<ParseFailure>
    ): List<Path> {
        val (good, bad) = coordinates.partition { MavenCoordinates.tryParse(it) != null }
        bad.forEach { coord ->
            logger.warn(
                "Stage 2: skipping malformed Maven coordinate '$coord' (illegal URI character)"
            )
            parseFailures += ParseFailure(
                path = coord,
                reason = "illegal Maven coordinate",
                parser = "DependencyResolutionStage"
            )
        }
        return if (good.isEmpty()) emptyList() else resolveArtifactsDirectly(good)
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
        val mvnCmd = resolveMavenCommand(projectDir, commandRoot ?: projectDir)
        val output = StringBuilder()
        val result = runProcess(
            projectDir,
            listOf(mvnCmd, "dependency:tree"),
            captureStdout = output,
            timeout = processTimeout,
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
     * Includes `compile`, `provided`, and `test` scopes for correct OpenRewrite type
     * resolution (test sources need test deps; provided deps supply compile-time types).
     * Excludes `runtime` and `system` scopes — consistent with Stage 3.
     *
     * @return Deduplicated list of `groupId:artifactId:version` coordinates.
     */
    internal fun parseMavenDependencyTreeOutput(output: String): List<String> {
        val coordinates = mutableSetOf<String>()
        // Lines like: [INFO] +- org.springframework:spring-core:jar:6.1.0:compile
        val pattern = Regex(
            """^\[INFO\]\s+[\s|+\\-]+([a-zA-Z][\w.\-]+:[a-zA-Z][\w.\-]+):(?:jar|war|pom|ear|zip|aar|bundle):([^:]+):(\w+)\s*$"""
        )
        for (line in output.lines()) {
            val match = pattern.find(line) ?: continue
            val groupArtifact = match.groupValues[1]
            val version = match.groupValues[2]
            val scope = match.groupValues[3]
            if (version.isNotBlank() && version != "FAILED" &&
                scope !in listOf("runtime", "system")
            ) {
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
     * Separated from [runGradleDependenciesTask] so that [resolve] can parse the output
     * both for the flat coordinates list (via [parseGradleDependencyTaskOutput]) and for
     * per-project data (via [parseGradleDependencyTaskOutputByProject]).
     */
    protected open fun runGradleDependenciesRawOutput(projectDir: Path): String? {
        val gradleCmd = resolveGradleCommand(projectDir, commandRoot ?: projectDir)
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
            timeout = processTimeout,
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

            val depLine = parseGradleDepLine(line) ?: continue
            requestedDeps.add("${depLine.groupArtifact}:${depLine.declaredVersion}")
            resolvedDeps.add("${depLine.groupArtifact}:${depLine.resolvedVersion ?: depLine.declaredVersion}")
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
     *
     * Only includes dependencies from compile-time configurations (e.g.
     * `compileClasspath`, `testCompileClasspath`, `implementation`,
     * `testImplementation`). Runtime-only configurations (`runtimeClasspath`,
     * `testRuntimeClasspath`, `runtimeOnly`, `testRuntimeOnly`) are skipped so
     * that the resulting classpath matches the compile-time view required for
     * OpenRewrite type resolution — consistent with Stage 3.
     */
    internal fun parseGradleDependencyTaskOutput(output: String): List<String> {
        val coordinates = mutableSetOf<String>()
        val configHeaderPattern = Regex("""^([a-zA-Z][\w\-]*)\s+-\s+.+$""")
        // Start in "include" mode — lines before the first config header belong to no
        // named configuration (task header lines, blank lines) and are harmless to skip.
        var inCompileConfig = false
        for (line in output.lines()) {
            // Configuration headers do not start with tree-drawing characters.
            if (!line.startsWith(" ") && !line.startsWith("+") &&
                !line.startsWith("\\") && !line.startsWith("|")
            ) {
                val configMatch = configHeaderPattern.find(line)
                if (configMatch != null) {
                    inCompileConfig = isCompileTimeConfiguration(configMatch.groupValues[1])
                    continue
                }
            }
            if (!inCompileConfig) continue
            val depLine = parseGradleDepLine(line) ?: continue
            val version = depLine.resolvedVersion ?: depLine.declaredVersion
            coordinates.add("${depLine.groupArtifact}:$version")
        }
        return coordinates.toList()
    }

    /**
     * Returns true if [name] is a compile-time Gradle configuration whose dependencies
     * belong on the compile classpath used for OpenRewrite type resolution.
     *
     * Excludes runtime-only configurations (e.g. `runtimeClasspath`, `runtimeOnly`,
     * `testRuntimeClasspath`, `testRuntimeOnly`) whose dependencies are not available
     * at compile time.
     */
    private fun isCompileTimeConfiguration(name: String): Boolean {
        val lower = name.lowercase()
        // A configuration is runtime-only when "runtime" appears in its name but
        // "compile" does not (e.g. runtimeClasspath, testRuntimeOnly).
        return !("runtime" in lower && "compile" !in lower)
    }
}
