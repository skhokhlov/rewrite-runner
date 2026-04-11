package io.github.skhokhlov.rewriterunner.lst.utils

import io.github.skhokhlov.rewriterunner.RunnerLogger
import java.nio.file.Path
import kotlin.io.path.exists
import org.apache.maven.model.io.xpp3.MavenXpp3Reader

/**
 * Best-effort static parsing of Maven and Gradle build descriptors — no subprocess invocations,
 * no network access.
 *
 * Shared by [io.github.skhokhlov.rewriterunner.lst.BuildFileParseStage] (Stage 3) for coordinate collection and by [io.github.skhokhlov.rewriterunner.lst.LstBuilder] for
 * the [io.github.skhokhlov.rewriterunner.lst.LocalRepositoryStage] (Stage 4) coordinate hint and [MarkerFactory] for repository URLs.
 *
 * **Maven:** Reads `pom.xml` via [org.apache.maven.model.io.xpp3.MavenXpp3Reader]. Property-interpolated and BOM-managed
 * versions (e.g. `${spring.version}`) are silently skipped — they cannot be resolved statically.
 *
 * **Gradle:** Regex-scans `build.gradle`/`build.gradle.kts` for quoted `"g:a:v"` strings and
 * three-argument `implementation("g", "a", "v")` forms. Also parses `gradle/\*.versions.toml`
 * version catalogs, resolving `version.ref` aliases against the `[versions]` section.
 */
internal class StaticBuildFileParser(private val logger: RunnerLogger) {

    // ─── Maven ────────────────────────────────────────────────────────────────

