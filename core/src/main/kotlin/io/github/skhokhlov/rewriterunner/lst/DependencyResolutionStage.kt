package io.github.skhokhlov.rewriterunner.lst

import io.github.skhokhlov.rewriterunner.AetherContext
import io.github.skhokhlov.rewriterunner.NoOpRunnerLogger
import io.github.skhokhlov.rewriterunner.RunnerLogger
import java.nio.file.Path
import kotlin.io.path.exists
import org.apache.maven.model.io.xpp3.MavenXpp3Reader
import org.eclipse.aether.artifact.DefaultArtifact
import org.eclipse.aether.collection.CollectRequest
import org.eclipse.aether.graph.Dependency
import org.eclipse.aether.resolution.DependencyRequest
import org.eclipse.aether.resolution.DependencyResolutionException
import org.eclipse.aether.util.filter.ScopeDependencyFilter

/**
 * Stage 2 of the LST classpath-resolution pipeline: parse the project's build
 * descriptor and resolve declared dependencies via Maven Resolver (Eclipse Aether),
 * **without** invoking the project's build tool as a subprocess.
 *
 * **Why Stage 2?**
 * Stage 1 ([BuildToolStage]) requires the build tool to be installed and the project
 * to be in a runnable state. Stage 2 is the fallback when Stage 1 fails — for example,
 * when running in CI with no Maven/Gradle installation, when the project has an
 * incomplete build setup, or when Stage 1 times out.
 *
 * **How it works:**
 * 1. The build descriptor is parsed statically to extract `groupId:artifactId:version`
 *    coordinates of declared dependencies.
 * 2. All coordinates are submitted as a single dependency graph to Maven Resolver
 *    (Eclipse Aether), which downloads missing JARs and applies Maven conflict resolution
 *    (nearest-wins) across the full transitive graph. This mirrors what `mvn dependency:resolve`
 *    does without actually running Maven.
 * 3. Resolved JAR paths from the local repository are returned.
 *
 * **Maven projects:** `pom.xml` is parsed using the Maven Model reader. Dependencies
 * with `provided` or `system` scope, and those whose version contains an unresolved
 * Maven property placeholder (e.g. `${spring.version}`), are skipped.
 *
 * **Gradle projects:** Two strategies are attempted in order:
 * 1. Run `gradle dependencies` (with `gradlew` if present) for the root project and
 *    all subprojects discovered in `settings.gradle(.kts)`. The resolved dependency
 *    tree output is parsed to extract coordinates, correctly handling version overrides
 *    (e.g. `1.0 -> 2.0` conflict resolution lines).
 * 2. If the Gradle task cannot be run (no wrapper, non-zero exit, empty output),
 *    fall back to best-effort static regex parsing of `build.gradle` /
 *    `build.gradle.kts` to extract quoted `group:artifact:version` strings.
 *
 * **Partial resolution:** When some dependencies cannot be downloaded (e.g. private
 * repositories, network issues), a [org.eclipse.aether.resolution.DependencyResolutionException]
 * is thrown with partial results. Stage 2 logs a warning and returns whatever JARs
 * were resolved. Missing types appear as `JavaType.Unknown` in the LST rather than
 * causing a hard failure.
 *
 * **Failure behaviour:** When no coordinates are found, or when Maven Resolver
 * produces an empty result, [resolveClasspath] returns an empty list, causing
 * [LstBuilder] to fall through to [DirectParseStage] (Stage 3).
 *
 * **Extensibility:** The class is `open` with `open` / `protected open` methods so
 * tests can subclass it to inject a fake classpath without triggering network access.
 *
 * @param context Shared Maven Resolver context holding the Aether RepositorySystem,
 *   session, and configured remote repositories. Create one via [AetherContext.build].
 */
