package io.github.skhokhlov.rewriterunner.lst

import io.github.skhokhlov.rewriterunner.AetherContext
import io.github.skhokhlov.rewriterunner.NoOpRunnerLogger
import io.github.skhokhlov.rewriterunner.RunnerLogger
import io.github.skhokhlov.rewriterunner.config.ParseConfig
import io.github.skhokhlov.rewriterunner.config.ToolConfig
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
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

/**
 * Orchestrates the 3-stage LST building pipeline and multi-language file parsing.
 *
 * **Classpath resolution (3 stages)** — runs once per [build] invocation and the result is
 * shared by all language parsers (Java, Kotlin, Groovy):
 * - Stage 1 — Run the project's own build tool (Maven/Gradle) to extract the compile classpath.
 * - Stage 2 — Parse the build descriptor and resolve deps via Maven Resolver.
 * - Stage 3 — Scan `~/.m2` / `~/.gradle/caches` for already-cached JARs matching declared deps.
 *
 * **Gradle DSL classpath** — an additional classpath resolved from the Gradle installation
 * (via `GRADLE_HOME`, the project's Gradle wrapper, or `~/.gradle/wrapper/dists/`) is added
 * on top of the regular classpath exclusively for Gradle script files:
 * - `.gradle` — Groovy DSL build scripts
 * - `*.gradle.kts` — Kotlin DSL build scripts (e.g. `build.gradle.kts`, `settings.gradle.kts`)
 *
 * Plain `.kt` sources and non-Gradle `.kts` scripts receive only the regular project classpath.
 *
 * **Java/Kotlin version detection** — each `.java`, `.kt`, and `.kts` source file receives a
 * [org.openrewrite.java.marker.JavaVersion] marker whose `sourceCompatibility`/`targetCompatibility`
 * values reflect the **nearest** build descriptor found by walking up from the file's own directory
 * toward [build]'s `projectDir`. This means each subproject's source files automatically pick up
 * that subproject's setting:
 *
 * ```
 * root/
 *   pom.xml                          ← Java 11
 *   subproject1/
 *     pom.xml                        ← Java 17   ← wins for files under subproject1/
 *     src/main/java/Hello.java       ← gets JavaVersion("17", "17")
 *   subproject2/
 *     src/main/java/World.java       ← no subproject pom.xml → falls back to root → "11"
 * ```
 *
 * Detection priority within a single build descriptor:
 * - **Maven (Java)**: plugin `<release>` > plugin `<source>`/`<target>` > `<properties>` entries.
 * - **Maven (Kotlin)**: `kotlin-maven-plugin <jvmTarget>` > then same as Java above.
 * - **Gradle (Java)**: `compileJava.options.release` > `sourceCompatibility`/`targetCompatibility` >
 *   `jvmToolchain()` / `JavaLanguageVersion.of()`.
 * - **Gradle (Kotlin)**: `jvmTarget` / `JvmTarget.JVM_N` > then same as Gradle Java above.
 *
 * Legacy `"1.8"` format is normalised to `"8"`. Unresolvable placeholders (e.g.
 * `${java.version}`) are skipped, and the walk-up continues. If no explicit version is
 * found anywhere in the ancestor chain, the running JVM's major version is used as fallback.
 */
