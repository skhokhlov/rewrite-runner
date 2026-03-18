package io.github.skhokhlov.rewriterunner.lst

import io.github.skhokhlov.rewriterunner.AetherContext
import io.github.skhokhlov.rewriterunner.NoOpRunnerLogger
import io.github.skhokhlov.rewriterunner.RunnerLogger
import io.github.skhokhlov.rewriterunner.config.ToolConfig
import io.github.skhokhlov.rewriterunner.lst.utils.ClasspathResolutionResult
import io.github.skhokhlov.rewriterunner.lst.utils.GradleConfigData
import io.github.skhokhlov.rewriterunner.lst.utils.GradleProjectData
import io.kotest.core.spec.style.FunSpec
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.openrewrite.gradle.GradleParser
import org.openrewrite.gradle.marker.GradleProject
import org.openrewrite.java.internal.JavaTypeCache

/**
 * Verifies that [GradleProject] markers are attached to Gradle build files in every code path:
 *
 * - **#90 fix**: GradleParser fallback paths (KotlinParser / GroovyParser) now call
 *   [io.github.skhokhlov.rewriterunner.lst.utils.MarkerFactory.addGradleProjectMarker], so the
 *   marker is not silently dropped when GradleParser throws.
 *
 * - **#78 fix**: When Stage 1 succeeds, [LstBuilder] now calls
 *   [DependencyResolutionStage.collectGradleProjectData] so that `gradleProjectData` is
 *   available for marker attachment even when Stage 2 was never run.
 */
