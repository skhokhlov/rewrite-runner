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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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

        test("GradleProject marker uses per-project metadata for subprojects and nested paths") {
            projectDir.resolve("settings.gradle.kts").writeText(
                """
                rootProject.name = "root-app"
                include(":api", ":core:util")
                """.trimIndent()
            )
            projectDir.resolve("build.gradle.kts").writeText(
                """
                group = "com.example.root"
                version = "1.0.0"
                repositories { mavenCentral() }
                """.trimIndent()
            )
            projectDir.resolve("api").toFile().mkdirs()
            projectDir.resolve("api/build.gradle.kts").writeText(
                """
                group = "com.example.api"
                version = "2.0.0"
                repositories { maven { url = "https://repo.example.test/maven" } }
                """.trimIndent()
            )
            projectDir.resolve("core/util").toFile().mkdirs()
            projectDir.resolve("core/util/build.gradle").writeText(
                """
                group = 'com.example.util'
                version = '3.0.0'
                repositories { google() }
                """.trimIndent()
            )

            val gradleData = mapOf(
                ":" to GradleProjectData(
                    mapOf(
                        "compileClasspath" to GradleConfigData(
                            requested = listOf("com.example:root-lib:1.0.0"),
                            resolved = listOf("com.example:root-lib:1.0.0")
                        )
                    ),
                    emptyList()
                ),
                ":api" to GradleProjectData(
                    mapOf(
                        "compileClasspath" to GradleConfigData(
                            requested = listOf("com.example:api-lib:1.0.0"),
                            resolved = listOf("com.example:api-lib:1.1.0")
                        )
                    ),
                    emptyList()
                ),
                ":core:util" to GradleProjectData(
                    mapOf(
                        "compileClasspath" to GradleConfigData(
                            requested = listOf("com.example:util-lib:1.0.0"),
                            resolved = listOf("com.example:util-lib:1.0.1")
                        )
                    ),
                    emptyList()
                )
            )

            val sources = lstBuilderWithStage1Success(gradleData = gradleData).build(
                projectDir,
                includeExtensionsCli = listOf(".kts", ".gradle")
            )

            fun markerFor(path: String): GradleProject {
                val source = sources.singleOrNull { it.sourcePath.toString() == path }
                assertNotNull(source, "Expected source file $path to be parsed")
                val marker = source.markers.findFirst(GradleProject::class.java).orElse(null)
                assertNotNull(marker, "Expected GradleProject marker on $path")
                return marker
            }

            val root = markerFor("build.gradle.kts")
            assertEquals(":", root.path)
            assertEquals("root-app", root.name)
            assertEquals("com.example.root", root.group)
            assertEquals("1.0.0", root.version)
            assertTrue(
                root.mavenRepositories.any { it.uri == "https://repo.maven.apache.org/maven2" },
                "Root should use mavenCentral repository from root build file"
            )

            val api = markerFor("api/build.gradle.kts")
            assertEquals(":api", api.path)
            assertEquals("api", api.name)
            assertEquals("com.example.api", api.group)
            assertEquals("2.0.0", api.version)
            assertTrue(
                api.mavenRepositories.any { it.uri == "https://repo.example.test/maven" },
                "API should use repository declared in api/build.gradle.kts"
            )

            val util = markerFor("core/util/build.gradle")
            assertEquals(":core:util", util.path)
            assertEquals("util", util.name)
            assertEquals("com.example.util", util.group)
            assertEquals("3.0.0", util.version)
            assertTrue(
                util.mavenRepositories.any { it.uri == "https://dl.google.com/dl/android/maven2" },
                "Nested subproject should use repository declared in core/util/build.gradle"
            )
        }

        test("GradleProject marker configurations include requested and resolved dependencies") {
            projectDir.resolve("build.gradle.kts").writeText("// empty build")
            val gradleData = mapOf(
                ":" to GradleProjectData(
                    mapOf(
                        "compileClasspath" to GradleConfigData(
                            requested = listOf(
                                "org.sample:demo-lib:1.0.0"
                            ),
                            resolved = listOf(
                                "org.sample:demo-lib:1.1.0"
                            )
                        )
                    ),
                    emptyList()
                )
            )

            val sources = lstBuilderWithStage1Success(gradleData = gradleData).build(
                projectDir,
                includeExtensionsCli = listOf(".kts")
            )
            val marker = sources
                .single { it.sourcePath.toString() == "build.gradle.kts" }
                .markers.findFirst(GradleProject::class.java).orElse(null)
            assertNotNull(marker, "Expected GradleProject marker on build.gradle.kts")

            val compileClasspath = marker.nameToConfiguration["compileClasspath"]
            assertNotNull(compileClasspath, "Expected compileClasspath configuration")

            val requested = compileClasspath.requested.map {
                "${it.groupId}:${it.artifactId}:${it.version}"
            }
            val directResolved = compileClasspath.directResolved.map {
                "${it.groupId}:${it.artifactId}:${it.version}"
            }
            assertTrue(
                requested.contains("org.sample:demo-lib:1.0.0"),
                "Expected requested dependencies to be propagated from GradleProjectData"
            )
            assertTrue(
                directResolved.contains("org.sample:demo-lib:1.1.0"),
                "Expected resolved dependencies to be propagated from GradleProjectData"
            )
        }

        test(
            "GradleProject marker repositories are parsed from build file, not GradleProjectData"
        ) {
            projectDir.resolve("build.gradle.kts").writeText(
                """
                repositories {
                    mavenCentral()
                }
                """.trimIndent()
            )
            val gradleData = mapOf(
                ":" to GradleProjectData(
                    mapOf(
                        "compileClasspath" to GradleConfigData(
                            requested = emptyList(),
                            resolved = emptyList()
                        )
                    ),
                    listOf("https://repo.example.invalid/should-not-be-used")
                )
            )

            val sources = lstBuilderWithStage1Success(gradleData = gradleData).build(
                projectDir,
                includeExtensionsCli = listOf(".kts")
            )
            val marker = sources
                .single { it.sourcePath.toString() == "build.gradle.kts" }
                .markers.findFirst(GradleProject::class.java).orElse(null)
            assertNotNull(marker, "Expected GradleProject marker on build.gradle.kts")

            val repoUris = marker.mavenRepositories.map { it.uri }
            assertTrue(
                repoUris.contains("https://repo.maven.apache.org/maven2"),
                "Expected repository URLs from static build-file parsing"
            )
            assertFalse(
                repoUris.contains("https://repo.example.invalid/should-not-be-used"),
                "Should ignore repositoryUrls from GradleProjectData until that field is populated"
            )
        }
    })
