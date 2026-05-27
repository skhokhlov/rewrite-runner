package io.github.skhokhlov.rewriterunner.lst

import io.github.skhokhlov.rewriterunner.AetherContext
import io.github.skhokhlov.rewriterunner.ExecutionDiagnostics
import io.github.skhokhlov.rewriterunner.ParseFailure
import io.github.skhokhlov.rewriterunner.RunnerLogger
import io.github.skhokhlov.rewriterunner.UsedExecutionStage
import io.github.skhokhlov.rewriterunner.config.ToolConfig
import io.github.skhokhlov.rewriterunner.lst.utils.BuildToolKind
import io.github.skhokhlov.rewriterunner.lst.utils.ClasspathResolutionResult
import io.github.skhokhlov.rewriterunner.lst.utils.FileCollector
import io.github.skhokhlov.rewriterunner.lst.utils.GradleDslClasspathResolver
import io.github.skhokhlov.rewriterunner.lst.utils.MarkerFactory
import io.github.skhokhlov.rewriterunner.lst.utils.StaticBuildFileParser
import io.github.skhokhlov.rewriterunner.lst.utils.VersionDetector
import io.github.skhokhlov.rewriterunner.lst.utils.discoverBuildUnits
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.EnumSet
import java.util.function.Consumer
import kotlin.io.path.exists
import kotlin.io.path.name
import org.openrewrite.DelegatingExecutionContext
import org.openrewrite.ExecutionContext
import org.openrewrite.InMemoryExecutionContext
import org.openrewrite.ParseExceptionResult
import org.openrewrite.Parser
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
import org.openrewrite.tree.ParseError
import org.openrewrite.xml.XmlParser
import org.openrewrite.yaml.YamlParser

/** Result of a single [LstBuilder.build] invocation. */
data class LstBuildResult(
    val sourceFiles: List<SourceFile>,
    val executionDiagnostics: ExecutionDiagnostics
)

