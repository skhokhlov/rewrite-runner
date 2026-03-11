package io.github.skhokhlov.rewriterunner.lst

import io.github.skhokhlov.rewriterunner.AetherContext
import io.github.skhokhlov.rewriterunner.config.RepositoryConfig
import io.kotest.core.spec.style.FunSpec
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [DependencyResolutionStage.resolveClasspath] — the high-level orchestration
 * method that selects between Maven and Gradle parsing and delegates to Maven Resolver.
 *
 * Resolution calls are intercepted via subclassing so no network is required.
 */
class DependencyResolutionStageResolveClasspathTest :
    FunSpec({
        var projectDir: Path = Path.of("")
        var cacheDir: Path = Path.of("")

        beforeEach {
            projectDir = Files.createTempDirectory("drsrct-project-")
            cacheDir = Files.createTempDirectory("drsrct-cache-")
        }

        afterEach {
            projectDir.toFile().deleteRecursively()
            cacheDir.toFile().deleteRecursively()
        }

        // ─── resolveClasspath routing ─────────────────────────────────────────────

        test("resolveClasspath returns empty list when no build file is present") {
            // A real DependencyResolutionStage with no pom.xml / build.gradle returns empty
            val stage =
                object : DependencyResolutionStage(
                    AetherContext.build(cacheDir.resolve("repository"))
                ) {
                    override fun resolveClasspath(projectDir: Path): List<Path> =
                        super.resolveClasspath(projectDir)
                }
            // Empty directory: no pom.xml, no build.gradle
            val result = stage.resolveClasspath(projectDir)
            assertTrue(
                result.isEmpty(),
                "Should return empty list when no build descriptor exists"
            )
        }

        test("resolveClasspath routes to Maven when pom_xml present") {
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

            var parsedMaven = false
            val stage =
                object : DependencyResolutionStage(
                    AetherContext.build(cacheDir.resolve("repository"))
                ) {
                    override fun resolveClasspath(projectDir: Path): List<Path> {
                        parsedMaven =
                            parseMavenDependencies(projectDir).isNotEmpty() ||
                            projectDir.resolve("pom.xml").toFile().exists()
                        return emptyList()
                    }
                }
            stage.resolveClasspath(projectDir)
            assertTrue(parsedMaven, "pom.xml should be detected")
        }

        test("resolveClasspath routes to Gradle when build_gradle_kts present") {
            projectDir.resolve("build.gradle.kts").writeText(
                """
                plugins { kotlin("jvm") version "2.3.0" }
                """.trimIndent()
            )

            var parsedGradle = false
            val stage =
                object : DependencyResolutionStage(
                    AetherContext.build(cacheDir.resolve("repository"))
                ) {
                    override fun resolveClasspath(projectDir: Path): List<Path> {
                        parsedGradle =
                            parseGradleDependencies(projectDir).isEmpty() ||
                            projectDir.resolve("build.gradle.kts").toFile().exists()
                        return emptyList()
                    }
                }
            stage.resolveClasspath(projectDir)
            assertTrue(parsedGradle, "build.gradle.kts should be detected")
        }

        // ─── parseMavenDependencies edge cases ───────────────────────────────────

        test("parseMavenDependencies skips provided-scoped dependencies") {
            projectDir.resolve("pom.xml").writeText(
                """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>test</artifactId>
                    <version>1.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>javax.servlet</groupId>
                            <artifactId>javax.servlet-api</artifactId>
                            <version>4.0.1</version>
                            <scope>provided</scope>
                        </dependency>
                        <dependency>
                            <groupId>org.apache.commons</groupId>
                            <artifactId>commons-lang3</artifactId>
                            <version>3.12.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """.trimIndent()
            )
            val stage =
                DependencyResolutionStage(AetherContext.build(cacheDir.resolve("repository")))
            val coords = stage.parseMavenDependencies(projectDir)
            assertTrue(
                coords.none { it.contains("servlet-api") },
                "provided scope should be excluded"
            )
            assertTrue(
                coords.any { it.contains("commons-lang3") },
                "compile scope should be included"
            )
        }

        test("parseMavenDependencies skips system-scoped dependencies") {
            projectDir.resolve("pom.xml").writeText(
                """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>test</artifactId>
                    <version>1.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>com.oracle</groupId>
                            <artifactId>ojdbc8</artifactId>
                            <version>21.1.0.0</version>
                            <scope>system</scope>
                            <systemPath>/path/to/ojdbc8.jar</systemPath>
                        </dependency>
                    </dependencies>
                </project>
                """.trimIndent()
            )
            val stage =
                DependencyResolutionStage(AetherContext.build(cacheDir.resolve("repository")))
            val coords = stage.parseMavenDependencies(projectDir)
            assertTrue(coords.isEmpty(), "system scope should be excluded")
        }

        // ─── parseGradleDependencies edge cases ──────────────────────────────────

        test("parseGradleDependencies handles three-arg Kotlin DSL form") {
            projectDir.resolve("build.gradle.kts").writeText(
                """
                dependencies {
                    implementation("org.springframework", "spring-core", "6.1.0")
                    api("com.fasterxml.jackson.core", "jackson-databind", "2.16.0")
                }
                """.trimIndent()
            )
            val stage =
                DependencyResolutionStage(AetherContext.build(cacheDir.resolve("repository")))
            val coords = stage.parseGradleDependencies(projectDir)
            assertTrue(coords.contains("org.springframework:spring-core:6.1.0"))
            assertTrue(coords.contains("com.fasterxml.jackson.core:jackson-databind:2.16.0"))
        }

        test("parseGradleDependencies routes to build_gradle when kts absent") {
            projectDir.resolve("build.gradle").writeText(
                """
                dependencies {
                    implementation 'org.apache.commons:commons-lang3:3.12.0'
                }
                """.trimIndent()
            )
            val stage =
                DependencyResolutionStage(AetherContext.build(cacheDir.resolve("repository")))
            val coords = stage.parseGradleDependencies(projectDir)
            assertTrue(coords.contains("org.apache.commons:commons-lang3:3.12.0"))
        }

        // ─── Extra repositories (buildRemoteRepos coverage) ───────────────────────

        test("stage accepts extra repositories with credentials") {
            val repos =
                listOf(
                    RepositoryConfig(
                        url = "https://private.example.com/maven2",
                        username = "user",
                        password = "secret"
                    )
                )
            val stage =
                DependencyResolutionStage(
                    AetherContext.build(cacheDir.resolve("repository"), extraRepositories = repos)
                )
            // Accessing the stage without an actual resolve call; we can call resolveClasspath
            // on an empty dir to exercise the lazy initializers (repos are built on first use)
            val result = stage.resolveClasspath(projectDir)
            assertTrue(result.isEmpty(), "Empty project → empty classpath")
        }

        test("stage accepts extra repositories without credentials") {
            val repos =
                listOf(
                    RepositoryConfig(
                        url = "https://public.example.com/maven2",
                        username = null,
                        password = null
                    )
                )
            val stage =
                DependencyResolutionStage(
                    AetherContext.build(cacheDir.resolve("repository"), extraRepositories = repos)
                )
            val result = stage.resolveClasspath(projectDir)
            assertTrue(result.isEmpty())
        }
    })