    /**
     * Parses the `pom.xml` in [projectDir] and returns compile-time dependency coordinates.
     * Includes `compile` (default), `provided`, and `test` scoped dependencies so that
     * OpenRewrite can resolve types in both main and test source sets; `provided` deps
     * supply compile-time types even though they are not bundled at runtime.
     * Skips `runtime`- and `system`-scoped dependencies and any dependency whose version is
     * absent or uses property interpolation (`${...}`).
     */
    fun parseMavenDependencies(projectDir: Path): List<String> {
        val pomFile = projectDir.resolve("pom.xml")
        return try {
            val model = MavenXpp3Reader().read(pomFile.toFile().inputStream())
            model.dependencies
                .filter { it.scope !in listOf("runtime", "system") }
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

    // ─── Gradle subproject discovery ──────────────────────────────────────────

    /**
     * Discovers subproject paths declared via `include()` in `settings.gradle` or
     * `settings.gradle.kts`. Returns paths such as `[":module1", ":module2"]`.
     */
    fun discoverSubprojects(projectDir: Path): List<String> {
        val settingsFile = findSettingsFile(projectDir) ?: return emptyList()
        return try {
            val text = settingsFile.toFile().readText()
            val discovered = linkedSetOf<String>()

            // Kotlin/Groovy forms with parentheses, e.g. include(":api"), include("api", "core")
            val includeCallPattern = Regex("""(?m)\binclude\b\s*\(([^)]*)\)""")
            includeCallPattern.findAll(text).forEach { match ->
                val args = match.groupValues[1]
                extractQuotedValues(args).mapNotNullTo(discovered, ::normalizeSubprojectPath)
            }

            // Groovy form without parentheses, e.g. include ':service', ':common'
            val includeNoParenPattern = Regex("""(?m)\binclude\b\s+([^\n]+)""")
            includeNoParenPattern.findAll(text).forEach { match ->
                val args = match.groupValues[1]
                extractQuotedValues(args).mapNotNullTo(discovered, ::normalizeSubprojectPath)
            }

            discovered.toList()
        } catch (e: Exception) {
            logger.warn("Failed to parse settings file for subprojects: ${e.message}")
            emptyList()
        }
    }

    private fun extractQuotedValues(input: String): List<String> =
        Regex("""["']([^"']+)["']""").findAll(input).map { it.groupValues[1] }.toList()

    private fun normalizeSubprojectPath(raw: String): String? {
        var value = raw.trim()
        if (value.isEmpty()) return null

        value = value.replace('/', ':')
        if (!value.startsWith(":")) value = ":$value"
        value = value.replace(Regex(":+"), ":")

        return value.takeIf { it.matches(Regex(""":[a-zA-Z][\w.\-]*(?::[a-zA-Z][\w.\-]*)*""")) }
    }

    // ─── Gradle static parsing ─────────────────────────────────────────────────

    /**
     * Best-effort static regex parse of `build.gradle` / `build.gradle.kts` files for the root
     * project and all declared subprojects, combined with coordinates from any Gradle version
     * catalog files found under `gradle/\*.versions.toml`.
     *
     * **Limitations:** Dynamic expressions (computed version strings, BOM-managed versions not
     * declared in a catalog) will be absent.
     */
    fun parseGradleDependenciesStatically(projectDir: Path): List<String> {
        val subprojectDirs = discoverSubprojects(projectDir).map { subprojectPath ->
            projectDir.resolve(subprojectPath.trimStart(':').replace(':', '/'))
        }
        val buildDirs = listOf(projectDir) + subprojectDirs

        val coordPattern = Regex("""["']([a-zA-Z][\w.\-]+:[a-zA-Z][\w.\-]+:[\w.\-]+)["']""")
        // runtimeOnly and testRuntimeOnly are excluded — their JARs are not on the compile
        // classpath and are not needed for OpenRewrite type resolution.
        val threeArgPattern = Regex(
            """(?:implementation|api|compileOnly|testImplementation|testCompileOnly)\s*\(\s*["']([^"']+)["']\s*,\s*["']([^"']+)["']\s*,\s*["']([^"']+)["']\s*\)"""
        )
        // Lines that start with a runtime-only configuration keyword are skipped before
        // applying the broad coordPattern so that quoted coordinate strings on those lines
        // are not inadvertently collected.
        val runtimeOnlyLinePattern =
            Regex("""^\s*(?:runtimeOnly|testRuntimeOnly|runtime|testRuntime)\b""")

        val coordinates = mutableListOf<String>()
        for (dir in buildDirs) {
            val buildFile = findBuildFile(dir) ?: continue
            logger.debug("Stage 3: statically parsing ${buildFile.toAbsolutePath()}")
            try {
                val text = buildFile.toFile().readText()
                val compileTimeText = text.lines()
                    .filterNot { runtimeOnlyLinePattern.containsMatchIn(it) }
                    .joinToString("\n")
                coordPattern.findAll(compileTimeText).forEach { match ->
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
                "Stage 3: static parsing found ${it.size} unique coordinate(s) across " +
                    "${buildDirs.size} build file(s) + version catalog(s)"
            )
        }
    }

    // ─── Version catalog parsing ───────────────────────────────────────────────

    /**
     * Parses all `*.versions.toml` files under `<projectDir>/gradle/` and returns
     * fully-resolved `groupId:artifactId:version` coordinates from the `[libraries]` section.
     */
    fun parseVersionCatalogs(projectDir: Path): List<String> {
        val gradleDir = projectDir.resolve("gradle")
        if (!gradleDir.exists()) return emptyList()

        val catalogFiles = gradleDir.toFile()
            .listFiles { f -> f.isFile && f.name.endsWith(".versions.toml") }
            ?.toList()
            ?: return emptyList()

        val coordinates = mutableListOf<String>()
        for (catalogFile in catalogFiles) {
            logger.debug("Stage 3: parsing version catalog ${catalogFile.absolutePath}")
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

            if (line.startsWith("[")) {
                section = Regex("""\[(\w+)]""").find(line)?.groupValues?.get(1) ?: ""
                continue
            }

            when (section) {
                "versions" -> {
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
     * Parses a single `[libraries]` entry from a version catalog TOML file. Returns `null` when
     * the version cannot be determined (BOM-managed or rich constraint).
     */
    internal fun parseCatalogLibraryEntry(line: String, versions: Map<String, String>): String? {
        // Form: string literal with all three parts
        val literal = Regex(
            """^[\w.\-]+\s*=\s*"([a-zA-Z][\w.\-]+:[a-zA-Z][\w.\-]+:[\w.\-]+)"""
        ).find(line)
        if (literal != null) return literal.groupValues[1]

        val groupArtifact =
            Regex("""module\s*=\s*"([a-zA-Z][\w.\-]+:[a-zA-Z][\w.\-]+)""").find(line)
                ?.groupValues?.get(1)
                ?: run {
                    val g = Regex("""group\s*=\s*"([^"]+)""").find(line)?.groupValues?.get(1)
                    val n = Regex("""name\s*=\s*"([^"]+)""").find(line)?.groupValues?.get(1)
                    if (g != null && n != null) "$g:$n" else null
                }
                ?: return null

        // Prefer version.ref (resolves via [versions] map), fall back to inline version = "..."
        val version =
            Regex("""version\.ref\s*=\s*"([^"]+)""").find(line)?.groupValues?.get(1)
                ?.let { versions[it] }
                ?: Regex("""version\s*=\s*"([^"]+)""").find(line)?.groupValues?.get(1)
                ?: return null

        return "$groupArtifact:$version"
    }

    // ─── Repository URL parsing ───────────────────────────────────────────────

    /**
     * Parses repository URLs from a Gradle build file using regex.
     * Recognises `mavenCentral()`, `google()`, and explicit `maven { url = "..." }` blocks.
     *
     * @param buildFileContent Pre-read build file text; when non-null, skips the disk read.
     */
    fun parseRepositoryUrls(projectDir: Path, buildFileContent: String? = null): List<String> {
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
}
