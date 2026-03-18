package io.github.skhokhlov.rewriterunner.lst

import io.github.skhokhlov.rewriterunner.AetherContext
import io.github.skhokhlov.rewriterunner.RunnerLogger
import io.github.skhokhlov.rewriterunner.config.ParseConfig
import io.github.skhokhlov.rewriterunner.config.ToolConfig
import io.github.skhokhlov.rewriterunner.lst.utils.ClasspathResolutionResult
import io.github.skhokhlov.rewriterunner.lst.utils.FileCollector
import io.github.skhokhlov.rewriterunner.lst.utils.GradleDslClasspathResolver
import io.github.skhokhlov.rewriterunner.lst.utils.MarkerFactory
import io.github.skhokhlov.rewriterunner.lst.utils.StaticBuildFileParser
import io.github.skhokhlov.rewriterunner.lst.utils.VersionDetector
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.io.path.name
import org.openrewrite.ExecutionContext
import org.openrewrite.InMemoryExecutionContext
import org.openrewrite.SourceFile
import org.openrewrite.docker.DockerParser
import org.openrewrite.gradle.GradleParser
import org.openrewrite.groovy.GroovyParser
import org.openrewrite.hcl.HclParser
import org.openrewrite.java.JavaParser
import org.openrewrite.java.internal.JavaTypeCache
import org.openrewrite.java.marker.JavaSourceSet
import org.openrewrite.json.JsonParser
import org.openrewrite.kotlin.KotlinParser
import org.openrewrite.maven.MavenParser
import org.openrewrite.properties.PropertiesParser
import org.openrewrite.protobuf.ProtoParser
import org.openrewrite.toml.TomlParser
import org.openrewrite.xml.XmlParser
import org.openrewrite.yaml.YamlParser

/**
 * Orchestrates the 4-stage LST building pipeline and multi-language file parsing.
 *
 * **Classpath resolution (4 stages)** — runs once per [build] invocation and the result is
 * shared by all JVM language parsers (Java, Kotlin, Groovy):
 * - Stage 1 — [ProjectBuildStage] Run the project's own build tool (Maven/Gradle) to extract the compile classpath.
 * - Stage 2 — [DependencyResolutionStage] Run `mvn dependency:tree` / `gradle dependencies` subprocesses and resolve via Maven Resolver.
 * - Stage 3 — [BuildFileParseStage] Parse build files statically and resolve via Maven Resolver POM traversal.
 * - Stage 4 — [LocalRepositoryStage] Scan `~/.m2` / `~/.gradle/caches` for already-cached JARs matching declared deps.
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
 * toward [build]'s `projectDir`. See [io.github.skhokhlov.rewriterunner.lst.utils.VersionDetector] for the full algorithm.
 *
 * **Maven POM parsing** — `pom.xml` files are routed to [org.openrewrite.maven.MavenParser],
 * producing `Xml.Document` nodes annotated with [org.openrewrite.maven.tree.MavenResolutionResult]
 * and related Maven markers. All other `*.xml` files use [org.openrewrite.xml.XmlParser].
 */