open class DependencyResolutionStage(
    private val context: AetherContext,
    protected val logger: RunnerLogger = NoOpRunnerLogger
) {
    /**
     * Resolves the project's compile classpath by parsing its build descriptor and
     * downloading dependencies via Maven Resolver.
     *
     * For Maven projects, reads `pom.xml`; for Gradle projects, first attempts
     * `gradle dependencies` and falls back to static build-file parsing.
     * All collected coordinates are resolved together in a single Aether request
     * so that Maven conflict resolution (nearest-wins) is applied across the full
     * transitive graph — the same behaviour as `mvn dependency:resolve`.
     *
     * Test-scoped and provided-scoped transitive dependencies are excluded via
     * [org.eclipse.aether.util.filter.ScopeDependencyFilter] to keep the classpath
     * focused on compile/runtime artifacts.
     *
     * @return Paths to all resolved JAR files in the local Maven repository.
     *   Returns an empty list (never throws) when no coordinates can be found or when
     *   resolution fails completely. A partially resolved list is returned (with a
     *   warning logged) when only some dependencies are available.
     */
    open fun resolveClasspath(projectDir: Path): List<Path> {
        val coordinates = when {
            projectDir.resolve("pom.xml").exists() -> parseMavenDependencies(projectDir)
            hasBuildGradle(projectDir) -> parseGradleDependencies(projectDir)
            else -> emptyList()
        }

        if (coordinates.isEmpty()) {
            logger.info("No dependencies found in build descriptor")
            return emptyList()
        }

        logger.info("Resolving ${coordinates.size} declared dependencies via Maven Resolver")
        val deps = coordinates.map { Dependency(DefaultArtifact(it), "runtime") }
        val collectRequest = CollectRequest(deps, emptyList(), context.remoteRepos)
        val scopeFilter = ScopeDependencyFilter(null, listOf("test", "provided"))
        val depRequest = DependencyRequest(collectRequest, scopeFilter)
        return try {
            context.system
                .resolveDependencies(context.session, depRequest)
                .artifactResults
                .mapNotNull { it.artifact?.path }
        } catch (e: DependencyResolutionException) {
            val partial = e.result?.artifactResults?.mapNotNull { it.artifact?.path }.orEmpty()
            if (partial.isNotEmpty()) {
                val firstError = e.message?.lineSequence()?.firstOrNull { it.isNotBlank() }
                logger.warn(
                    "Partial classpath resolution " +
                        "(${partial.size} JAR(s); some deps missing): $firstError"
                )
                partial
            } else {
                logger.warn(
                    "Could not resolve project classpath: " +
                        e.message?.lineSequence()?.firstOrNull { it.isNotBlank() }
                )
                emptyList()
            }
        }
    }

    // ─── Maven pom.xml parsing ────────────────────────────────────────────────

    internal fun parseMavenDependencies(projectDir: Path): List<String> {
        val pomFile = projectDir.resolve("pom.xml")
        return try {
            val model = MavenXpp3Reader().read(pomFile.toFile().inputStream())
            model.dependencies
                .filter { !listOf("provided", "system").contains(it.scope) }
                .mapNotNull { dep ->
                    val version = dep.version?.takeIf { it.isNotBlank() && !it.startsWith("\${") }
                        ?: return@mapNotNull null
                    "${dep.groupId}:${dep.artifactId}:$version"
                }
        } catch (e: Exception) {
            logger.warn("Failed to parse pom.xml: ${e.message}")
            emptyList()
        }
    }

    // ─── Gradle dependency resolution ─────────────────────────────────────────

    internal fun parseGradleDependencies(projectDir: Path): List<String> {
        // Try the Gradle dependencies task first — gives accurately resolved versions
        // including version catalog lookups, BOM imports, and conflict resolution.
        val fromTask = runGradleDependenciesTask(projectDir)
        if (fromTask != null) return fromTask

        // Fall back to static regex parsing of the build file.
        return parseGradleDependenciesStatically(projectDir)
    }

    /**
     * Runs `gradle dependencies` for the root project and all declared subprojects,
     * then parses the resolved dependency tree to extract coordinates.
     *
     * Returns the list of resolved coordinates, or null if the task cannot be run
     * (no Gradle wrapper, non-zero exit, or no coordinates found in the output).
     */
    protected open fun runGradleDependenciesTask(projectDir: Path): List<String>? {
        val gradleCmd = resolveGradleCommand(projectDir)
        val subprojects = discoverSubprojects(projectDir)
        // Build task list: root 'dependencies' + ':sub:dependencies' for each subproject.
        // The `gradle dependencies` task only covers the project it is applied to, so
        // subprojects must be queried explicitly.
        val tasks = mutableListOf("dependencies")
        subprojects.mapTo(tasks) { "$it:dependencies" }

        val output = StringBuilder()
        val result = runProcess(
            projectDir,
            listOf(gradleCmd, "-q") + tasks,
            captureStdout = output,
            logger = logger
        ) ?: return null

        if (result != 0) {
            logger.warn(
                "Gradle dependencies task failed with exit code $result — falling back to static parsing"
            )
            return null
        }

        val coords = parseGradleDependencyTaskOutput(output.toString())
        if (coords.isEmpty()) {
            logger.info(
                "Gradle dependencies task returned no coordinates — falling back to static parsing"
            )
            return null
        }

        logger.info(
            "Discovered ${coords.size} coordinates via Gradle dependencies task" +
                " (root + ${subprojects.size} subproject(s))"
        )
        return coords
    }

    /**
     * Discovers subproject paths declared via `include()` in `settings.gradle` or
     * `settings.gradle.kts`. Returns paths such as [":module1", ":module2"].
     *
     * The `gradle dependencies` task only covers the project it is invoked on.
     * Subprojects must be queried explicitly using `:sub:dependencies` tasks.
     */
    internal fun discoverSubprojects(projectDir: Path): List<String> {
        val settingsFile =
            projectDir.resolve("settings.gradle.kts").takeIf { it.exists() }
                ?: projectDir.resolve("settings.gradle").takeIf { it.exists() }
                ?: return emptyList()

        return try {
            val text = settingsFile.toFile().readText()
            // Match quoted strings starting with ':' — these are subproject paths
            // inside include() calls in both Groovy and Kotlin DSL settings files.
            val pattern = Regex("""["'](:[^"']+)["']""")
            pattern.findAll(text)
                .map { it.groupValues[1] }
                .filter { it.matches(Regex(""":[a-zA-Z][\w.\-/]*""")) }
                .distinct()
                .toList()
        } catch (e: Exception) {
            logger.warn("Failed to parse settings file for subprojects: ${e.message}")
            emptyList()
        }
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

    /**
     * Best-effort static regex parse of `build.gradle` / `build.gradle.kts`.
     *
     * Used as a fallback when [runGradleDependenciesTask] cannot be invoked (no Gradle
     * wrapper, non-zero exit, or no output). Extracts coordinates using two patterns:
     * - Quoted `"group:artifact:version"` strings (Groovy and Kotlin DSL notation).
     * - Three-argument form: `implementation("group", "artifact", "version")`.
     *
     * **Limitations:** Static parsing cannot evaluate version catalog references
     * (`libs.spring.core`), BOM-managed versions, or dynamically computed version strings.
     * Those dependencies will be absent from the returned list, and their types will appear
     * as `JavaType.Unknown` in the LST unless they were already present in local caches
     * (picked up later by [DirectParseStage]).
     *
     * @return Deduplicated list of `groupId:artifactId:version` coordinates found in the
     *   root build file. Returns an empty list if no build file exists or parsing fails.
     */
    internal fun parseGradleDependenciesStatically(projectDir: Path): List<String> {
        val buildFile =
            projectDir.resolve("build.gradle.kts").takeIf { it.exists() }
                ?: projectDir.resolve("build.gradle").takeIf { it.exists() }
                ?: return emptyList()

        return try {
            val text = buildFile.toFile().readText()
            val coordinates = mutableListOf<String>()

            // Match quoted strings like "group:artifact:version"
            val coordPattern = Regex("""["']([a-zA-Z][\w.\-]+:[a-zA-Z][\w.\-]+:[\w.\-]+)["']""")
            coordPattern.findAll(text).forEach { match ->
                val coord = match.groupValues[1]
                // Skip BOM / platform entries
                if (!coord.contains("bom") && !coord.contains("platform")) {
                    coordinates.add(coord)
                }
            }

            // Match Kotlin DSL form: implementation("group", "artifact", "version")
            val threeArgPattern = Regex(
                """(?:implementation|api|compileOnly|runtimeOnly)\s*\(\s*["']([^"']+)["']\s*,\s*["']([^"']+)["']\s*,\s*["']([^"']+)["']\s*\)"""
            )
            threeArgPattern.findAll(text).forEach { match ->
                coordinates.add(
                    "${match.groupValues[1]}:${match.groupValues[2]}:${match.groupValues[3]}"
                )
            }

            coordinates.distinct()
        } catch (e: Exception) {
            logger.warn("Failed to parse Gradle build file: ${e.message}")
            emptyList()
        }
    }
}
