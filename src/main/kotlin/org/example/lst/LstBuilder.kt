package org.example.lst

import org.apache.maven.model.io.xpp3.MavenXpp3Reader
import org.codehaus.plexus.util.xml.Xpp3Dom
import org.openrewrite.ExecutionContext
import org.openrewrite.InMemoryExecutionContext
import org.openrewrite.SourceFile
import org.openrewrite.groovy.GroovyParser
import org.openrewrite.java.JavaParser
import org.openrewrite.java.marker.JavaVersion
import org.openrewrite.json.JsonParser
import org.openrewrite.kotlin.KotlinParser
import org.openrewrite.properties.PropertiesParser
import org.openrewrite.xml.XmlParser
import org.openrewrite.yaml.YamlParser
import org.example.config.ParseConfig
import org.example.config.ToolConfig
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.logging.Logger
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/**
 * Orchestrates the 3-stage LST building pipeline and multi-language file parsing.
 *
 * Stage 1 — Run the project's own build tool to extract the compile classpath.
 * Stage 2 — Parse the build descriptor and resolve deps via Maven Resolver.
 * Stage 3 — Use whatever is already in ~/.m2 / ~/.gradle/caches.
 */
class LstBuilder(
    private val cacheDir: Path,
    private val toolConfig: ToolConfig,
    private val buildToolStage: BuildToolStage = BuildToolStage(),
    private val depResolutionStage: DependencyResolutionStage = DependencyResolutionStage(
        cacheDir = cacheDir,
        extraRepositories = toolConfig.resolvedRepositories(),
    ),
) {
    private val log = Logger.getLogger(LstBuilder::class.java.name)

    /** Default set of extensions supported out of the box. */
    private val defaultExtensions = setOf(".java", ".kt", ".groovy", ".yaml", ".yml", ".json", ".xml", ".properties")

    /** Directories excluded from the recursive walk. */
    private val excludedDirNames = setOf(
        ".git", "build", "target", "node_modules", ".gradle", ".idea", "out", "dist",
    )

    fun build(
        projectDir: Path,
        parseConfig: ParseConfig = toolConfig.parse,
        includeExtensionsCli: List<String> = emptyList(),
        excludeExtensionsCli: List<String> = emptyList(),
        ctx: ExecutionContext = InMemoryExecutionContext { log.warning("Parse error: ${it.message}") },
    ): List<SourceFile> {
        // Determine effective extension set
        val effectiveExtensions = resolveExtensions(parseConfig, includeExtensionsCli, excludeExtensionsCli)
        log.info("Parsing extensions: $effectiveExtensions")

        // ── 3-stage classpath resolution ──────────────────────────────────────
        val classpath = resolveClasspath(projectDir)

        // ── Detect Java source/target version from build descriptor ───────────
        val javaVersionMarker = buildJavaVersionMarker(projectDir)
        log.info("Java version marker: source=${javaVersionMarker.sourceCompatibility}, target=${javaVersionMarker.targetCompatibility}")

        // ── Collect files by extension ────────────────────────────────────────
        val filesByExt = collectFiles(projectDir, effectiveExtensions, parseConfig.excludePaths)
        val totalFiles = filesByExt.values.sumOf { it.size }
        log.info("Found $totalFiles files to parse across ${filesByExt.keys.size} extension group(s)")

        // ── Parse each language ───────────────────────────────────────────────
        val allSources = mutableListOf<SourceFile>()

        filesByExt[".java"]?.let { files ->
            log.info("Parsing ${files.size} Java file(s)")
            val parser = JavaParser
                .fromJavaVersion()
                .classpath(classpath)
                .build()
            parser.parse(files, projectDir, ctx).forEach {
                allSources.add(it.withMarkers(it.markers.add(javaVersionMarker)))
            }
        }

        filesByExt[".kt"]?.let { files ->
            log.info("Parsing ${files.size} Kotlin file(s)")
            val parser = KotlinParser.builder().classpath(classpath).build()
            parser.parse(files, projectDir, ctx).forEach { allSources.add(it) }
        }

        filesByExt[".groovy"]?.let { files ->
            log.info("Parsing ${files.size} Groovy file(s)")
            val parser = GroovyParser.builder().classpath(classpath).build()
            parser.parse(files, projectDir, ctx).forEach { allSources.add(it) }
        }

        val yamlFiles = ((filesByExt[".yaml"] ?: emptyList()) + (filesByExt[".yml"] ?: emptyList()))
        if (yamlFiles.isNotEmpty()) {
            log.info("Parsing ${yamlFiles.size} YAML file(s)")
            val parser = YamlParser()
            parser.parse(yamlFiles, projectDir, ctx).forEach { allSources.add(it) }
        }

        filesByExt[".json"]?.let { files ->
            log.info("Parsing ${files.size} JSON file(s)")
            val parser = JsonParser()
            parser.parse(files, projectDir, ctx).forEach { allSources.add(it) }
        }

        filesByExt[".xml"]?.let { files ->
            log.info("Parsing ${files.size} XML file(s)")
            val parser = XmlParser()
            parser.parse(files, projectDir, ctx).forEach { allSources.add(it) }
        }

        filesByExt[".properties"]?.let { files ->
            log.info("Parsing ${files.size} properties file(s)")
            val parser = PropertiesParser()
            parser.parse(files, projectDir, ctx).forEach { allSources.add(it) }
        }

        log.info("LST build complete: ${allSources.size} SourceFile(s)")
        return allSources
    }

    // ─── Java version detection ───────────────────────────────────────────────

    private fun buildJavaVersionMarker(projectDir: Path): JavaVersion {
        val createdBy = System.getProperty("java.runtime.version") ?: System.getProperty("java.version") ?: ""
        val vmVendor = System.getProperty("java.vm.vendor") ?: ""
        val (source, target) = detectJavaVersion(projectDir)
        return JavaVersion(UUID.randomUUID(), createdBy, vmVendor, source, target)
    }

    private fun detectJavaVersion(projectDir: Path): Pair<String, String> {
        val jvmMajor = normalizeJvmVersion(System.getProperty("java.version") ?: "")
        val fallback = Pair(jvmMajor, jvmMajor)

        if (projectDir.resolve("pom.xml").toFile().exists()) {
            return detectMavenJavaVersion(projectDir) ?: fallback
        }

        val buildFile = projectDir.resolve("build.gradle.kts").takeIf { it.toFile().exists() }
            ?: projectDir.resolve("build.gradle").takeIf { it.toFile().exists() }
        if (buildFile != null) {
            return detectGradleJavaVersion(buildFile) ?: fallback
        }

        return fallback
    }

    /**
     * Extracts Java source/target version from Maven's maven-compiler-plugin.
     * Priority: plugin <release> > plugin <source>/<target> > project properties.
     */
    private fun detectMavenJavaVersion(projectDir: Path): Pair<String, String>? {
        return try {
            val model = MavenXpp3Reader().read(projectDir.resolve("pom.xml").toFile().inputStream())

            // Priority 1: maven-compiler-plugin <configuration>
            val compilerPlugin = model.build?.plugins?.find { it.artifactId == "maven-compiler-plugin" }
            val dom = compilerPlugin?.configuration as? Xpp3Dom
            if (dom != null) {
                val release = dom.getChild("release")?.value?.takeIf { it.isNotBlank() && !it.startsWith("\${") }
                if (release != null) return Pair(release, release)

                val source = dom.getChild("source")?.value?.takeIf { it.isNotBlank() && !it.startsWith("\${") }
                val target = dom.getChild("target")?.value?.takeIf { it.isNotBlank() && !it.startsWith("\${") }
                if (source != null || target != null) return Pair(source ?: target ?: "", target ?: source ?: "")
            }

            // Priority 2: project <properties>
            val props = model.properties
            val propsRelease = props["maven.compiler.release"]?.toString()?.takeIf { it.isNotBlank() }
            if (propsRelease != null) return Pair(propsRelease, propsRelease)

            val propsSource = props["maven.compiler.source"]?.toString()?.takeIf { it.isNotBlank() }
            val propsTarget = props["maven.compiler.target"]?.toString()?.takeIf { it.isNotBlank() }
            if (propsSource != null || propsTarget != null) {
                return Pair(propsSource ?: propsTarget ?: "", propsTarget ?: propsSource ?: "")
            }

            null
        } catch (e: Exception) {
            log.warning("Failed to detect Java version from pom.xml: ${e.message}")
            null
        }
    }

    /**
     * Extracts Java source/target version from a Gradle build file via regex.
     * Handles Groovy DSL (`sourceCompatibility = '17'`) and Kotlin DSL
     * (`sourceCompatibility = JavaVersion.VERSION_17`, `jvmToolchain(21)`,
     * `java { toolchain { languageVersion = JavaLanguageVersion.of(17) } }`).
     */
    private fun detectGradleJavaVersion(buildFile: Path): Pair<String, String>? {
        return try {
            val text = buildFile.toFile().readText()

            // sourceCompatibility / targetCompatibility in various forms
            val sourcePattern = Regex("""sourceCompatibility\s*[=:]\s*(?:JavaVersion\.VERSION_)?['"]?(\d+)['"]?""")
            val targetPattern = Regex("""targetCompatibility\s*[=:]\s*(?:JavaVersion\.VERSION_)?['"]?(\d+)['"]?""")
            // jvmToolchain(21) — Kotlin/Gradle toolchain shorthand
            val jvmToolchainPattern = Regex("""jvmToolchain\s*\(\s*(\d+)\s*\)""")
            // java { toolchain { languageVersion = JavaLanguageVersion.of(17) } }
            val javaToolchainPattern = Regex("""JavaLanguageVersion\.of\s*\(\s*(\d+)\s*\)""")
            // compileJava.options.release = 17 or options.release.set(17)
            val releasePattern = Regex("""[.\s]release\s*[=.(]\s*(\d+)""")

            val source = sourcePattern.find(text)?.groupValues?.get(1)
            val target = targetPattern.find(text)?.groupValues?.get(1)
            val toolchain = jvmToolchainPattern.find(text)?.groupValues?.get(1)
                ?: javaToolchainPattern.find(text)?.groupValues?.get(1)
            val release = releasePattern.find(text)?.groupValues?.get(1)

            when {
                release != null -> Pair(release, release)
                source != null || target != null -> Pair(source ?: target ?: "", target ?: source ?: "")
                toolchain != null -> Pair(toolchain, toolchain)
                else -> null
            }
        } catch (e: Exception) {
            log.warning("Failed to detect Java version from Gradle build file: ${e.message}")
            null
        }
    }

    /** Converts JVM version strings like "1.8.0_xxx" → "8", "21.0.1" → "21". */
    private fun normalizeJvmVersion(version: String): String {
        val v = if (version.startsWith("1.")) version.removePrefix("1.") else version
        return v.substringBefore(".")
    }

    // ─── Classpath resolution (3 stages) ─────────────────────────────────────

    /**
     * Directories where the project's own compiled classes might live.
     * Added to the classpath so that intra-project wildcard imports (e.g.
     * `import com.example.pkg.*;`) and cross-module references resolve correctly,
     * preventing javac from producing `Type$UnknownType` for project-owned types.
     */
    private fun projectClassDirs(projectDir: Path): List<Path> =
        listOf(
            // Maven
            projectDir.resolve("target/classes"),
            projectDir.resolve("target/test-classes"),
            // Gradle
            projectDir.resolve("build/classes/java/main"),
            projectDir.resolve("build/classes/java/test"),
            projectDir.resolve("build/classes/kotlin/main"),
            projectDir.resolve("build/classes/kotlin/test"),
        ).filter { Files.isDirectory(it) }

    private fun resolveClasspath(projectDir: Path): List<Path> {
        // Stage 1: Build tool subprocess
        log.info("Stage 1: attempting build-tool classpath extraction")
        val stage1 = buildToolStage.extractClasspath(projectDir)
        if (stage1 != null) {
            val classDirs = projectClassDirs(projectDir)
            if (classDirs.isNotEmpty()) {
                log.info("Appending ${classDirs.size} project class dir(s) to classpath")
            }
            log.info("Stage 1 succeeded: ${stage1.size} JAR(s)")
            return stage1 + classDirs
        }

        log.info("Stage 1 failed, falling through to Stage 2")

        // Stage 2: Direct Maven Resolver
        log.info("Stage 2: resolving dependencies via Maven Resolver")
        val stage2 = try {
            depResolutionStage.resolveClasspath(projectDir)
        } catch (e: Exception) {
            log.warning("Stage 2 threw an exception: ${e.message}")
            emptyList()
        }

        if (stage2.isNotEmpty()) {
            val classDirs = projectClassDirs(projectDir)
            if (classDirs.isNotEmpty()) {
                log.info("Appending ${classDirs.size} project class dir(s) to classpath")
            }
            log.info("Stage 2 succeeded: ${stage2.size} JAR(s)")
            return stage2 + classDirs
        }

        log.info("Stage 2 failed or produced no JARs, falling through to Stage 3")

        // Stage 3: Local cache scan
        log.info("Stage 3: scanning local Maven/Gradle caches")
        val directParseStage = DirectParseStage(projectDir)
        val declaredCoords = gatherDeclaredCoordinates(projectDir)
        val stage3 = directParseStage.findAvailableJars(declaredCoords)
        val classDirs = projectClassDirs(projectDir)
        if (classDirs.isNotEmpty()) {
            log.info("Appending ${classDirs.size} project class dir(s) to classpath")
        }
        log.info("Stage 3: using ${stage3.size} locally cached JAR(s) — unresolved types will be JavaType.Unknown")
        return stage3 + classDirs
    }

    private fun gatherDeclaredCoordinates(projectDir: Path): List<String> {
        // Best-effort: re-parse the build descriptor without network access
        return try {
            depResolutionStage.resolveClasspath(projectDir).map { it.toString() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ─── File collection ──────────────────────────────────────────────────────

    private fun collectFiles(
        projectDir: Path,
        effectiveExtensions: Set<String>,
        excludeGlobs: List<String>,
    ): Map<String, List<Path>> {
        val matchers = excludeGlobs.map {
            FileSystems.getDefault().getPathMatcher("glob:$it")
        }

        val result = mutableMapOf<String, MutableList<Path>>()

        Files.walk(projectDir)
            .filter { path ->
                val relative = projectDir.relativize(path)

                // Skip excluded directories
                val inExcludedDir = relative.any { part -> part.name in excludedDirNames }
                if (inExcludedDir) return@filter false

                // Skip glob-excluded paths
                val matchedGlob = matchers.any { it.matches(relative) }
                if (matchedGlob) return@filter false

                if (!path.isRegularFile()) return@filter false

                val ext = ".${path.extension}".lowercase()
                ext in effectiveExtensions
            }
            .forEach { path ->
                val ext = ".${path.extension}".lowercase()
                result.getOrPut(ext) { mutableListOf() }.add(path)
            }

        return result
    }

    private fun resolveExtensions(
        parseConfig: ParseConfig,
        includeExtensionsCli: List<String>,
        excludeExtensionsCli: List<String>,
    ): Set<String> {
        // CLI flags take precedence over config file
        val include = (includeExtensionsCli.takeIf { it.isNotEmpty() } ?: parseConfig.includeExtensions)
            .map { it.lowercase().let { e -> if (e.startsWith(".")) e else ".$e" } }

        val exclude = (excludeExtensionsCli.takeIf { it.isNotEmpty() } ?: parseConfig.excludeExtensions)
            .map { it.lowercase().let { e -> if (e.startsWith(".")) e else ".$e" } }
            .toSet()

        val base = include.takeIf { it.isNotEmpty() }?.toSet() ?: defaultExtensions
        return base - exclude
    }
}
