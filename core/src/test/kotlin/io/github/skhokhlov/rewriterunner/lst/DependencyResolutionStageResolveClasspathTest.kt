package io.github.skhokhlov.rewriterunner.lst

import io.github.skhokhlov.rewriterunner.AetherContext
import io.github.skhokhlov.rewriterunner.NoOpRunnerLogger
import io.github.skhokhlov.rewriterunner.config.RepositoryConfig
import io.github.skhokhlov.rewriterunner.lst.utils.ClasspathResolutionResult
import io.github.skhokhlov.rewriterunner.lst.utils.StaticBuildFileParser
import io.kotest.core.spec.style.FunSpec
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
                    AetherContext.build(cacheDir.resolve("repository"), logger = NoOpRunnerLogger),
                    NoOpRunnerLogger
                ) {
                    override fun resolveClasspath(projectDir: Path): ClasspathResolutionResult =
                        super.resolveClasspath(projectDir)
                }
            // Empty directory: no pom.xml, no build.gradle
            val result = stage.resolveClasspath(projectDir)
            assertTrue(
                result.classpath.isEmpty(),
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

            var mavenSubprocessCalled = false
            val stage =
                object : DependencyResolutionStage(
                    AetherContext.build(cacheDir.resolve("repository"), logger = NoOpRunnerLogger),
                    NoOpRunnerLogger
                ) {
                    override fun runMavenDependencyTreeOutput(projectDir: Path): String? {
                        mavenSubprocessCalled = true
                        return null
                    }
                }
            stage.resolveClasspath(projectDir)
            assertTrue(
                mavenSubprocessCalled,
                "Maven subprocess should be invoked for pom.xml project"
            )
        }

        test("resolveClasspath routes to Gradle when build_gradle_kts present") {
            projectDir.resolve("build.gradle.kts").writeText(
                """
                plugins { kotlin("jvm") version "2.3.0" }
                """.trimIndent()
            )

            var gradleCalled = false
            val stage =
                object : DependencyResolutionStage(
                    AetherContext.build(cacheDir.resolve("repository"), logger = NoOpRunnerLogger),
                    NoOpRunnerLogger
                ) {
                    override fun runGradleDependenciesRawOutput(projectDir: Path): String? {
                        gradleCalled = true
                        return null
                    }
                }
            stage.resolveClasspath(projectDir)
            assertTrue(gradleCalled, "build.gradle.kts should trigger the Gradle subprocess")
        }

        // ─── StaticBuildFileParser: parseMavenDependencies edge cases ────────────

        test("parseMavenDependencies includes provided-scoped dependencies") {
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
            val coords = StaticBuildFileParser(NoOpRunnerLogger).parseMavenDependencies(projectDir)
            assertTrue(
                coords.any { it.contains("servlet-api") },
                "provided scope must be included for compile-time type resolution"
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
            val coords = StaticBuildFileParser(NoOpRunnerLogger).parseMavenDependencies(projectDir)
            assertTrue(coords.isEmpty(), "system scope should be excluded")
        }

        // ─── StaticBuildFileParser: parseGradleDependenciesStatically edge cases ─

        test("parseGradleDependenciesStatically handles three-arg Kotlin DSL form") {
            projectDir.resolve("build.gradle.kts").writeText(
                """
                dependencies {
                    implementation("org.springframework", "spring-core", "6.1.0")
                    api("com.fasterxml.jackson.core", "jackson-databind", "2.16.0")
                }
                """.trimIndent()
            )
            val coords =
                StaticBuildFileParser(NoOpRunnerLogger).parseGradleDependenciesStatically(
                    projectDir
                )
            assertTrue(coords.contains("org.springframework:spring-core:6.1.0"))
            assertTrue(coords.contains("com.fasterxml.jackson.core:jackson-databind:2.16.0"))
        }

        test("parseGradleDependenciesStatically routes to build_gradle when kts absent") {
            projectDir.resolve("build.gradle").writeText(
                """
                dependencies {
                    implementation 'org.apache.commons:commons-lang3:3.12.0'
                }
                """.trimIndent()
            )
            val coords =
                StaticBuildFileParser(NoOpRunnerLogger).parseGradleDependenciesStatically(
                    projectDir
                )
            assertTrue(coords.contains("org.apache.commons:commons-lang3:3.12.0"))
        }

        // ─── resolveClasspath POM-skip routing ───────────────────────────────────

        test("resolveClasspath calls resolveArtifactsDirectly when gradle task returns coords") {
            projectDir.resolve("build.gradle.kts").writeText("plugins { kotlin(\"jvm\") }")

            var directCalled = false
            val stage =
                object : DependencyResolutionStage(
                    AetherContext.build(cacheDir.resolve("repository"), logger = NoOpRunnerLogger),
                    NoOpRunnerLogger
                ) {
                    override fun runGradleDependenciesRawOutput(projectDir: Path) =
                        "compileClasspath - Compile classpath for source set 'main'.\n+--- org.apache.commons:commons-lang3:3.12.0\n"

                    override fun resolveArtifactsDirectly(coordinates: List<String>): List<Path> {
                        directCalled = true
                        return emptyList()
                    }
                }
            stage.resolveClasspath(projectDir)
            assertTrue(
                directCalled,
                "resolveArtifactsDirectly should be called for gradle task output"
            )
        }

        test(
            "gradle task returns null → returns empty (no static fallback)"
        ) {
            projectDir.resolve("build.gradle.kts").writeText(
                """
                dependencies {
                    implementation("org.apache.commons:commons-lang3:3.12.0")
                }
                """.trimIndent()
            )

            var directCalled = false
            val stage =
                object : DependencyResolutionStage(
                    AetherContext.build(cacheDir.resolve("repository"), logger = NoOpRunnerLogger),
                    NoOpRunnerLogger
                ) {
                    override fun runGradleDependenciesRawOutput(projectDir: Path): String? = null

                    override fun resolveArtifactsDirectly(coordinates: List<String>): List<Path> {
                        directCalled = true
                        return emptyList()
                    }
                }
            val result = stage.resolveClasspath(projectDir)
            assertFalse(
                directCalled,
                "resolveArtifactsDirectly should NOT be called — no static fallback in Stage 2"
            )
            assertTrue(
                result.classpath.isEmpty(),
                "Stage 2 should return empty when subprocess fails"
            )
        }

        test(
            "Maven falls through to empty when dependency:tree fails"
        ) {
            projectDir.resolve("pom.xml").writeText(
                """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>test</artifactId>
                    <version>1.0</version>
                    <dependencies>
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
                object : DependencyResolutionStage(
                    AetherContext.build(cacheDir.resolve("repository"), logger = NoOpRunnerLogger),
                    NoOpRunnerLogger
                ) {
                    override fun runMavenDependencyTreeOutput(projectDir: Path): String? = null
                }
            val result = stage.resolveClasspath(projectDir)
            assertTrue(
                result.classpath.isEmpty(),
                "Stage 2 should return empty when dependency:tree subprocess fails"
            )
        }

        test("resolveClasspath runs Maven dependency:tree when pom.xml present") {
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

            var directCalled = false
            val stage =
                object : DependencyResolutionStage(
                    AetherContext.build(cacheDir.resolve("repository"), logger = NoOpRunnerLogger),
                    NoOpRunnerLogger
                ) {
                    override fun runMavenDependencyTreeOutput(projectDir: Path): String =
                        "[INFO] +- org.apache.commons:commons-lang3:jar:3.12.0:compile\n"

                    override fun resolveArtifactsDirectly(coordinates: List<String>): List<Path> {
                        directCalled = true
                        return emptyList()
                    }
                }
            stage.resolveClasspath(projectDir)
            assertTrue(
                directCalled,
                "resolveArtifactsDirectly should be called with parsed Maven coords"
            )
        }

        test(
            "resolveClasspath runs both Maven and Gradle subprocess when both build files present"
        ) {
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
            projectDir.resolve("build.gradle").writeText("// mixed project")

            var mavenCalled = false
            var gradleCalled = false
            val stage =
                object : DependencyResolutionStage(
                    AetherContext.build(cacheDir.resolve("repository"), logger = NoOpRunnerLogger),
                    NoOpRunnerLogger
                ) {
                    override fun runMavenDependencyTreeOutput(projectDir: Path): String? {
                        mavenCalled = true
                        return null
                    }

                    override fun runGradleDependenciesRawOutput(projectDir: Path): String? {
                        gradleCalled = true
                        return null
                    }
                }
            stage.resolveClasspath(projectDir)
            assertTrue(mavenCalled, "Maven subprocess should be attempted for mixed project")
            assertTrue(gradleCalled, "Gradle subprocess should be attempted for mixed project")
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
                    AetherContext.build(
                        cacheDir.resolve("repository"),
                        extraRepositories = repos,
                        logger = NoOpRunnerLogger
                    ),
                    NoOpRunnerLogger
                )
            // Accessing the stage without an actual resolve call; we can call resolveClasspath
            // on an empty dir to exercise the lazy initializers (repos are built on first use)
            val result = stage.resolveClasspath(projectDir)
            assertTrue(result.classpath.isEmpty(), "Empty project → empty classpath")
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
                    AetherContext.build(
                        cacheDir.resolve("repository"),
                        extraRepositories = repos,
                        logger = NoOpRunnerLogger
                    ),
                    NoOpRunnerLogger
                )
            val result = stage.resolveClasspath(projectDir)
            assertTrue(result.classpath.isEmpty())
        }
    })