class LstBuilder(
    private val logger: RunnerLogger,
    private val cacheDir: Path,
    private val toolConfig: ToolConfig,
    private val aetherContext: AetherContext = AetherContext.build(
        localRepoDir = Paths.get(System.getProperty("user.home"), ".m2", "repository"),
        extraRepositories = toolConfig.resolvedRepositories(),
        logger = logger
    ),
    private val projectBuildStage: ProjectBuildStage = ProjectBuildStage(logger),
    private val depResolutionStage: DependencyResolutionStage = DependencyResolutionStage(
        aetherContext,
        logger
    ),
    private val buildFileParseStage: BuildFileParseStage = BuildFileParseStage(
        aetherContext,
        logger
    )
) {
    private val fileCollector = FileCollector()
    private val versionDetector = VersionDetector(logger)
    private val staticParser = StaticBuildFileParser(logger)
    private val gradleDslClasspathResolver = GradleDslClasspathResolver(logger, versionDetector)
    private val markerFactory = MarkerFactory(logger, staticParser, versionDetector)

    // ─── Thin delegation methods for backward-compatible test access ──────────

    /** Exposed for [GradleVersionParsingTest] — delegates to [VersionDetector]. */
    internal fun parseGradleVersionFromWrapper(wrapperProps: Path): String? =
        versionDetector.parseGradleVersionFromWrapper(wrapperProps)

    /** Exposed for [LstBuilderTest] Gradle DSL tests — delegates to [GradleDslClasspathResolver]. */
    internal fun resolveGradleDslClasspath(projectDir: Path): List<Path> =
        gradleDslClasspathResolver.resolveGradleDslClasspath(projectDir)

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
        val effectiveExtensions =
            fileCollector.resolveExtensions(parseConfig, includeExtensionsCli, excludeExtensionsCli)
        logger.info("Parsing extensions: $effectiveExtensions")

        // ── 4-stage classpath resolution ──────────────────────────────────────
        val resolutionResult = resolveClasspath(projectDir)
        val classpath = resolutionResult.classpath

        // ── Shared type cache — all JVM parsers share one instance ────────────
        val typeCache = JavaTypeCache()

        // ── Provenance markers — computed once, attached to all source files ──
        val buildEnv = markerFactory.buildEnvironment()
        val gitProvenance = markerFactory.gitProvenance(projectDir, buildEnv)
        val osProvenance = markerFactory.operatingSystem()
        val buildToolMarker = markerFactory.detectBuildToolMarker(projectDir)

        // ── Per-file version caches (module dir → (source, target)) ─────────
        val javaVersionCache = mutableMapOf<Path, Pair<String, String>?>()
        val kotlinVersionCache = mutableMapOf<Path, Pair<String, String>?>()

        // ── Collect files by extension ────────────────────────────────────────
        val filesByExt = fileCollector.collectFiles(
            projectDir,
            effectiveExtensions,
            parseConfig.excludePaths
        )
        val totalFiles = filesByExt.values.sumOf { it.size }
        logger.lifecycle(
            "Found $totalFiles files to parse across ${filesByExt.keys.size} extension group(s)"
        )

        // ── Warn unconditionally when all stages produced empty classpath ─────
        val jvmExtensions = setOf(".java", ".kt", ".kts", ".groovy")
        val hasJvmFiles = jvmExtensions.any { filesByExt[it]?.isNotEmpty() == true }
        if (classpath.isEmpty() && hasJvmFiles) {
            logger.warn(
                "Classpath resolution failed across all 4 stages — " +
                    "type information will be missing. Recipe results may be incomplete."
            )
        }

        // ── Gradle DSL classpath (resolved at most once per build() call) ─────
        val gradleDslClasspath: List<Path> by lazy {
            gradleDslClasspathResolver.resolveGradleDslClasspath(projectDir)
        }

        // ── JavaSourceSet markers — built once per name, shared across files ──
        val mainSourceSet: JavaSourceSet by lazy { JavaSourceSet.build("main", classpath) }
        val testSourceSet: JavaSourceSet by lazy { JavaSourceSet.build("test", classpath) }

        // ── Parse each language ───────────────────────────────────────────────
        val allSources = mutableListOf<SourceFile>()

        filesByExt[".java"]?.let { files ->
            logger.info("Parsing ${files.size} Java file(s)")
            val parser = JavaParser
                .fromJavaVersion()
                .classpath(classpath)
                .typeCache(typeCache)
                .build()
            parser.parse(files, projectDir, ctx).forEach { sourceFile ->
                val absPath = projectDir.resolve(sourceFile.sourcePath)
                val (source, target) = versionDetector.detectJavaVersionForFile(
                    absPath,
                    projectDir,
                    javaVersionCache
                )
                logger.debug(
                    "Java version for ${sourceFile.sourcePath}: source=$source, target=$target"
                )
                val sourceSet = if (isTestPath(absPath)) testSourceSet else mainSourceSet
                allSources.add(
                    sourceFile.withMarkers(
                        sourceFile.markers
                            .add(versionDetector.buildJavaVersionMarker(source, target))
                            .addIfAbsent(sourceSet)
                    )
                )
            }
        }

        filesByExt[".kt"]?.let { files ->
            logger.info("Parsing ${files.size} Kotlin file(s)")
            val parser = KotlinParser.builder().classpath(classpath).typeCache(typeCache).build()
            parser.parse(files, projectDir, ctx).forEach { sourceFile ->
                val absPath = projectDir.resolve(sourceFile.sourcePath)
                val (source, target) =
                    versionDetector.detectKotlinVersionForFile(
                        absPath,
                        projectDir,
                        kotlinVersionCache
                    )
                logger.debug(
                    "Kotlin JVM target for ${sourceFile.sourcePath}: source=$source, target=$target"
                )
                val sourceSet = if (isTestPath(absPath)) testSourceSet else mainSourceSet
                allSources.add(
                    sourceFile.withMarkers(
                        sourceFile.markers
                            .add(versionDetector.buildJavaVersionMarker(source, target))
                            .addIfAbsent(sourceSet)
                    )
                )
            }
        }

        filesByExt[".kts"]?.let { files ->
            val gradleKtsFiles = files.filter { it.name.endsWith(".gradle.kts") }
            val plainKtsFiles = files.filter { !it.name.endsWith(".gradle.kts") }

            if (plainKtsFiles.isNotEmpty()) {
                logger.info("Parsing ${plainKtsFiles.size} Kotlin Script file(s)")
                val parser = KotlinParser.builder().classpath(
                    classpath
                ).typeCache(typeCache).build()
                parser.parse(plainKtsFiles, projectDir, ctx).forEach { sourceFile ->
                    val absPath = projectDir.resolve(sourceFile.sourcePath)
                    val (source, target) =
                        versionDetector.detectKotlinVersionForFile(
                            absPath,
                            projectDir,
                            kotlinVersionCache
                        )
                    allSources.add(
                        sourceFile.withMarkers(
                            sourceFile.markers.add(
                                versionDetector.buildJavaVersionMarker(source, target)
                            )
                        )
                    )
                }
            }

            if (gradleKtsFiles.isNotEmpty()) {
                logger.info("Parsing ${gradleKtsFiles.size} Gradle Kotlin DSL script(s)")
                if (gradleDslClasspath.isNotEmpty()) {
                    logger.info(
                        "Augmenting GradleParser KTS classpath with ${gradleDslClasspath.size} Gradle DSL JAR(s)"
                    )
                }
                try {
                    GradleParser.builder()
                        .kotlinParser(
                            KotlinParser.builder()
                                .classpath(classpath + gradleDslClasspath)
                                .typeCache(typeCache)
                        )
                        .buildscriptClasspath(gradleDslClasspath)
                        .build()
                        .parse(gradleKtsFiles, projectDir, ctx)
                        .forEach { sf ->
                            allSources.add(
                                markerFactory.addGradleProjectMarker(
                                    sf,
                                    projectDir,
                                    resolutionResult
                                )
                            )
                        }
                } catch (e: Exception) {
                    logger.warn(
                        "GradleParser failed for Kotlin DSL, falling back to KotlinParser: ${e.message}"
                    )
                    KotlinParser.builder()
                        .classpath(classpath + gradleDslClasspath)
                        .typeCache(typeCache)
                        .build()
                        .parse(gradleKtsFiles, projectDir, ctx)
                        .forEach { allSources.add(it) }
                }
            }
        }

        filesByExt[".groovy"]?.let { files ->
            logger.info("Parsing ${files.size} Groovy file(s)")
            val parser = GroovyParser.builder().classpath(classpath).typeCache(typeCache).build()
            parser.parse(files, projectDir, ctx).forEach { sourceFile ->
                val absPath = projectDir.resolve(sourceFile.sourcePath)
                val sourceSet = if (isTestPath(absPath)) testSourceSet else mainSourceSet
                allSources.add(
                    sourceFile.withMarkers(sourceFile.markers.addIfAbsent(sourceSet))
                )
            }
        }

        filesByExt[".gradle"]?.let { files ->
            logger.info("Parsing ${files.size} Gradle Groovy DSL file(s)")
            if (gradleDslClasspath.isNotEmpty()) {
                logger.info(
                    "Augmenting GradleParser classpath with ${gradleDslClasspath.size} Gradle DSL JAR(s)"
                )
            }
            try {
                GradleParser.builder()
                    .groovyParser(
                        GroovyParser.builder()
                            .classpath(classpath + gradleDslClasspath)
                            .typeCache(typeCache)
                    )
                    .buildscriptClasspath(gradleDslClasspath)
                    .build()
                    .parse(files, projectDir, ctx)
                    .forEach { sf ->
                        allSources.add(
                            markerFactory.addGradleProjectMarker(sf, projectDir, resolutionResult)
                        )
                    }
            } catch (e: Exception) {
                logger.warn(
                    "GradleParser failed for Groovy DSL, falling back to GroovyParser: ${e.message}"
                )
                GroovyParser.builder()
                    .classpath(classpath + gradleDslClasspath)
                    .typeCache(typeCache)
                    .build()
                    .parse(files, projectDir, ctx)
                    .forEach { allSources.add(it) }
            }
        }

        val yamlFiles =
            ((filesByExt[".yaml"] ?: emptyList()) + (filesByExt[".yml"] ?: emptyList()))
        if (yamlFiles.isNotEmpty()) {
            logger.info("Parsing ${yamlFiles.size} YAML file(s)")
            YamlParser().parse(yamlFiles, projectDir, ctx).forEach { allSources.add(it) }
        }

        filesByExt[".json"]?.let { files ->
            logger.info("Parsing ${files.size} JSON file(s)")
            JsonParser().parse(files, projectDir, ctx).forEach { allSources.add(it) }
        }

        filesByExt[".xml"]?.let { files ->
            val pomFiles = files.filter { it.name == "pom.xml" }
            val otherXmlFiles = files.filter { it.name != "pom.xml" }

            if (pomFiles.isNotEmpty()) {
                logger.info("Parsing ${pomFiles.size} Maven POM file(s) with MavenParser")
                MavenParser.builder().build()
                    .parse(pomFiles, projectDir, ctx)
                    .forEach { allSources.add(it) }
            }

            if (otherXmlFiles.isNotEmpty()) {
                logger.info("Parsing ${otherXmlFiles.size} XML file(s)")
                XmlParser().parse(otherXmlFiles, projectDir, ctx).forEach { allSources.add(it) }
            }
        }

        filesByExt[".properties"]?.let { files ->
            logger.info("Parsing ${files.size} properties file(s)")
            PropertiesParser().parse(files, projectDir, ctx).forEach { allSources.add(it) }
        }

        filesByExt[".toml"]?.let { files ->
            logger.info("Parsing ${files.size} TOML file(s)")
            TomlParser.builder().build()
                .parse(files, projectDir, ctx)
                .forEach { allSources.add(it) }
        }

        val hclFiles = listOfNotNull(
            filesByExt[".hcl"],
            filesByExt[".tf"],
            filesByExt[".tfvars"]
        ).flatten()
        if (hclFiles.isNotEmpty()) {
            logger.info("Parsing ${hclFiles.size} HCL file(s)")
            HclParser.builder().build()
                .parse(hclFiles, projectDir, ctx)
                .forEach { allSources.add(it) }
        }

        filesByExt[".proto"]?.let { files ->
            logger.info("Parsing ${files.size} Protobuf file(s)")
            ProtoParser.builder().build()
                .parse(files, projectDir, ctx)
                .forEach { allSources.add(it) }
        }

        val dockerFiles = listOfNotNull(
            filesByExt[".dockerfile"],
            filesByExt[".containerfile"]
        ).flatten()
        if (dockerFiles.isNotEmpty()) {
            logger.info("Parsing ${dockerFiles.size} Dockerfile(s)")
            DockerParser.builder().build()
                .parse(dockerFiles, projectDir, ctx)
                .forEach { allSources.add(it) }
        }

        logger.info("LST build complete: ${allSources.size} SourceFile(s)")

        // ── Attach provenance markers to every source file ────────────────────
        return allSources.map { sf ->
            var markers = sf.markers
            buildEnv?.let { markers = markers.addIfAbsent(it) }
            gitProvenance?.let { markers = markers.addIfAbsent(it) }
            markers = markers.addIfAbsent(osProvenance)
            buildToolMarker?.let { markers = markers.addIfAbsent(it) }
            if (markers === sf.markers) sf else sf.withMarkers(markers)
        }
    }

    // ─── Classpath resolution (4 stages) ─────────────────────────────────────

    /**
     * Directories where the project's own compiled classes might live.
     * Added to the classpath so that intra-project type references resolve correctly.
     */
    private fun projectClassDirs(projectDir: Path): List<Path> = listOf(
        projectDir.resolve("target/classes"),
        projectDir.resolve("target/test-classes"),
        projectDir.resolve("build/classes/java/main"),
        projectDir.resolve("build/classes/java/test"),
        projectDir.resolve("build/classes/kotlin/main"),
        projectDir.resolve("build/classes/kotlin/test")
    ).filter { Files.isDirectory(it) }

    private fun resolveClasspath(projectDir: Path): ClasspathResolutionResult {
        logger.info("Stage 1: attempting build-tool classpath extraction")
        val stage1 = projectBuildStage.extractClasspath(projectDir)
        if (stage1 != null) {
            var classDirs = projectClassDirs(projectDir)
            if (classDirs.isEmpty()) {
                logger.info("No compiled class directories found — attempting compilation")
                projectBuildStage.tryCompile(projectDir)
                classDirs = projectClassDirs(projectDir)
            }
            if (classDirs.isNotEmpty()) {
                logger.info("Appending ${classDirs.size} project class dir(s) to classpath")
            }
            logger.info("Stage 1 succeeded: ${stage1.size} JAR(s)")
            return ClasspathResolutionResult(stage1 + classDirs)
        }

        logger.warn(
            "Stage 1 (build tool) failed: no classpath extracted, falling through to Stage 2"
        )

        logger.info("Stage 2: resolving dependencies via Maven Resolver")
        val stage2Result = try {
            depResolutionStage.resolveClasspath(projectDir)
        } catch (e: Exception) {
            logger.warn("Stage 2 threw an exception: ${e.message}")
            ClasspathResolutionResult(emptyList())
        }

        if (stage2Result.classpath.isNotEmpty()) {
            val classDirs = projectClassDirs(projectDir)
            if (classDirs.isNotEmpty()) {
                logger.info("Appending ${classDirs.size} project class dir(s) to classpath")
            }
            logger.info("Stage 2 succeeded: ${stage2Result.classpath.size} JAR(s)")
            return ClasspathResolutionResult(
                classpath = stage2Result.classpath + classDirs,
                gradleProjectData = stage2Result.gradleProjectData
            )
        }

        logger.warn(
            "Stage 2 (dependency resolution) failed: no JARs resolved, falling through to Stage 3"
        )

        logger.info("Stage 3: resolving via static build file parse + POM traversal")
        val stage3 = try {
            buildFileParseStage.resolveClasspath(projectDir)
        } catch (e: Exception) {
            logger.warn("Stage 3 threw: ${e.message}")
            emptyList()
        }

        if (stage3.isNotEmpty()) {
            val classDirs = projectClassDirs(projectDir)
            if (classDirs.isNotEmpty()) {
                logger.info("Appending ${classDirs.size} project class dir(s) to classpath")
            }
            logger.info("Stage 3 succeeded: ${stage3.size} JAR(s)")
            return ClasspathResolutionResult(stage3 + classDirs)
        }

        logger.warn("Stage 3 failed — falling through to Stage 4")

        logger.info("Stage 4: scanning local Maven/Gradle caches")
        val localRepositoryStage = LocalRepositoryStage(projectDir, logger)
        val declaredCoords = gatherDeclaredCoordinates(projectDir)
        val stage4 = localRepositoryStage.findAvailableJars(declaredCoords)
        val classDirs = projectClassDirs(projectDir)
        if (classDirs.isNotEmpty()) {
            logger.info("Appending ${classDirs.size} project class dir(s) to classpath")
        }
        if (stage4.isEmpty()) {
            logger.warn("Stage 4 (local cache): no cached JARs found")
        } else {
            logger.info("Stage 4: using ${stage4.size} locally cached JAR(s)")
        }
        return ClasspathResolutionResult(stage4 + classDirs)
    }

    /**
     * Extract declared dependency coordinates from the project's build descriptor without
     * triggering any network downloads. Returns `groupId:artifactId:version` strings
     * suitable for [LocalRepositoryStage.findAvailableJars].
     */
    internal fun gatherDeclaredCoordinates(projectDir: Path): List<String> = try {
        when {
            projectDir.resolve("pom.xml").exists() ->
                staticParser.parseMavenDependencies(projectDir)

            else ->
                staticParser.parseGradleDependenciesStatically(projectDir)
        }
    } catch (_: Exception) {
        emptyList()
    }

    // ─── Source set inference ─────────────────────────────────────────────────

    private fun isTestPath(absPath: Path): Boolean = absPath.toString().contains(
        "${java.io.File.separatorChar}test${java.io.File.separatorChar}"
    )
}
