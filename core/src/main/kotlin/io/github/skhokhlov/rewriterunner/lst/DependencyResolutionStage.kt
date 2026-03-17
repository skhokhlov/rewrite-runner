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
import org.eclipse.aether.repository.LocalRepository
import org.eclipse.aether.resolution.ArtifactRequest
import org.eclipse.aether.resolution.ArtifactResolutionException
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
    protected val logger: RunnerLogger
) {
    private sealed class ResolvedCoords {
        abstract val coords: List<String>

        /** Fully-resolved transitive coordinates from `gradle dependencies` — skip POM traversal. */
        class Full(override val coords: List<String>) : ResolvedCoords()

        /** Declared (direct) coordinates only — POM traversal required for transitive graph. */
        class Declared(override val coords: List<String>) : ResolvedCoords()
    }

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
     * @return [ClasspathResolutionResult] containing the resolved JAR paths and, for Gradle
     *   projects where the `gradle dependencies` task succeeded, per-project configuration data
     *   for constructing [GradleProject] markers. Returns an empty classpath (never throws) when
     *   no coordinates can be found or when resolution fails completely.
     */
    open fun resolveClasspath(projectDir: Path): ClasspathResolutionResult {
        val resolved: ResolvedCoords
        var gradleProjectData: Map<String, GradleProjectData>? = null

        when {
            projectDir.resolve("pom.xml").exists() -> {
                logger.debug("Stage 2: Maven project detected — parsing pom.xml")
                resolved = ResolvedCoords.Declared(parseMavenDependencies(projectDir))
            }

            hasBuildGradle(projectDir) -> {
                logger.debug("Stage 2: Gradle project detected — running dependencies task")
                val rawOutput = runGradleDependenciesRawOutput(projectDir)
                if (rawOutput != null) {
                    val coords = parseGradleDependencyTaskOutput(rawOutput)
                    if (coords.isNotEmpty()) {
                        resolved = ResolvedCoords.Full(coords)
                        gradleProjectData = parseGradleDependencyTaskOutputByProject(rawOutput)
                    } else {
                        logger.info(
                            "Gradle dependencies task returned no coordinates — falling back to static parsing"
                        )
                        resolved =
                            ResolvedCoords.Declared(parseGradleDependenciesStatically(projectDir))
                    }
                } else {
                    logger.debug(
                        "Stage 2: falling back to static Gradle build-file parsing"
                    )
                    resolved =
                        ResolvedCoords.Declared(parseGradleDependenciesStatically(projectDir))
                }
            }

            else -> resolved = ResolvedCoords.Declared(emptyList())
        }

        if (resolved.coords.isEmpty()) {
            logger.info("No dependencies found in build descriptor")
            return ClasspathResolutionResult(emptyList(), gradleProjectData)
        }

        // Both Full (Gradle task output) and Declared (Maven POM / static Gradle parsing)
        // routes use direct artifact resolution. Full coords are already the complete
        // transitive graph returned by Gradle; Declared coords are only direct deps
        // (transitive deps are skipped — OpenRewrite tolerates JavaType.Unknown for
        // missing transitives, and Stage 3 supplements local-cache JARs).
        val classpath = resolveArtifactsDirectly(resolved.coords)
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
            ArtifactRequest(DefaultArtifact(it), context.remoteRepos, null)
        }
        return try {
            context.system.resolveArtifacts(context.session, requests)
                .mapNotNull { it.artifact?.path }
        } catch (e: ArtifactResolutionException) {
            handlePartialResolution(e.results.mapNotNull { it.artifact?.path }, e.message)
        }
    }

    /**
     * Resolves declared (direct) coordinates via POM traversal using Maven Resolver's
     * dependency graph, applying nearest-wins conflict resolution across the full
     * transitive graph. Used for Maven projects and static Gradle parsing fallback.
     */
    protected open fun resolveWithPomTraversal(coordinates: List<String>): List<Path> {
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
            handlePartialResolution(
                e.result?.artifactResults?.mapNotNull { it.artifact?.path }.orEmpty(),
                e.message
            )
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
            logger.info(
                "Gradle dependencies task returned no coordinates — falling back to static parsing"
            )
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
        val subprojects = discoverSubprojects(projectDir)
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
            logger.warn(
                "Gradle dependencies task failed with exit code $result — falling back to static parsing"
            )
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
     *
     * Example input:
     * ```
     * > Task :dependencies
     * compileClasspath - ...
     *   +--- com.example:foo:1.0
     *   \--- com.example:bar:2.0 -> 3.0
     *
     * > Task :api:dependencies
     * compileClasspath - ...
     *   \--- org.slf4j:slf4j-api:2.0.0
     * ```
     *
     * Example output:
     * ```
     * ":" -> GradleProjectData(configsByName={"compileClasspath": GradleConfigData(...)}, ...)
     * ":api" -> GradleProjectData(...)
     * ```
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
     * Each configuration block (header line + dependency tree) becomes a [GradleConfigData].
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
     * Parses repository URLs from a Gradle build file using regex.
     * Recognises `mavenCentral()`, `google()`, and explicit `maven { url = "..." }` blocks.
     *
     * @param buildFileContent pre-read build file text; when non-null, skips the disk read.
     */
    internal fun parseRepositoryUrls(
        projectDir: Path,
        buildFileContent: String? = null
    ): List<String> {
        return try {
            val text = buildFileContent
                ?: findBuildFile(projectDir)?.toFile()?.readText()
                ?: return emptyList()
            val urls = mutableListOf<String>()
            if (text.contains("mavenCentral()")) {
                urls.add("https://repo.maven.apache.org/maven2")
            }
            if (text.contains("google()")) {
                urls.add("https://dl.google.com/dl/android/maven2")
            }
            Regex("""maven\s*\{[^}]*url\s*[=:]\s*["']([^"']+)["']""").findAll(text)
                .mapTo(urls) { it.groupValues[1] }
            urls.distinct()
        } catch (e: Exception) {
            logger.warn("Failed to parse repository URLs from build file: ${e.message}")
            emptyList()
        }
    }

    /**
     * Discovers subproject paths declared via `include()` in `settings.gradle` or
     * `settings.gradle.kts`. Returns paths such as [":module1", ":module2"].
     *
     * The `gradle dependencies` task only covers the project it is invoked on.
     * Subprojects must be queried explicitly using `:sub:dependencies` tasks.
     */
    internal fun discoverSubprojects(projectDir: Path): List<String> {
        val settingsFile = findSettingsFile(projectDir) ?: return emptyList()

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
     * Best-effort static regex parse of `build.gradle` / `build.gradle.kts` files for
     * the root project and all declared subprojects, combined with coordinates from any
     * Gradle version catalog files found under `gradle/\*.versions.toml`.
     *
     * Used as a fallback when [runGradleDependenciesTask] cannot be invoked (no Gradle
     * wrapper, non-zero exit, or no output). Extracts coordinates using:
     * - Quoted `"group:artifact:version"` strings (Groovy and Kotlin DSL notation).
     * - Three-argument form: `implementation("group", "artifact", "version")`.
     * - Version catalog entries in `gradle/\*.versions.toml` (resolves `version.ref` aliases).
     *
     * **Limitations:** Dynamic expressions (computed version strings, BOM-managed versions
     * not declared in a catalog) will be absent. Those types appear as `JavaType.Unknown`
     * in the LST unless Stage 3 picks them up from local caches.
     *
     * @return Deduplicated list of `groupId:artifactId:version` coordinates found across
     *   all build files and version catalogs. Returns an empty list if nothing is found.
     */
    internal fun parseGradleDependenciesStatically(projectDir: Path): List<String> {
        // Collect build files: root + each subproject (":mod" → "mod/build.gradle(.kts)").
        val subprojectDirs = discoverSubprojects(projectDir).map { subprojectPath ->
            // Convert Gradle project path ":mod" or ":a:b" to a filesystem path "mod" / "a/b".
            projectDir.resolve(subprojectPath.trimStart(':').replace(':', '/'))
        }
        val buildDirs = listOf(projectDir) + subprojectDirs

        val coordPattern = Regex("""["']([a-zA-Z][\w.\-]+:[a-zA-Z][\w.\-]+:[\w.\-]+)["']""")
        val threeArgPattern = Regex(
            """(?:implementation|api|compileOnly|runtimeOnly)\s*\(\s*["']([^"']+)["']\s*,\s*["']([^"']+)["']\s*,\s*["']([^"']+)["']\s*\)"""
        )

        val coordinates = mutableListOf<String>()
        for (dir in buildDirs) {
            val buildFile = findBuildFile(dir) ?: continue
            logger.debug("Stage 2: statically parsing ${buildFile.toAbsolutePath()}")
            try {
                val text = buildFile.toFile().readText()
                coordPattern.findAll(text).forEach { match ->
                    val coord = match.groupValues[1]
                    if (!coord.contains("bom") && !coord.contains("platform")) {
                        coordinates.add(coord)
                    }
                }
                threeArgPattern.findAll(text).forEach { match ->
                    coordinates.add(
                        "${match.groupValues[1]}:${match.groupValues[2]}:${match.groupValues[3]}"
                    )
                }
            } catch (e: Exception) {
                logger.warn("Failed to parse Gradle build file ${buildFile.fileName}: ${e.message}")
            }
        }

        coordinates += parseVersionCatalogs(projectDir)

        return coordinates.distinct().also {
            logger.debug(
                "Stage 2: static parsing found ${it.size} unique coordinate(s) across " +
                    "${buildDirs.size} build file(s) + version catalog(s)"
            )
        }
    }

    // ─── Version catalog parsing ───────────────────────────────────────────────

    /**
     * Parses all `*.versions.toml` files under `<projectDir>/gradle/` and returns
     * fully-resolved `groupId:artifactId:version` coordinates from the `[libraries]`
     * section.
     *
     * The Gradle version catalog format defines three library entry styles:
     * 1. `alias = { module = "group:artifact", version.ref = "versionAlias" }`
     * 2. `alias = { module = "group:artifact", version = "1.0" }`
     * 3. `alias = { group = "com.example", name = "lib", version[.ref] = "..." }`
     * 4. `alias = "group:artifact:version"` (string literal form)
     *
     * `version.ref` entries are resolved against the `[versions]` section of the same
     * catalog file. Entries without a resolvable version (e.g. BOM-managed, `required`
     * rich constraints) are silently skipped — their types may appear as
     * `JavaType.Unknown` unless Stage 3 finds the JARs in local caches.
     *
     * @return Deduplicated list of resolved coordinates across all catalog files.
     *   Returns an empty list if no `gradle/` directory or no `.versions.toml` files exist.
     */
    internal fun parseVersionCatalogs(projectDir: Path): List<String> {
        val gradleDir = projectDir.resolve("gradle")
        if (!gradleDir.exists()) return emptyList()

        val catalogFiles = gradleDir.toFile()
            .listFiles { f -> f.isFile && f.name.endsWith(".versions.toml") }
            ?.toList()
            ?: return emptyList()

        val coordinates = mutableListOf<String>()
        for (catalogFile in catalogFiles) {
            logger.debug("Stage 2: parsing version catalog ${catalogFile.absolutePath}")
            try {
                coordinates += parseCatalogFile(catalogFile.toPath())
            } catch (e: Exception) {
                logger.warn("Failed to parse version catalog ${catalogFile.name}: ${e.message}")
            }
        }
        return coordinates.distinct()
    }

    private fun parseCatalogFile(file: Path): List<String> {
        val versions = mutableMapOf<String, String>()
        val coordinates = mutableListOf<String>()
        var section = ""

        for (rawLine in file.toFile().readLines()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue

            // Section header: [versions], [libraries], [bundles], [plugins]
            if (line.startsWith("[")) {
                section = Regex("""\[(\w+)]""").find(line)?.groupValues?.get(1) ?: ""
                continue
            }

            when (section) {
                "versions" -> {
                    // Simple string form only: guava = "32.1.2-jre"
                    // Rich constraint forms ({ require = "..." }) are skipped.
                    val m = Regex("""^([\w.\-]+)\s*=\s*"([^"]+)""").find(line) ?: continue
                    versions[m.groupValues[1]] = m.groupValues[2]
                }

                "libraries" -> {
                    parseCatalogLibraryEntry(line, versions)?.let { coordinates += it }
                }
            }
        }
        return coordinates
    }

    /**
     * Parses a single `[libraries]` entry from a version catalog TOML file.
     *
     * Handles four forms:
     * - String literal: `alias = "group:artifact:version"`
     * - Module + version.ref: `alias = { module = "group:artifact", version.ref = "key" }`
     * - Module + inline version: `alias = { module = "group:artifact", version = "1.0" }`
     * - Verbose group/name: `alias = { group = "g", name = "a", version[.ref] = "..." }`
     *
     * Returns null when the version cannot be determined (BOM-managed, rich constraint).
     */
    internal fun parseCatalogLibraryEntry(line: String, versions: Map<String, String>): String? {
        // Form 4: string literal with all three parts
        val literal = Regex(
            """^[\w.\-]+\s*=\s*"([a-zA-Z][\w.\-]+:[a-zA-Z][\w.\-]+:[\w.\-]+)"""
        ).find(line)
        if (literal != null) return literal.groupValues[1]

        // Determine group:artifact from either `module` or `group`+`name`
        val groupArtifact =
            Regex("""module\s*=\s*"([a-zA-Z][\w.\-]+:[a-zA-Z][\w.\-]+)""").find(line)
                ?.groupValues?.get(1)
                ?: run {
                    val g = Regex("""group\s*=\s*"([^"]+)""").find(line)?.groupValues?.get(1)
                    val n = Regex("""name\s*=\s*"([^"]+)""").find(line)?.groupValues?.get(1)
                    if (g != null && n != null) "$g:$n" else null
                }
                ?: return null

        // Resolve version: prefer version.ref (resolves via [versions] map), fall back to
        // inline version = "...". `version\s*=` does not match `version.ref` because the
        // pattern requires `=` immediately after optional whitespace (not `.ref`).
        val version =
            Regex("""version\.ref\s*=\s*"([^"]+)""").find(line)?.groupValues?.get(1)
                ?.let { versions[it] }
                ?: Regex("""version\s*=\s*"([^"]+)""").find(line)?.groupValues?.get(1)
                ?: return null // BOM-managed or rich constraint — skip

        return "$groupArtifact:$version"
    }
}