/** Extensions whose presence requires JVM classpath resolution (stages 1–4). */
private val JVM_SOURCE_EXTENSIONS = setOf(".java", ".kt", ".kts", ".groovy", ".gradle")

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
open class LstBuilder(
    private val logger: RunnerLogger,
    private val cacheDir: Path,
    private val toolConfig: ToolConfig,
    private val aetherContext: AetherContext = AetherContext.build(
        localRepoDir = Paths.get(System.getProperty("user.home"), ".m2", "repository"),
        extraRepositories = toolConfig.resolvedArtifactRepositories(),
        connectTimeout = toolConfig.artifactResolverConnectTimeout,
        requestTimeout = toolConfig.artifactResolverRequestTimeout,
        downloadThreads = toolConfig.artifactDownloadThreads,
        includeMavenCentral = toolConfig.includeMavenCentral,
        logger = logger
    ),
    private val projectBuildStage: ProjectBuildStage = ProjectBuildStage(
        logger,
        toolConfig.subprocessRunTimeout
    ),
    private val depResolutionStage: DependencyResolutionStage = DependencyResolutionStage(
        aetherContext,
        logger,
        toolConfig.subprocessRunTimeout
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
    private val markerFactory =
        MarkerFactory(logger, staticParser, versionDetector, toolConfig.subprocessRunTimeout)

    // ─── Thin delegation methods for backward-compatible test access ──────────

    /** Exposed for `GradleVersionParsingTest` — delegates to [VersionDetector]. */
    internal fun parseGradleVersionFromWrapper(wrapperProps: Path): String? =
        versionDetector.parseGradleVersionFromWrapper(wrapperProps)

    /** Exposed for `LstBuilderTest` Gradle DSL tests — delegates to [GradleDslClasspathResolver]. */
    internal fun resolveGradleDslClasspath(projectDir: Path): List<Path> =
        gradleDslClasspathResolver.resolveGradleDslClasspath(projectDir)

    /**
     * Parse all source files in [projectDir] into OpenRewrite SourceFile trees.
     *
     * Runs the 4-stage classpath resolution pipeline (skipped entirely when no JVM source
     * files survive [excludePaths]), then dispatches each collected file to the appropriate
     * language parser based on its extension.
     *
     * @param projectDir Root of the project to parse. Must be an existing directory.
     * @param excludePaths Glob patterns (relative to [projectDir]) of files to skip. Same
     *   semantics as the upstream OpenRewrite Gradle/Maven plugin exclusions. Resolved by
     *   [io.github.skhokhlov.rewriterunner.RewriteRunner] from CLI override over
     *   [io.github.skhokhlov.rewriterunner.config.ParseConfig.excludePaths] before reaching here.
     * @param ctx OpenRewrite execution context. Defaults to an [org.openrewrite.InMemoryExecutionContext]
     *   that logs parse warnings without aborting.
     * @return An [LstBuildResult] containing the parsed source files and execution diagnostics.
     */
    fun build(
        projectDir: Path,
        excludePaths: List<String> = toolConfig.parse.excludePaths,
        ctx: ExecutionContext = InMemoryExecutionContext {}
    ): LstBuildResult {
        val pendingErrors = mutableListOf<Throwable>()
        val parseCtx = object : DelegatingExecutionContext(ctx) {
            private val errorConsumer = Consumer<Throwable> { t ->
                pendingErrors.add(t)
                logger.warn("Parse warning: ${t.message}")
                logger.info("Parse warning details:\n${t.stackTraceToString()}")
                super.getOnError().accept(t)
            }

            override fun getOnError(): Consumer<Throwable> = errorConsumer
        }
        val effectiveExtensions = FileCollector.DEFAULT_EXTENSIONS
        logger.info("Parsing extensions: $effectiveExtensions")

        // ── Per-build parse-failure accumulator ───────────────────────────────
        // Declared before classpath resolution so Stage 2/3 can record malformed
        // Maven coordinate failures alongside per-file parse failures.
        val parseFailures = mutableListOf<ParseFailure>()

        // ── Collect files by extension ────────────────────────────────────────
        val filesByExt = fileCollector.collectFiles(
            projectDir,
            effectiveExtensions,
            excludePaths
        )
        val totalFiles = filesByExt.values.sumOf { it.size }
        logger.lifecycle(
            "Found $totalFiles files to parse across ${filesByExt.keys.size} extension group(s)"
        )

        // ── 4-stage classpath resolution ──────────────────────────────────────
        // Skip the four classpath stages entirely when no JVM source survived
        // [excludePaths] filtering — running mvn/gradle subprocesses is pointless when no
        // parser would consume their output.
        val hasJvmSources = filesByExt.keys.any { it in JVM_SOURCE_EXTENSIONS }
        val resolutionResult = if (hasJvmSources) {
            resolveClasspath(projectDir, parseFailures)
        } else {
            logger.info(
                "No JVM source files in scope — skipping classpath resolution stages."
            )
            ClasspathResolutionResult(emptyList())
        }
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

        // ── Warn unconditionally when all stages produced empty classpath ─────
        if (classpath.isEmpty() && hasJvmSources) {
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
            val parser = buildJavaParser(classpath, typeCache)
            val parsed = parseAndRecord("JavaParser", files, projectDir, parseFailures) {
                parser.parse(files, projectDir, parseCtx).toList()
            }
            pendingErrors.clear()
            parsed.forEach { sourceFile ->
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
            val parsed = parseAndRecord("KotlinParser", files, projectDir, parseFailures) {
                parser.parse(files, projectDir, parseCtx).toList()
            }
            pendingErrors.clear()
            parsed.forEach { sourceFile ->
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
                val parsed =
                    parseAndRecord("KotlinParser", plainKtsFiles, projectDir, parseFailures) {
                        parser.parse(plainKtsFiles, projectDir, parseCtx).toList()
                    }
                pendingErrors.clear()
                parsed.forEach { sourceFile ->
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
                val parsed = try {
                    val raw = buildGradleKtsParser(classpath, gradleDslClasspath, typeCache)
                        .parse(gradleKtsFiles, projectDir, parseCtx)
                        .toList()
                    recordParseFailures(
                        "GradleParser",
                        gradleKtsFiles,
                        raw,
                        projectDir,
                        parseFailures
                    )
                    pendingErrors.clear()
                    raw
                } catch (e: Exception) {
                    pendingErrors.clear()
                    logger.warn(
                        "GradleParser failed for Kotlin DSL, falling back to KotlinParser: ${e.message}"
                    )
                    recordBatchFailure(
                        "GradleParser",
                        gradleKtsFiles,
                        projectDir,
                        parseFailures,
                        e
                    )
                    parseAndRecord(
                        "KotlinParser",
                        gradleKtsFiles,
                        projectDir,
                        parseFailures
                    ) {
                        KotlinParser.builder()
                            .classpath(classpath + gradleDslClasspath)
                            .typeCache(typeCache)
                            .build()
                            .parse(gradleKtsFiles, projectDir, parseCtx)
                            .toList()
                    }.also { pendingErrors.clear() }
                }
                parsed.forEach { sf ->
                    allSources.add(
                        markerFactory.addGradleProjectMarker(sf, projectDir, resolutionResult)
                    )
                }
            }
        }

        filesByExt[".groovy"]?.let { files ->
            logger.info("Parsing ${files.size} Groovy file(s)")
            val parser = GroovyParser.builder().classpath(classpath).typeCache(typeCache).build()
            val parsed = parseAndRecord("GroovyParser", files, projectDir, parseFailures) {
                parser.parse(files, projectDir, parseCtx).toList()
            }
            pendingErrors.clear()
            parsed.forEach { sourceFile ->
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
            val parsed = try {
                val raw = buildGradleGroovyParser(classpath, gradleDslClasspath, typeCache)
                    .parse(files, projectDir, parseCtx)
                    .toList()
                recordParseFailures("GradleParser", files, raw, projectDir, parseFailures)
                pendingErrors.clear()
                raw
            } catch (e: Exception) {
                pendingErrors.clear()
                logger.warn(
                    "GradleParser failed for Groovy DSL, falling back to GroovyParser: ${e.message}"
                )
                recordBatchFailure("GradleParser", files, projectDir, parseFailures, e)
                parseAndRecord("GroovyParser", files, projectDir, parseFailures) {
                    GroovyParser.builder()
                        .classpath(classpath + gradleDslClasspath)
                        .typeCache(typeCache)
                        .build()
                        .parse(files, projectDir, parseCtx)
                        .toList()
                }.also { pendingErrors.clear() }
            }
            parsed.forEach { sf ->
                allSources.add(
                    markerFactory.addGradleProjectMarker(sf, projectDir, resolutionResult)
                )
            }
        }

        val yamlFiles =
            ((filesByExt[".yaml"] ?: emptyList()) + (filesByExt[".yml"] ?: emptyList()))
        if (yamlFiles.isNotEmpty()) {
            logger.info("Parsing ${yamlFiles.size} YAML file(s)")
            val parsed = parseAndRecord("YamlParser", yamlFiles, projectDir, parseFailures) {
                YamlParser().parse(yamlFiles, projectDir, parseCtx).toList()
            }
            pendingErrors.clear()
            parsed.forEach { allSources.add(it) }
        }

        filesByExt[".json"]?.let { files ->
            logger.info("Parsing ${files.size} JSON file(s)")
            val parsed = parseAndRecord("JsonParser", files, projectDir, parseFailures) {
                JsonParser().parse(files, projectDir, parseCtx).toList()
            }
            pendingErrors.clear()
            parsed.forEach { allSources.add(it) }
        }

        filesByExt[".xml"]?.let { files ->
            val pomFiles = files.filter { it.name == "pom.xml" }
            val otherXmlFiles = files.filter { it.name != "pom.xml" }

            if (pomFiles.isNotEmpty()) {
                logger.info("Parsing ${pomFiles.size} Maven POM file(s) with MavenParser")
                val parsed = parsePomFilesWithFallback(
                    pomFiles,
                    projectDir,
                    parseCtx,
                    parseFailures
                )
                pendingErrors.clear()
                parsed.forEach { allSources.add(it) }
            }

            if (otherXmlFiles.isNotEmpty()) {
                logger.info("Parsing ${otherXmlFiles.size} XML file(s)")
                val parsedXml =
                    parseAndRecord("XmlParser", otherXmlFiles, projectDir, parseFailures) {
                        buildXmlParser().parse(otherXmlFiles, projectDir, parseCtx).toList()
                    }
                pendingErrors.clear()
                parsedXml.forEach { allSources.add(it) }
            }
        }

        filesByExt[".properties"]?.let { files ->
            logger.info("Parsing ${files.size} properties file(s)")
            val parsed = parseAndRecord("PropertiesParser", files, projectDir, parseFailures) {
                PropertiesParser().parse(files, projectDir, parseCtx).toList()
            }
            pendingErrors.clear()
            parsed.forEach { allSources.add(it) }
        }

        filesByExt[".toml"]?.let { files ->
            logger.info("Parsing ${files.size} TOML file(s)")
            val parsed = parseAndRecord("TomlParser", files, projectDir, parseFailures) {
                TomlParser.builder().build()
                    .parse(files, projectDir, parseCtx)
                    .toList()
            }
            pendingErrors.clear()
            parsed.forEach { allSources.add(it) }
        }

        val hclFiles = listOfNotNull(
            filesByExt[".hcl"],
            filesByExt[".tf"],
            filesByExt[".tfvars"]
        ).flatten()
        if (hclFiles.isNotEmpty()) {
            logger.info("Parsing ${hclFiles.size} HCL file(s)")
            val parsed = parseAndRecord("HclParser", hclFiles, projectDir, parseFailures) {
                HclParser.builder().build()
                    .parse(hclFiles, projectDir, parseCtx)
                    .toList()
            }
            pendingErrors.clear()
            parsed.forEach { allSources.add(it) }
        }

        filesByExt[".proto"]?.let { files ->
            logger.info("Parsing ${files.size} Protobuf file(s)")
            val parsed = parseAndRecord("ProtoParser", files, projectDir, parseFailures) {
                ProtoParser.builder().build()
                    .parse(files, projectDir, parseCtx)
                    .toList()
            }
            pendingErrors.clear()
            parsed.forEach { allSources.add(it) }
        }

        val dockerFiles = listOfNotNull(
            filesByExt[".dockerfile"],
            filesByExt[".containerfile"]
        ).flatten()
        if (dockerFiles.isNotEmpty()) {
            logger.info("Parsing ${dockerFiles.size} Dockerfile(s)")
            val parsed = parseAndRecord("DockerParser", dockerFiles, projectDir, parseFailures) {
                DockerParser.builder().build()
                    .parse(dockerFiles, projectDir, parseCtx)
                    .toList()
            }
            pendingErrors.clear()
            parsed.forEach { allSources.add(it) }
        }

        logger.info("LST build complete: ${allSources.size} SourceFile(s)")

        // ── Attach provenance markers to every source file ────────────────────
        val withMarkers = allSources.map { sf ->
            var markers = sf.markers
            buildEnv?.let { markers = markers.addIfAbsent(it) }
            gitProvenance?.let { markers = markers.addIfAbsent(it) }
            markers = markers.addIfAbsent(osProvenance)
            buildToolMarker?.let { markers = markers.addIfAbsent(it) }
            if (markers === sf.markers) sf else sf.withMarkers(markers)
        }

        val resolvedJarCount = classpath.count { it.toString().endsWith(".jar") }
        val diagnostics = ExecutionDiagnostics(
            stageUsed = resolutionResult.stageUsed,
            resolvedJarCount = resolvedJarCount,
            parseFailures = parseFailures.toList()
        )
        return LstBuildResult(withMarkers, diagnostics)
    }

    /**
     * Parse `pom.xml` files with [MavenParser], falling back to [XmlParser] when
     * [MavenParser] throws an `IllegalArgumentException` whose cause is a
     * [java.net.URISyntaxException] (the failure mode `MavenPomDownloader` produces
     * via `URI.create(...)` when a coordinate or repository URL contains an illegal
     * URI character).
     *
     * Strategy:
     * 1. Try the batch with [MavenParser] — all poms together so parent/module
     *    relationships resolve normally.
     * 2. On URI failure, retry each pom individually so a single bad pom does not
     *    poison its siblings.
     * 3. Any pom that still trips [MavenParser] is reparsed with [XmlParser] so the
     *    file still shows up in the LST (without `MavenResolutionResult` markers) and
     *    recorded in [failures].
     *
     * Exceptions without a [java.net.URISyntaxException] in their cause chain are
     * rethrown — the fallback is intentionally narrow so unrelated MavenParser
     * regressions surface rather than being silently downgraded to XmlParser.
     */
    private fun parsePomFilesWithFallback(
        pomFiles: List<Path>,
        projectDir: Path,
        parseCtx: ExecutionContext,
        failures: MutableList<ParseFailure>
    ): List<SourceFile> = try {
        val parsed = buildMavenParser().parse(pomFiles, projectDir, parseCtx).toList()
        // Scan the batch output for ParseError SourceFiles and silently dropped poms —
        // MavenParser may signal per-file trouble without throwing.
        recordParseFailures("MavenParser", pomFiles, parsed, projectDir, failures)
        parsed
    } catch (e: Throwable) {
        if (!isUriFailure(e)) throw e
        logger.warn(
            "MavenParser failed on the pom batch (${e.message}); " +
                "retrying each pom.xml individually"
        )
        pomFiles.flatMap { pom ->
            try {
                val parsedOne =
                    buildMavenParser().parse(listOf(pom), projectDir, parseCtx).toList()
                // Per-pom retry can still produce ParseError / drops without throwing.
                recordParseFailures(
                    "MavenParser",
                    listOf(pom),
                    parsedOne,
                    projectDir,
                    failures
                )
                parsedOne
            } catch (single: Throwable) {
                if (!isUriFailure(single)) throw single
                val rel = projectDir.relativize(pom).normalize().toString()
                logger.warn(
                    "Failed to parse $rel with MavenParser (${single.message}); " +
                        "falling back to XmlParser"
                )
                failures += ParseFailure(
                    path = rel,
                    reason = single.message ?: single.javaClass.simpleName,
                    parser = "MavenParser"
                )
                parseAndRecord("XmlParser", listOf(pom), projectDir, failures) {
                    buildXmlParser().parse(listOf(pom), projectDir, parseCtx).toList()
                }
            }
        }
    }

    /**
     * True when [t] or any cause in its chain is a [java.net.URISyntaxException].
     *
     * `MavenPomDownloader` calls `URI.create(...)`, which wraps `URISyntaxException`
     * inside `IllegalArgumentException`. Matching on the `URISyntaxException` cause
     * (rather than any `IllegalArgumentException`) keeps the fallback narrow so
     * unrelated MavenParser bugs surface instead of being silently downgraded.
     */
    private fun isUriFailure(t: Throwable): Boolean =
        generateSequence(t) { it.cause }.any { it is java.net.URISyntaxException }

    // ─── Overrideable parser factories (for testing fallback paths) ──────────

    /** Creates the [JavaParser] used for `.java` files. Overrideable in tests. */
    protected open fun buildJavaParser(classpath: List<Path>, typeCache: JavaTypeCache): Parser =
        JavaParser
            .fromJavaVersion()
            .classpath(classpath)
            .typeCache(typeCache)
            .build()

    /** Creates the [XmlParser] used for non-pom `.xml` files and the pom fallback. Overrideable in tests. */
    protected open fun buildXmlParser(): Parser = XmlParser()

    /** Creates the [GradleParser] used for `.gradle.kts` files. Overrideable in tests. */
    protected open fun buildGradleKtsParser(
        classpath: List<Path>,
        gradleDslClasspath: List<Path>,
        typeCache: JavaTypeCache
    ): GradleParser = GradleParser.builder()
        .kotlinParser(
            KotlinParser.builder()
                .classpath(classpath + gradleDslClasspath)
                .typeCache(typeCache)
        )
        .buildscriptClasspath(gradleDslClasspath)
        .build()

    /** Creates the [GradleParser] used for `.gradle` (Groovy DSL) files. Overrideable in tests. */
    protected open fun buildGradleGroovyParser(
        classpath: List<Path>,
        gradleDslClasspath: List<Path>,
        typeCache: JavaTypeCache
    ): GradleParser = GradleParser.builder()
        .groovyParser(
            GroovyParser.builder()
                .classpath(classpath + gradleDslClasspath)
                .typeCache(typeCache)
        )
        .buildscriptClasspath(gradleDslClasspath)
        .build()

    /** Creates the [LocalRepositoryStage] used for Stage 4. Overrideable in tests. */
    protected open fun createLocalRepositoryStage(projectDir: Path): LocalRepositoryStage =
        LocalRepositoryStage(projectDir, logger)

    /** Creates the [MavenParser] used for `pom.xml` files. Overrideable in tests. */
    protected open fun buildMavenParser(): MavenParser = MavenParser.builder().build()

    // ─── Classpath resolution (4 stages) ─────────────────────────────────────

    /**
     * Directories where the project's own compiled classes might live, including subprojects.
     * Added to the classpath so that intra-project type references resolve correctly.
     *
     * Scans [projectDir] recursively up to depth 10, pruning hidden subtrees (e.g. `.git`),
     * so compiled output from nested Gradle subprojects (e.g. `core/build/classes/kotlin/main`)
     * and Maven submodules (e.g. `module-a/target/classes`) is included.
     */
    internal fun projectClassDirs(projectDir: Path): List<Path> {
        val classDirSuffixes = listOf(
            "target/classes",
            "target/test-classes",
            "build/classes/java/main",
            "build/classes/java/test",
            "build/classes/kotlin/main",
            "build/classes/kotlin/test"
        )
        val discovered = linkedSetOf<Path>()

        fun isHiddenRelativePath(path: Path): Boolean = path.any { segment ->
            segment.toString().startsWith(".")
        }

        fun maybeAdd(dir: Path) {
            if (!Files.isDirectory(dir)) return
            val relative = projectDir.relativize(dir)
            if (isHiddenRelativePath(relative)) return
            discovered.add(dir)
        }

        // Fast-path: check root project candidates directly.
        classDirSuffixes.forEach { suffix -> maybeAdd(projectDir.resolve(suffix)) }

        // Walk nested subprojects to pick up outputs like services/api/build/classes/java/main.
        // Use walkFileTree so hidden directories are pruned early (e.g., .git subtree).
        try {
            Files.walkFileTree(
                projectDir,
                EnumSet.noneOf(FileVisitOption::class.java),
                10,
                object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(
                        dir: Path,
                        attrs: BasicFileAttributes
                    ): FileVisitResult {
                        val relative = projectDir.relativize(dir)
                        if (isHiddenRelativePath(relative)) return FileVisitResult.SKIP_SUBTREE
                        val relativeText = relative.toString().replace('\\', '/')
                        if (classDirSuffixes.any { suffix -> relativeText.endsWith(suffix) }) {
                            discovered.add(dir)
                        }
                        return FileVisitResult.CONTINUE
                    }
                }
            )
        } catch (_: Exception) {
            // Ignore errors while walking subdirectories.
        }

        return discovered.toList()
    }

    private fun resolveClasspath(
        projectDir: Path,
        parseFailures: MutableList<ParseFailure>
    ): ClasspathResolutionResult {
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
            val gradleData = depResolutionStage.collectGradleProjectData(projectDir)
            if (gradleData == null &&
                discoverBuildUnits(projectDir, logger = logger).any {
                    it.tool == BuildToolKind.Gradle
                }
            ) {
                logger.warn(
                    "GradleProject markers could not be built — " +
                        "rewrite-gradle recipes may not apply. Run with --info for details."
                )
            }
            return ClasspathResolutionResult(
                classpath = stage1 + classDirs,
                gradleProjectData = gradleData,
                stageUsed = UsedExecutionStage.BUILD_TOOL
            )
        }

        logger.warn(
            "Stage 1 (build tool) failed: no classpath extracted, falling through to Stage 2"
        )

        logger.info("Stage 2: resolving dependencies via Maven Resolver")
        val stage2Result = try {
            depResolutionStage.resolveClasspath(projectDir, parseFailures)
        } catch (e: Exception) {
            logger.warn("Stage 2 threw an exception: ${e.message}")
            ClasspathResolutionResult(emptyList())
        }
        // Preserve Gradle project data from Stage 2 even when the classpath is empty, so
        // GradleProject markers can still be attached if Stage 3/4 resolves the JARs.
        val stage2GradleData = stage2Result.gradleProjectData

        if (stage2Result.classpath.isNotEmpty()) {
            val classDirs = projectClassDirs(projectDir)
            if (classDirs.isNotEmpty()) {
                logger.info("Appending ${classDirs.size} project class dir(s) to classpath")
            }
            logger.info("Stage 2 succeeded: ${stage2Result.classpath.size} JAR(s)")
            return ClasspathResolutionResult(
                classpath = stage2Result.classpath + classDirs,
                gradleProjectData = stage2GradleData,
                stageUsed = UsedExecutionStage.DEPENDENCY_RESOLUTION
            )
        }

        logger.warn(
            "Stage 2 (dependency resolution) failed: no JARs resolved, falling through to Stage 3"
        )

        logger.info("Stage 3: resolving via static build file parse + POM traversal")
        val stage3 = try {
            buildFileParseStage.resolveClasspath(projectDir, parseFailures)
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
            return ClasspathResolutionResult(
                classpath = stage3 + classDirs,
                gradleProjectData = stage2GradleData,
                stageUsed = UsedExecutionStage.DIRECT_PARSE
            )
        }

        logger.warn("Stage 3 failed — falling through to Stage 4")

        logger.info("Stage 4: scanning local Maven/Gradle caches")
        val localRepositoryStage = createLocalRepositoryStage(projectDir)
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
        return ClasspathResolutionResult(
            classpath = stage4 + classDirs,
            gradleProjectData = stage2GradleData,
            stageUsed = if (stage4.isEmpty()) null else UsedExecutionStage.LOCAL_REPOSITORY
        )
    }

    /**
     * Extract declared dependency coordinates using the same static descriptor discovery as
     * Stage 3, without triggering Maven Resolver downloads. Returns `groupId:artifactId:version`
     * strings suitable for [LocalRepositoryStage.findAvailableJars].
     */
    internal fun gatherDeclaredCoordinates(projectDir: Path): List<String> = try {
        buildFileParseStage.gatherAllCoordinates(projectDir)
    } catch (_: Exception) {
        emptyList()
    }

    // ─── Source set inference ─────────────────────────────────────────────────

    private fun isTestPath(absPath: Path): Boolean = absPath.toString().contains(
        "${java.io.File.separatorChar}test${java.io.File.separatorChar}"
    )

    // ─── Parse-failure collection (shared across every parser site) ──────────

    /**
     * Run [parse] for [parserName] and record any failures it produces into [failures]:
     *
     * - **Thrown exceptions** — caught here so the LST build never aborts on a single
     *   broken parser. One [ParseFailure] is recorded for every file in [inputFiles],
     *   each carrying the exception message; the function returns an empty list.
     * - **[ParseError] SourceFiles** — OpenRewrite parsers signal per-file failure by
     *   returning a [ParseError] in their output. The error stays in the returned list
     *   so callers can still see (and inspect) it, but a [ParseFailure] is recorded
     *   pointing at the same path.
     * - **Silently dropped files** — any [inputFiles] entry that does not appear in the
     *   parser output is recorded as a [ParseFailure] with a `silently dropped` reason.
     */
    private fun parseAndRecord(
        parserName: String,
        inputFiles: List<Path>,
        projectDir: Path,
        failures: MutableList<ParseFailure>,
        parse: () -> List<SourceFile>
    ): List<SourceFile> {
        // Catch [Exception] only — fatal [Error]s (OOM, StackOverflow, …) signal an
        // invalid JVM state and must propagate so the run fails fast rather than
        // emitting misleading partial results.
        val parsed = try {
            parse()
        } catch (e: Exception) {
            recordBatchFailure(parserName, inputFiles, projectDir, failures, e)
            return emptyList()
        }
        recordParseFailures(parserName, inputFiles, parsed, projectDir, failures)
        return parsed
    }

    /**
     * Inspect a successful parser run for [ParseError] SourceFiles and silently dropped
     * input files. Records a [ParseFailure] for each. Used directly by the Gradle DSL
     * paths so they can still trigger their Kotlin/Groovy fallback when the parser
     * itself throws.
     */
    private fun recordParseFailures(
        parserName: String,
        inputFiles: List<Path>,
        parsedFiles: List<SourceFile>,
        projectDir: Path,
        failures: MutableList<ParseFailure>
    ) {
        parsedFiles.filterIsInstance<ParseError>().forEach { pe ->
            val message = pe.markers
                .findFirst(ParseExceptionResult::class.java)
                .map { it.message?.takeIf { m -> m.isNotBlank() } ?: it.exceptionType }
                .orElse("parse error")
            val rel = pe.sourcePath.normalize().toString()
            failures += ParseFailure(path = rel, reason = message, parser = parserName)
            logger.warn("$parserName produced a ParseError for $rel: $message")
        }

        val parsedPaths = parsedFiles.map { it.sourcePath.normalize() }.toSet()
        inputFiles.forEach { file ->
            val rel = projectDir.relativize(file).normalize()
            if (rel !in parsedPaths) {
                failures += ParseFailure(
                    path = rel.toString(),
                    reason = "silently dropped by $parserName",
                    parser = parserName
                )
                logger.warn("$parserName silently dropped $rel")
            }
        }
    }

    /**
     * Record one [ParseFailure] per file when an entire parser invocation throws. Used
     * directly by the Gradle DSL paths before they trigger their Kotlin/Groovy fallback.
     */
    private fun recordBatchFailure(
        parserName: String,
        inputFiles: List<Path>,
        projectDir: Path,
        failures: MutableList<ParseFailure>,
        cause: Throwable
    ) {
        val reason = cause.message?.takeIf { it.isNotBlank() } ?: cause.javaClass.simpleName
        logger.warn(
            "$parserName threw on a batch of ${inputFiles.size} file(s): $reason"
        )
        logger.info("$parserName failure details:\n${cause.stackTraceToString()}")
        inputFiles.forEach { file ->
            val rel = projectDir.relativize(file).normalize().toString()
            failures += ParseFailure(path = rel, reason = reason, parser = parserName)
        }
    }
}