class GradleProjectMarkerTest :
    FunSpec({
        var projectDir: Path = Path.of("")

        beforeEach { projectDir = Files.createTempDirectory("gpmt-") }

        afterEach { projectDir.toFile().deleteRecursively() }

        val toolConfig = ToolConfig(logger = NoOpRunnerLogger)

        val minimalGradleData =
            mapOf(
                ":" to
                    GradleProjectData(
                        mapOf("implementation" to GradleConfigData(emptyList(), emptyList())),
                        emptyList()
                    )
            )

        /**
         * Builds an [LstBuilder] where Stage 1 fails (returns null) and Stage 2 always returns
         * [minimalGradleData]. [throwOnKtsParser] / [throwOnGroovyParser] force the corresponding
         * GradleParser fallback paths.
         */
        fun lstBuilderWithGradleData(
            throwOnKtsParser: Boolean = false,
            throwOnGroovyParser: Boolean = false,
            logger: RunnerLogger = NoOpRunnerLogger
        ): LstBuilder {
            val failingBuildTool =
                object : ProjectBuildStage(NoOpRunnerLogger) {
                    override fun extractClasspath(projectDir: Path): List<Path>? = null
                }
            val aether =
                AetherContext.build(
                    projectDir.resolve("cache/repository"),
                    logger = NoOpRunnerLogger
                )
            val depStage =
                object : DependencyResolutionStage(aether, NoOpRunnerLogger) {
                    override fun resolveClasspath(projectDir: Path) =
                        ClasspathResolutionResult(emptyList(), minimalGradleData)
                }
            val buildFileStage =
                object : BuildFileParseStage(aether, NoOpRunnerLogger) {
                    override fun resolveClasspath(projectDir: Path): List<Path> = emptyList()
                }
            return object :
                LstBuilder(
                    logger = logger,
                    cacheDir = projectDir.resolve("cache"),
                    toolConfig = toolConfig,
                    projectBuildStage = failingBuildTool,
                    depResolutionStage = depStage,
                    buildFileParseStage = buildFileStage
                ) {
                override fun buildGradleKtsParser(
                    classpath: List<Path>,
                    gradleDslClasspath: List<Path>,
                    typeCache: JavaTypeCache
                ): GradleParser = if (throwOnKtsParser) {
                    throw RuntimeException("forced KTS parse failure for test")
                } else {
                    super.buildGradleKtsParser(classpath, gradleDslClasspath, typeCache)
                }

                override fun buildGradleGroovyParser(
                    classpath: List<Path>,
                    gradleDslClasspath: List<Path>,
                    typeCache: JavaTypeCache
                ): GradleParser = if (throwOnGroovyParser) {
                    throw RuntimeException("forced Groovy parse failure for test")
                } else {
                    super.buildGradleGroovyParser(classpath, gradleDslClasspath, typeCache)
                }
            }
        }

        /**
         * Builds an [LstBuilder] where Stage 1 succeeds (returns an empty classpath) and
         * [DependencyResolutionStage.collectGradleProjectData] returns [gradleData].
         */
        fun lstBuilderWithStage1Success(
            gradleData: Map<String, GradleProjectData>? = null,
            logger: RunnerLogger = NoOpRunnerLogger
        ): LstBuilder {
            val stage1BuildTool =
                object : ProjectBuildStage(NoOpRunnerLogger) {
                    override fun extractClasspath(projectDir: Path): List<Path> = emptyList()
                }
            val aether =
                AetherContext.build(
                    projectDir.resolve("cache/repository"),
                    logger = NoOpRunnerLogger
                )
            val depStage =
                object : DependencyResolutionStage(aether, NoOpRunnerLogger) {
                    override fun collectGradleProjectData(projectDir: Path) = gradleData
                }
            val buildFileStage =
                object : BuildFileParseStage(aether, NoOpRunnerLogger) {
                    override fun resolveClasspath(projectDir: Path): List<Path> = emptyList()
                }
            return LstBuilder(
                logger = logger,
                cacheDir = projectDir.resolve("cache"),
                toolConfig = toolConfig,
                projectBuildStage = stage1BuildTool,
                depResolutionStage = depStage,
                buildFileParseStage = buildFileStage
            )
        }

        // ─── Issue #90: fallback paths must attach GradleProject marker ───────────

        test("GradleProject marker attached to gradle.kts via KotlinParser fallback (#90)") {
            projectDir.resolve("build.gradle.kts").writeText("// empty build")
            val sources =
                lstBuilderWithGradleData(throwOnKtsParser = true).build(
                    projectDir,
                    includeExtensionsCli = listOf(".kts")
                )
            assertTrue(sources.isNotEmpty(), "Expected at least one parsed source file")
            assertTrue(
                sources.any { it.markers.findFirst(GradleProject::class.java).isPresent },
                "Expected GradleProject marker on .gradle.kts file when falling back to KotlinParser"
            )
        }

        test("GradleProject marker attached to build.gradle via GroovyParser fallback (#90)") {
            projectDir.resolve("build.gradle").writeText("// empty build")
            val sources =
                lstBuilderWithGradleData(throwOnGroovyParser = true).build(
                    projectDir,
                    includeExtensionsCli = listOf(".gradle")
                )
            assertTrue(sources.isNotEmpty(), "Expected at least one parsed source file")
            assertTrue(
                sources.any { it.markers.findFirst(GradleProject::class.java).isPresent },
                "Expected GradleProject marker on .gradle file when falling back to GroovyParser"
            )
        }

        // ─── Issue #78: Stage 1 path must attach GradleProject marker ────────────

        test(
            "GradleProject marker attached when Stage 1 succeeds and collectGradleProjectData returns data (#78)"
        ) {
            projectDir.resolve("build.gradle.kts").writeText("// empty build")
            val sources =
                lstBuilderWithStage1Success(gradleData = minimalGradleData).build(
                    projectDir,
                    includeExtensionsCli = listOf(".kts")
                )
            assertTrue(sources.isNotEmpty(), "Expected at least one parsed source file")
            assertTrue(
                sources.any { it.markers.findFirst(GradleProject::class.java).isPresent },
                "Expected GradleProject marker when Stage 1 succeeds and gradle data available"
            )
        }

        test(
            "warning emitted when Stage 1 succeeds but collectGradleProjectData returns null for Gradle project (#78)"
        ) {
            projectDir.resolve("build.gradle.kts").writeText("// empty build")
            val warnings = mutableListOf<String>()
            val capturingLogger =
                object : RunnerLogger by NoOpRunnerLogger {
                    override fun warn(message: String) {
                        warnings.add(message)
                    }
                }
            lstBuilderWithStage1Success(gradleData = null, logger = capturingLogger)
                .build(projectDir, includeExtensionsCli = listOf(".kts"))
            assertTrue(
                warnings.any { it.contains("GradleProject markers could not be built") },
                "Expected warning about missing GradleProject markers. Warnings: $warnings"
            )
        }

        test("no GradleProject warning emitted for non-Gradle project when Stage 1 succeeds") {
            projectDir.resolve("pom.xml").writeText(
                """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>test</artifactId>
                    <version>1.0</version>
                </project>
                """.trimIndent()
            )
            val warnings = mutableListOf<String>()
            val capturingLogger =
                object : RunnerLogger by NoOpRunnerLogger {
                    override fun warn(message: String) {
                        warnings.add(message)
                    }
                }
            lstBuilderWithStage1Success(gradleData = null, logger = capturingLogger)
                .build(projectDir, includeExtensionsCli = listOf(".xml"))
            assertFalse(
                warnings.any { it.contains("GradleProject markers could not be built") },
                "Should not warn about Gradle markers for Maven-only project"
            )
        }
    })