class LstBuilder(
    private val logger: RunnerLogger = NoOpRunnerLogger,
    private val cacheDir: Path,
    private val toolConfig: ToolConfig,
    private val buildToolStage: BuildToolStage = BuildToolStage(),
    private val depResolutionStage: DependencyResolutionStage = DependencyResolutionStage(
        AetherContext.build(
            Paths.get(System.getProperty("user.home"), ".m2", "repository"),
            toolConfig.resolvedRepositories()
        )
    )
) {
    /** Default set of extensions supported out of the box. */
    private val defaultExtensions =
        setOf(
            ".java",
            ".kt",
            ".kts",
            ".groovy",
            ".gradle",
            ".yaml",
            ".yml",
            ".json",
            ".xml",
            ".properties"
        )

    /** Directories excluded from the recursive walk. */
    private val excludedDirNames = setOf(
        ".git",
        "build",
        "target",
        "node_modules",
        ".gradle",
        ".idea",
        "out",
        "dist"
    )

    /**
     * Parse all source files in [projectDir] into OpenRewrite SourceFile trees.
     *
     * Runs the 3-stage classpath resolution pipeline, then dispatches each collected file
     * to the appropriate language parser based on its extension.
     *
     * @param projectDir Root of the project to parse. Must be an existing directory.
     * @param parseConfig Extension inclusion/exclusion and glob-exclusion settings from the
     *   tool config file. CLI flags ([includeExtensionsCli], [excludeExtensionsCli]) take
     *   precedence when non-empty.
     * @param includeExtensionsCli File extensions to include, as specified via CLI or the
     *   library [io.github.skhokhlov.rewriterunner.RewriteRunner.Builder]. Overrides [parseConfig] when non-empty.
     * @param excludeExtensionsCli File extensions to skip. Overrides [parseConfig] when non-empty.
     * @param ctx OpenRewrite execution context. Defaults to an [org.openrewrite.InMemoryExecutionContext]
     *   that logs parse warnings without aborting.
     * @return The list of all parsed [org.openrewrite.SourceFile]s, one per source file found.
     */
    fun build(
        projectDir: Path,
        parseConfig: ParseConfig = toolConfig.parse,
        includeExtensionsCli: List<String> = emptyList(),
        excludeExtensionsCli: List<String> = emptyList(),
        ctx: ExecutionContext =
            InMemoryExecutionContext { logger.warn("Parse error: ${it.message}") }
    ): List<SourceFile> {
        // Determine effective extension set
        val effectiveExtensions =
            resolveExtensions(parseConfig, includeExtensionsCli, excludeExtensionsCli)
        logger.info("Parsing extensions: $effectiveExtensions")

        // ── 3-stage classpath resolution ──────────────────────────────────────
        val classpath = resolveClasspath(projectDir)

        // ── Per-file version caches (module dir → (source, target)) ─────────
        // Populated lazily; null means a build file was found but carried no
        // explicit version — triggers continued walk-up on next access.
        val javaVersionCache = mutableMapOf<Path, Pair<String, String>?>()
        val kotlinVersionCache = mutableMapOf<Path, Pair<String, String>?>()

        // ── Collect files by extension ────────────────────────────────────────
        val filesByExt = collectFiles(projectDir, effectiveExtensions, parseConfig.excludePaths)
        val totalFiles = filesByExt.values.sumOf { it.size }
        logger.lifecycle(
            "Found $totalFiles files to parse across ${filesByExt.keys.size} extension group(s)"
        )

        // ── Gradle DSL classpath (resolved at most once per build() call) ─────
        // Populated lazily; only incurs the filesystem walk when .gradle or .gradle.kts
        // files are actually present in the project.
        val gradleDslClasspath: List<Path> by lazy { resolveGradleDslClasspath(projectDir) }

        // ── Parse each language ───────────────────────────────────────────────
        val allSources = mutableListOf<SourceFile>()

        filesByExt[".java"]?.let { files ->
            logger.info("Parsing ${files.size} Java file(s)")
            val parser = JavaParser
                .fromJavaVersion()
                .classpath(classpath)
                .build()
            parser.parse(files, projectDir, ctx).forEach { sourceFile ->
                val absPath = projectDir.resolve(sourceFile.sourcePath)
                val (source, target) = detectJavaVersionForFile(
                    absPath,
                    projectDir,
                    javaVersionCache
                )
                logger.debug(
                    "Java version for ${sourceFile.sourcePath}: source=$source, target=$target"
                )
                allSources.add(
                    sourceFile.withMarkers(
                        sourceFile.markers.add(buildJavaVersionMarker(source, target))
                    )
                )
            }
        }

        filesByExt[".kt"]?.let { files ->
            logger.info("Parsing ${files.size} Kotlin file(s)")
            val parser = KotlinParser.builder().classpath(classpath).build()
            parser.parse(files, projectDir, ctx).forEach { sourceFile ->
                val absPath = projectDir.resolve(sourceFile.sourcePath)
                val (source, target) =
                    detectKotlinVersionForFile(absPath, projectDir, kotlinVersionCache)
                logger.debug(
                    "Kotlin JVM target for ${sourceFile.sourcePath}: source=$source, target=$target"
                )
                allSources.add(
                    sourceFile.withMarkers(
                        sourceFile.markers.add(buildJavaVersionMarker(source, target))
                    )
                )
            }
        }

        filesByExt[".kts"]?.let { files ->
            // Gradle Kotlin DSL scripts (*.gradle.kts) need the Gradle API on the classpath
            // for type resolution. Plain Kotlin scripts (.kts) are parsed with the project
            // classpath only, the same as regular .kt sources.
            val gradleKtsFiles = files.filter { it.name.endsWith(".gradle.kts") }
            val plainKtsFiles = files.filter { !it.name.endsWith(".gradle.kts") }

            if (plainKtsFiles.isNotEmpty()) {
                logger.info("Parsing ${plainKtsFiles.size} Kotlin Script file(s)")
                val parser = KotlinParser.builder().classpath(classpath).build()
                parser.parse(plainKtsFiles, projectDir, ctx).forEach { sourceFile ->
                    val absPath = projectDir.resolve(sourceFile.sourcePath)
                    val (source, target) =
                        detectKotlinVersionForFile(absPath, projectDir, kotlinVersionCache)
                    allSources.add(
                        sourceFile.withMarkers(
                            sourceFile.markers.add(buildJavaVersionMarker(source, target))
                        )
                    )
                }
            }

            if (gradleKtsFiles.isNotEmpty()) {
                logger.info("Parsing ${gradleKtsFiles.size} Gradle Kotlin DSL script(s)")
                if (gradleDslClasspath.isNotEmpty()) {
                    logger.info(
                        "Augmenting KotlinParser classpath with ${gradleDslClasspath.size} Gradle DSL JAR(s)"
                    )
                }
                val parser = KotlinParser.builder().classpath(
                    classpath + gradleDslClasspath
                ).build()
                parser.parse(gradleKtsFiles, projectDir, ctx).forEach { allSources.add(it) }
            }
        }

        filesByExt[".groovy"]?.let { files ->
            logger.info("Parsing ${files.size} Groovy file(s)")
            val parser = GroovyParser.builder().classpath(classpath).build()
            parser.parse(files, projectDir, ctx).forEach { allSources.add(it) }
        }

        filesByExt[".gradle"]?.let { files ->
            logger.info("Parsing ${files.size} Gradle Groovy DSL file(s)")
            if (gradleDslClasspath.isNotEmpty()) {
                logger.info(
                    "Augmenting GroovyParser classpath with ${gradleDslClasspath.size} Gradle DSL JAR(s)"
                )
            }
            val parser = GroovyParser.builder().classpath(classpath + gradleDslClasspath).build()
            parser.parse(files, projectDir, ctx).forEach { allSources.add(it) }
        }

        val yamlFiles = ((filesByExt[".yaml"] ?: emptyList()) + (filesByExt[".yml"] ?: emptyList()))
        if (yamlFiles.isNotEmpty()) {
            logger.info("Parsing ${yamlFiles.size} YAML file(s)")
            val parser = YamlParser()
            parser.parse(yamlFiles, projectDir, ctx).forEach { allSources.add(it) }
        }

        filesByExt[".json"]?.let { files ->
            logger.info("Parsing ${files.size} JSON file(s)")
            val parser = JsonParser()
            parser.parse(files, projectDir, ctx).forEach { allSources.add(it) }
        }

        filesByExt[".xml"]?.let { files ->
            logger.info("Parsing ${files.size} XML file(s)")
            val parser = XmlParser()
            parser.parse(files, projectDir, ctx).forEach { allSources.add(it) }
        }

        filesByExt[".properties"]?.let { files ->
            logger.info("Parsing ${files.size} properties file(s)")
            val parser = PropertiesParser()
            parser.parse(files, projectDir, ctx).forEach { allSources.add(it) }
        }

        logger.info("LST build complete: ${allSources.size} SourceFile(s)")
        return allSources
    }

    // ─── Java version detection ───────────────────────────────────────────────

    /** Creates a [JavaVersion] marker with the given source/target version strings. */
    private fun buildJavaVersionMarker(source: String, target: String): JavaVersion {
        val createdBy =
            System.getProperty("java.runtime.version") ?: System.getProperty("java.version") ?: ""
        val vmVendor = System.getProperty("java.vm.vendor") ?: ""
        return JavaVersion(UUID.randomUUID(), createdBy, vmVendor, source, target)
    }

    /**
     * Detects the Java source/target version for a specific source file by walking
     * up its directory tree until a build file with an explicit Java version is found.
     *
     * **Walk-up algorithm:**
     * Starting from the file's immediate parent directory, each level is examined:
     * 1. If the directory contains `pom.xml`, parse it for Maven compiler settings.
     * 2. Otherwise if it contains `build.gradle.kts` or `build.gradle`, parse it for
     *    Gradle compatibility settings.
     * 3. If no build file exists at this level, step up to the parent and repeat.
     * 4. If a build file is found but declares **no** explicit Java version (e.g. an
     *    aggregator `pom.xml` with no compiler configuration), step up and continue —
     *    this enables submodule → parent version inheritance without requiring full
     *    Maven/Gradle model resolution.
     * 5. If [projectDir] is reached without finding any explicit version, return the
     *    running JVM's major version as fallback.
     *
     * **Multi-subproject example:**
     * ```
     * root/
     *   pom.xml                          ← Java 11 (root)
     *   subproject1/
     *     pom.xml                        ← Java 17
     *     src/main/java/Hello.java  →  walk: subproject1/src/main/java/ (no pom)
     *                                        subproject1/src/main/ (no pom)
     *                                        subproject1/src/ (no pom)
     *                                        subproject1/ → pom.xml found → "17" ✓
     *   subproject2/
     *     src/main/java/World.java  →  walk: ... no pom in subproject2/ ...
     *                                        root/ → pom.xml found → "11" ✓
     * ```
     *
     * **Caching:** results are stored per directory so each `pom.xml` / `build.gradle`
     * is read at most once per [build] invocation regardless of how many Java files
     * reside under a given module. A `null` cache entry means a build file was found
     * but contained no explicit version (triggers continued walk-up on next access).
     *
     * @param absFilePath Absolute path of the Java source file being parsed.
     * @param projectDir  Root directory of the project (inclusive upper bound of the walk).
     * @param cache       Mutable cache: directory → detected `(source, target)` pair,
     *                    or `null` when a build file was present but had no explicit version.
     */
    private fun detectJavaVersionForFile(
        absFilePath: Path,
        projectDir: Path,
        cache: MutableMap<Path, Pair<String, String>?>
    ): Pair<String, String> {
        val jvmMajor = normalizeJvmVersion(System.getProperty("java.version") ?: "")
        val fallback = Pair(jvmMajor, jvmMajor)

        var dir: Path? = absFilePath.parent
        while (dir != null && dir.startsWith(projectDir)) {
            if (dir in cache) {
                val cached = cache[dir]
                if (cached != null) return cached
                // null → build file here but no explicit version; keep walking up
            } else {
                val pomFile = dir.resolve("pom.xml")
                val buildFile = dir.resolve("build.gradle.kts").takeIf { it.exists() }
                    ?: dir.resolve("build.gradle").takeIf { it.exists() }
                val detected: Pair<String, String>? = when {
                    pomFile.exists() -> detectMavenJavaVersion(dir)

                    buildFile != null -> detectGradleJavaVersion(buildFile)

                    else -> {
                        // No build file at this level; skip caching and try parent
                        if (dir == projectDir) break
                        dir = dir.parent
                        continue
                    }
                }
                cache[dir] = detected // cache null too (build file present, no explicit version)
                if (detected != null) return detected
            }
            if (dir == projectDir) break
            dir = dir.parent
        }
        return fallback
    }

    /**
     * Detects the JVM target version for a Kotlin source file by walking up its directory
     * tree toward [projectDir], using [detectMavenKotlinVersion] and
     * [detectGradleKotlinVersion] at each level.
     *
     * The algorithm is identical to [detectJavaVersionForFile]: Kotlin-specific settings
     * (`kotlinOptions.jvmTarget`, `kotlin-maven-plugin <jvmTarget>`) are checked first;
     * if absent, the same `jvmToolchain` / `sourceCompatibility` / maven-compiler-plugin
     * settings used for Java are treated as the JVM target for Kotlin too.
     *
     * If no explicit version is found anywhere in the ancestor chain, falls back to the
     * running JVM's major version.
     *
     * @param absFilePath Absolute path of the Kotlin source file being parsed.
     * @param projectDir  Root directory of the project (inclusive upper bound of the walk).
     * @param cache       Mutable cache: directory → detected `(source, target)` pair,
     *                    or `null` when a build file was present but had no explicit version.
     */
    private fun detectKotlinVersionForFile(
        absFilePath: Path,
        projectDir: Path,
        cache: MutableMap<Path, Pair<String, String>?>
    ): Pair<String, String> {
        val jvmMajor = normalizeJvmVersion(System.getProperty("java.version") ?: "")
        val fallback = Pair(jvmMajor, jvmMajor)

        var dir: Path? = absFilePath.parent
        while (dir != null && dir.startsWith(projectDir)) {
            if (dir in cache) {
                val cached = cache[dir]
                if (cached != null) return cached
                // null → build file here but no explicit version; keep walking up
            } else {
                val pomFile = dir.resolve("pom.xml")
                val buildFile = dir.resolve("build.gradle.kts").takeIf { it.exists() }
                    ?: dir.resolve("build.gradle").takeIf { it.exists() }
                val detected: Pair<String, String>? = when {
                    pomFile.exists() -> detectMavenKotlinVersion(dir)

                    buildFile != null -> detectGradleKotlinVersion(buildFile)

                    else -> {
                        if (dir == projectDir) break
                        dir = dir.parent
                        continue
                    }
                }
                cache[dir] = detected
                if (detected != null) return detected
            }
            if (dir == projectDir) break
            dir = dir.parent
        }
        return fallback
    }

    /**
     * Extracts the JVM target version for Kotlin from Maven's `kotlin-maven-plugin`
     * `<jvmTarget>` configuration, then falls back to [detectMavenJavaVersion].
     */
    private fun detectMavenKotlinVersion(dir: Path): Pair<String, String>? {
        return try {
            val model = MavenXpp3Reader().read(dir.resolve("pom.xml").toFile().inputStream())
            val kotlinPlugin = model.build?.plugins?.find { it.artifactId == "kotlin-maven-plugin" }
            val dom = kotlinPlugin?.configuration as? Xpp3Dom
            val jvmTarget = dom?.getChild("jvmTarget")?.value
                ?.takeIf { it.isNotBlank() && !it.startsWith("\${") }
            if (jvmTarget != null) {
                val v = normalizeJvmVersion(jvmTarget)
                return Pair(v, v)
            }
            detectMavenJavaVersion(dir)
        } catch (e: Exception) {
            logger.warn("Failed to detect Kotlin JVM target from pom.xml: ${e.message}")
            null
        }
    }

    /**
     * Extracts the JVM target version for Kotlin from a Gradle build file.
     *
     * Checks `kotlinOptions.jvmTarget` / `JvmTarget.JVM_N` first (highest precedence),
     * then delegates to [detectGradleJavaVersion] for shared settings
     * (`compileJava.options.release`, `sourceCompatibility`, `jvmToolchain`).
     *
     * Handles:
     * - `jvmTarget = "17"` / `jvmTarget = '17'` (Groovy/Kotlin DSL)
     * - `jvmTarget.set(JvmTarget.JVM_17)` (Kotlin DSL property API)
     * - `jvmTarget.set(a.b.JvmTarget.JVM_17)` (fully-qualified class name)
     * - `JvmTarget.JVM_1_8` → normalized to "8"
     */
    private fun detectGradleKotlinVersion(buildFile: Path): Pair<String, String>? = try {
        val text = buildFile.toFile().readText()
        // jvmTarget = "17"  |  jvmTarget = JvmTarget.JVM_17
        // jvmTarget.set(JvmTarget.JVM_17)  |  jvmTarget.set(a.b.c.JvmTarget.JVM_1_8)
        val jvmTargetPattern =
            Regex(
                """jvmTarget\s*(?:[=:]\s*|\.set\s*\(\s*)""" +
                    """(?:(?:\w+\.)*JvmTarget\.JVM_(?:1_)?)?["']?(?:1\.)?(\d+)["']?"""
            )
        val jvmTarget = jvmTargetPattern.find(text)?.groupValues?.get(1)
        if (jvmTarget != null) {
            Pair(jvmTarget, jvmTarget)
        } else {
            detectGradleJavaVersion(buildFile)
        }
    } catch (e: Exception) {
        logger.warn("Failed to detect Kotlin JVM target from Gradle build file: ${e.message}")
        null
    }

    /**
     * Extracts Java source/target version from Maven's maven-compiler-plugin.
     * Priority: plugin <release> > plugin <source>/<target> > project properties.
     *
     * Legacy "1.8" format (e.g. `<source>1.8</source>`) is normalised to "8".
     */
    private fun detectMavenJavaVersion(projectDir: Path): Pair<String, String>? {
        return try {
            val model = MavenXpp3Reader().read(projectDir.resolve("pom.xml").toFile().inputStream())

            // Priority 1: maven-compiler-plugin <configuration>
            val compilerPlugin = model.build?.plugins?.find {
                it.artifactId ==
                    "maven-compiler-plugin"
            }
            val dom = compilerPlugin?.configuration as? Xpp3Dom
            if (dom != null) {
                val release = dom.getChild("release")?.value?.takeIf {
                    it.isNotBlank() &&
                        !it.startsWith("\${")
                }
                if (release != null) {
                    val v = normalizeJvmVersion(release)
                    return Pair(v, v)
                }

                val source = dom.getChild("source")?.value?.takeIf {
                    it.isNotBlank() &&
                        !it.startsWith("\${")
                }
                val target = dom.getChild("target")?.value?.takeIf {
                    it.isNotBlank() &&
                        !it.startsWith("\${")
                }
                if (source != null || target != null) {
                    return Pair(
                        normalizeJvmVersion(source ?: target ?: ""),
                        normalizeJvmVersion(target ?: source ?: "")
                    )
                }
            }

            // Priority 2: project <properties>
            val props = model.properties
            val propsRelease = props["maven.compiler.release"]?.toString()?.takeIf {
                it.isNotBlank()
            }
            if (propsRelease != null) {
                val v = normalizeJvmVersion(propsRelease)
                return Pair(v, v)
            }

            val propsSource = props["maven.compiler.source"]?.toString()?.takeIf { it.isNotBlank() }
            val propsTarget = props["maven.compiler.target"]?.toString()?.takeIf { it.isNotBlank() }
            if (propsSource != null || propsTarget != null) {
                return Pair(
                    normalizeJvmVersion(propsSource ?: propsTarget ?: ""),
                    normalizeJvmVersion(propsTarget ?: propsSource ?: "")
                )
            }

            null
        } catch (e: Exception) {
            logger.warn("Failed to detect Java version from pom.xml: ${e.message}")
            null
        }
    }

    /**
     * Extracts Java source/target version from a Gradle build file via regex.
     * Handles Groovy DSL (`sourceCompatibility = '17'`) and Kotlin DSL
     * (`sourceCompatibility = JavaVersion.VERSION_17`, `jvmToolchain(21)`,
     * `java { toolchain { languageVersion = JavaLanguageVersion.of(17) } }`).
     */
    private fun detectGradleJavaVersion(buildFile: Path): Pair<String, String>? = try {
        val text = buildFile.toFile().readText()

        // sourceCompatibility / targetCompatibility in various forms.
        // Handles quoted strings ('17', '1.8'), JavaVersion constants (VERSION_17,
        // VERSION_1_8), and the legacy "1.N" format used for Java 8 (maps to "N").
        val sourcePattern =
            Regex(
                """sourceCompatibility\s*[=:]\s*(?:JavaVersion\.VERSION_(?:1_)?)?['"]?(?:1\.)?(\d+)['"]?"""
            )
        val targetPattern =
            Regex(
                """targetCompatibility\s*[=:]\s*(?:JavaVersion\.VERSION_(?:1_)?)?['"]?(?:1\.)?(\d+)['"]?"""
            )
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

            source != null || target != null -> Pair(
                source ?: target ?: "",
                target ?: source ?: ""
            )

            toolchain != null -> Pair(toolchain, toolchain)

            else -> null
        }
    } catch (e: Exception) {
        logger.warn("Failed to detect Java version from Gradle build file: ${e.message}")
        null
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
    private fun projectClassDirs(projectDir: Path): List<Path> = listOf(
        // Maven
        projectDir.resolve("target/classes"),
        projectDir.resolve("target/test-classes"),
        // Gradle
        projectDir.resolve("build/classes/java/main"),
        projectDir.resolve("build/classes/java/test"),
        projectDir.resolve("build/classes/kotlin/main"),
        projectDir.resolve("build/classes/kotlin/test")
    ).filter { Files.isDirectory(it) }

    private fun resolveClasspath(projectDir: Path): List<Path> {
        // Stage 1: Build tool subprocess
        logger.info("Stage 1: attempting build-tool classpath extraction")
        val stage1 = buildToolStage.extractClasspath(projectDir)
        if (stage1 != null) {
            // If there are no pre-compiled class directories, try compiling now so that
            // intra-project type references resolve instead of becoming JavaType.Unknown.
            var classDirs = projectClassDirs(projectDir)
            if (classDirs.isEmpty()) {
                logger.info("No compiled class directories found — attempting compilation")
                buildToolStage.tryCompile(projectDir)
                classDirs = projectClassDirs(projectDir)
            }
            if (classDirs.isNotEmpty()) {
                logger.info("Appending ${classDirs.size} project class dir(s) to classpath")
            }
            logger.info("Stage 1 succeeded: ${stage1.size} JAR(s)")
            return stage1 + classDirs
        }

        logger.info("Stage 1 failed, falling through to Stage 2")

        // Stage 2: Direct Maven Resolver
        logger.info("Stage 2: resolving dependencies via Maven Resolver")
        val stage2 = try {
            depResolutionStage.resolveClasspath(projectDir)
        } catch (e: Exception) {
            logger.warn("Stage 2 threw an exception: ${e.message}")
            emptyList()
        }

        if (stage2.isNotEmpty()) {
            val classDirs = projectClassDirs(projectDir)
            if (classDirs.isNotEmpty()) {
                logger.info("Appending ${classDirs.size} project class dir(s) to classpath")
            }
            logger.info("Stage 2 succeeded: ${stage2.size} JAR(s)")
            return stage2 + classDirs
        }

        logger.info("Stage 2 failed or produced no JARs, falling through to Stage 3")

        // Stage 3: Local cache scan
        logger.info("Stage 3: scanning local Maven/Gradle caches")
        val directParseStage = DirectParseStage(projectDir, logger)
        val declaredCoords = gatherDeclaredCoordinates(projectDir)
        val stage3 = directParseStage.findAvailableJars(declaredCoords)
        val classDirs = projectClassDirs(projectDir)
        if (classDirs.isNotEmpty()) {
            logger.info("Appending ${classDirs.size} project class dir(s) to classpath")
        }
        logger.info(
            "Stage 3: using ${stage3.size} locally cached JAR(s) — unresolved types will be JavaType.Unknown"
        )
        return stage3 + classDirs
    }

    /**
     * Extract declared dependency coordinates from the project's build descriptor without
     * triggering any network downloads. Returns `groupId:artifactId:version` strings
     * suitable for [DirectParseStage.findAvailableJars].
     *
     * Previously this called [DependencyResolutionStage.resolveClasspath], which returns
     * `List<Path>` (local JAR paths). Passing those paths to [DirectParseStage] caused
     * [DirectParseStage.parseCoord] to always return null (paths contain no `:` triplet),
     * making Stage 3 a permanent no-op.
     */
    internal fun gatherDeclaredCoordinates(projectDir: Path): List<String> = try {
        when {
            projectDir.resolve("pom.xml").exists() ->
                depResolutionStage.parseMavenDependencies(projectDir)

            else ->
                depResolutionStage.parseGradleDependencies(projectDir)
        }
    } catch (_: Exception) {
        emptyList()
    }

    // ─── Gradle DSL classpath resolution ─────────────────────────────────────

    /**
     * Resolves the Gradle DSL classpath for Gradle script files.
     *
     * Used when parsing Gradle Groovy DSL build scripts (`.gradle`) and Gradle Kotlin DSL
     * build scripts (`*.gradle.kts`, e.g. `build.gradle.kts`, `settings.gradle.kts`).
     * Plain Kotlin scripts (`.kts` files whose name does not end with `.gradle.kts`) and
     * regular `.kt` sources do **not** receive this classpath.
     *
     * Lookup order:
     * 1. `GRADLE_HOME` environment variable — use `$GRADLE_HOME/lib/`.
     * 2. Gradle wrapper properties (`gradle/wrapper/gradle-wrapper.properties`) in
     *    [projectDir] — parse the declared Gradle version and locate the unpacked
     *    distribution under `~/.gradle/wrapper/dists/`.
     * 3. Any available distribution under `~/.gradle/wrapper/dists/` — picks the
     *    most recently modified one as a best-effort fallback.
     *
     * Only JARs directly inside `lib/` are included (not `lib/plugins/` or
     * `lib/agents/`) to keep the classpath focused on the core Gradle API and
     * Kotlin DSL types used in build scripts.
     *
     * Returns an empty list (and logs a warning) when no Gradle installation can be found.
     */
    internal fun resolveGradleDslClasspath(projectDir: Path): List<Path> {
        val gradleHome = findGradleHome(projectDir)
        if (gradleHome == null) {
            logger.warn(
                "Gradle DSL classpath not added: no Gradle installation found " +
                    "(set GRADLE_HOME or add a Gradle wrapper to the project)"
            )
            return emptyList()
        }
        val libDir = gradleHome.resolve("lib")
        if (!Files.isDirectory(libDir)) {
            logger.warn("Gradle lib/ directory not found at $libDir")
            return emptyList()
        }
        return Files.list(libDir).use { stream ->
            stream
                .filter { it.fileName.toString().endsWith(".jar") }
                .filter { Files.isRegularFile(it) }
                .toList()
        }
    }

    /**
     * Resolves the root directory of a Gradle installation, trying (in order):
     * 1. `GRADLE_HOME` env var.
     * 2. Gradle version declared in the project's wrapper properties.
     * 3. Any distribution cached under `~/.gradle/wrapper/dists/` (most recently modified).
     */
    private fun findGradleHome(projectDir: Path): Path? {
        // 1. Explicit GRADLE_HOME
        val gradleHomeEnv = System.getenv("GRADLE_HOME")
        if (!gradleHomeEnv.isNullOrBlank()) {
            val path = Path.of(gradleHomeEnv)
            if (Files.isDirectory(path)) {
                logger.info("Using Gradle installation from GRADLE_HOME: $path")
                return path
            }
        }

        val gradleUserHome = Path.of(System.getProperty("user.home")).resolve(".gradle")
        val distsRoot = gradleUserHome.resolve("wrapper/dists")

        // 2. Wrapper properties — extract version and find matching distribution
        val wrapperProps = projectDir.resolve("gradle/wrapper/gradle-wrapper.properties")
        if (Files.isRegularFile(wrapperProps)) {
            val gradleVersion = parseGradleVersionFromWrapper(wrapperProps)
            if (gradleVersion != null && Files.isDirectory(distsRoot)) {
                val match = findGradleDistribution(distsRoot, gradleVersion)
                if (match != null) {
                    logger.info(
                        "Using Gradle $gradleVersion distribution from wrapper cache: $match"
                    )
                    return match
                }
            }
        }

        // 3. Best-effort: newest distribution in ~/.gradle/wrapper/dists/
        if (Files.isDirectory(distsRoot)) {
            val newest = Files.list(distsRoot).use { stream ->
                stream
                    .filter { Files.isDirectory(it) }
                    .filter { it.fileName.toString().startsWith("gradle-") }
                    .flatMap { distDir ->
                        // Structure: distDir/<hash>/gradle-<version>/ (the actual Gradle home).
                        // Collect to list inside the use block to avoid closing the stream early.
                        Files.list(distDir).use { hashStream ->
                            hashStream
                                .filter { Files.isDirectory(it) }
                                .flatMap { hashDir ->
                                    Files.list(hashDir).use { subStream ->
                                        subStream
                                            .filter { Files.isDirectory(it.resolve("lib")) }
                                            .toList()
                                            .stream()
                                    }
                                }
                                .toList()
                                .stream()
                        }
                    }
                    .max(Comparator.comparingLong { Files.getLastModifiedTime(it).toMillis() })
                    .orElse(null)
            }
            if (newest != null) {
                logger.info("Using Gradle distribution (best-effort fallback): $newest")
                return newest
            }
        }

        return null
    }

    /**
     * Parses the Gradle version string from `gradle-wrapper.properties`.
     * Extracts the version from the `distributionUrl` value, e.g.
     * `https://services.gradle.org/distributions/gradle-8.7-bin.zip` → `"8.7"`.
     */
    internal fun parseGradleVersionFromWrapper(wrapperProps: Path): String? = try {
        val props = java.util.Properties()
        wrapperProps.toFile().inputStream().use { props.load(it) }
        val url = props.getProperty("distributionUrl") ?: return null
        // Match "gradle-X.Y[.Z][-qualifier-N]-bin" or "-all", e.g.:
        //   gradle-8.7-bin.zip          → "8.7"
        //   gradle-8.7.3-bin.zip        → "8.7.3"
        //   gradle-9.0-rc-1-bin.zip     → "9.0-rc-1"
        //   gradle-9.0-milestone-1-bin.zip → "9.0-milestone-1"
        Regex("""gradle-(\d+\.\d+(?:\.\d+)?(?:-[a-zA-Z]+-\d+)?)-(?:bin|all)""")
            .find(url)?.groupValues?.get(1)
    } catch (e: Exception) {
        logger.warn("Failed to parse Gradle wrapper properties: ${e.message}")
        null
    }

    /**
     * Scans [distsRoot] for an unpacked Gradle distribution matching [version].
     *
     * The Gradle wrapper unpacks distributions into a three-level hierarchy:
     * `<distsRoot>/gradle-<version>-<type>/<hash>/gradle-<version>/`
     * where the innermost directory is the actual Gradle home (it contains `lib/`).
     *
     * Returns the unpacked distribution root (containing `lib/`) or null if not found.
     */
    private fun findGradleDistribution(distsRoot: Path, version: String): Path? {
        if (!Files.isDirectory(distsRoot)) return null
        return Files.list(distsRoot).use { stream ->
            stream
                .filter { Files.isDirectory(it) }
                .filter { it.fileName.toString().startsWith("gradle-$version-") }
                .flatMap { distDir ->
                    // distDir/<hash>/gradle-<version>/ is the actual Gradle home.
                    Files.list(distDir).use { hashStream ->
                        hashStream
                            .filter { Files.isDirectory(it) }
                            .flatMap { hashDir ->
                                Files.list(hashDir).use { subStream ->
                                    subStream
                                        .filter { Files.isDirectory(it.resolve("lib")) }
                                        .toList()
                                        .stream()
                                }
                            }
                            .toList()
                            .stream()
                    }
                }
                .findFirst()
                .orElse(null)
        }
    }

    // ─── File collection ──────────────────────────────────────────────────────

    private fun collectFiles(
        projectDir: Path,
        effectiveExtensions: Set<String>,
        excludeGlobs: List<String>
    ): Map<String, List<Path>> {
        val matchers = excludeGlobs.map {
            FileSystems.getDefault().getPathMatcher("glob:$it")
        }

        val result = mutableMapOf<String, MutableList<Path>>()

        Files.walk(projectDir).use { stream ->
            stream.filter { path ->
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
            }.forEach { path ->
                val ext = ".${path.extension}".lowercase()
                result.getOrPut(ext) { mutableListOf() }.add(path)
            }
        }

        return result
    }

    private fun resolveExtensions(
        parseConfig: ParseConfig,
        includeExtensionsCli: List<String>,
        excludeExtensionsCli: List<String>
    ): Set<String> {
        // CLI flags take precedence over config file
        val include = (
            includeExtensionsCli.takeIf { it.isNotEmpty() }
                ?: parseConfig.includeExtensions
            )
            .map { it.lowercase().let { e -> if (e.startsWith(".")) e else ".$e" } }

        val exclude = (
            excludeExtensionsCli.takeIf { it.isNotEmpty() }
                ?: parseConfig.excludeExtensions
            )
            .map { it.lowercase().let { e -> if (e.startsWith(".")) e else ".$e" } }
            .toSet()

        val base = include.takeIf { it.isNotEmpty() }?.toSet() ?: defaultExtensions
        return base - exclude
    }
}
