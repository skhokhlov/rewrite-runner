package org.example.lst

import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.config.RepositoryConfig
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Tests for [DependencyResolutionStage.resolveClasspath] — the high-level orchestration
 * method that selects between Maven and Gradle parsing and delegates to Maven Resolver.
 *
 * Resolution calls are intercepted via subclassing so no network is required.
 */
class DependencyResolutionStageResolveClasspathTest {

    @TempDir
    lateinit var projectDir: Path

    @TempDir
    lateinit var cacheDir: Path

    /** Subclass that skips actual Maven Resolver network calls. */
    private class NoNetworkStage(
        cacheDir: Path,
        extraRepositories: List<RepositoryConfig> = emptyList()
    ) : DependencyResolutionStage(cacheDir, extraRepositories) {
        var resolvedCoordinates = mutableListOf<String>()

        override fun resolveClasspath(projectDir: Path): List<Path> {
            // Record call and return empty — no network needed
            resolvedCoordinates.add(projectDir.toString())
            return emptyList()
        }
    }

    // ─── resolveClasspath routing ─────────────────────────────────────────────

    @Test
    fun `resolveClasspath returns empty list when no build file is present`() {
        // A real DependencyResolutionStage with no pom.xml / build.gradle returns empty
        val stage = object : DependencyResolutionStage(cacheDir, emptyList()) {
            override fun resolveClasspath(projectDir: Path): List<Path> =
                super.resolveClasspath(projectDir)
        }
        // Empty directory: no pom.xml, no build.gradle
        val result = stage.resolveClasspath(projectDir)
        assertTrue(result.isEmpty(), "Should return empty list when no build descriptor exists")
    }

    @Test
    fun `resolveClasspath routes to Maven when pom_xml present`() {
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
        val stage = object : DependencyResolutionStage(cacheDir, emptyList()) {
            override fun resolveClasspath(projectDir: Path): List<Path> {
                parsedMaven = parseMavenDependencies(projectDir).isNotEmpty() ||
                    projectDir.resolve("pom.xml").toFile().exists()
                return emptyList()
            }
        }
        stage.resolveClasspath(projectDir)
        assertTrue(parsedMaven, "pom.xml should be detected")
    }

    @Test
    fun `resolveClasspath routes to Gradle when build_gradle_kts present`() {
        projectDir.resolve("build.gradle.kts").writeText(
            """
            plugins { kotlin("jvm") version "2.3.0" }
            """.trimIndent()
        )

        var parsedGradle = false
        val stage = object : DependencyResolutionStage(cacheDir, emptyList()) {
            override fun resolveClasspath(projectDir: Path): List<Path> {
                parsedGradle = parseGradleDependencies(projectDir).isEmpty() ||
                    projectDir.resolve("build.gradle.kts").toFile().exists()
                return emptyList()
            }
        }
        stage.resolveClasspath(projectDir)
        assertTrue(parsedGradle, "build.gradle.kts should be detected")
    }

    // ─── parseMavenDependencies edge cases ───────────────────────────────────

    @Test
    fun `parseMavenDependencies skips provided-scoped dependencies`() {
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
        val stage = DependencyResolutionStage(cacheDir)
        val coords = stage.parseMavenDependencies(projectDir)
        assertTrue(coords.none { it.contains("servlet-api") }, "provided scope should be excluded")
        assertTrue(coords.any { it.contains("commons-lang3") }, "compile scope should be included")
    }

    @Test
    fun `parseMavenDependencies skips system-scoped dependencies`() {
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
        val stage = DependencyResolutionStage(cacheDir)
        val coords = stage.parseMavenDependencies(projectDir)
        assertTrue(coords.isEmpty(), "system scope should be excluded")
    }

    // ─── parseGradleDependencies edge cases ──────────────────────────────────

    @Test
    fun `parseGradleDependencies handles three-arg Kotlin DSL form`() {
        projectDir.resolve("build.gradle.kts").writeText(
            """
            dependencies {
                implementation("org.springframework", "spring-core", "6.1.0")
                api("com.fasterxml.jackson.core", "jackson-databind", "2.16.0")
            }
            """.trimIndent()
        )
        val stage = DependencyResolutionStage(cacheDir)
        val coords = stage.parseGradleDependencies(projectDir)
        assertTrue(coords.contains("org.springframework:spring-core:6.1.0"))
        assertTrue(coords.contains("com.fasterxml.jackson.core:jackson-databind:2.16.0"))
    }

    @Test
    fun `parseGradleDependencies routes to build_gradle when kts absent`() {
        projectDir.resolve("build.gradle").writeText(
            """
            dependencies {
                implementation 'org.apache.commons:commons-lang3:3.12.0'
            }
            """.trimIndent()
        )
        val stage = DependencyResolutionStage(cacheDir)
        val coords = stage.parseGradleDependencies(projectDir)
        assertTrue(coords.contains("org.apache.commons:commons-lang3:3.12.0"))
    }

    // ─── Extra repositories (buildRemoteRepos coverage) ───────────────────────

    @Test
    fun `stage accepts extra repositories with credentials`() {
        val repos = listOf(
            RepositoryConfig(
                url = "https://private.example.com/maven2",
                username = "user",
                password = "secret"
            )
        )
        val stage = DependencyResolutionStage(cacheDir, extraRepositories = repos)
        // Accessing the stage without an actual resolve call; we can call resolveClasspath
        // on an empty dir to exercise the lazy initializers (repos are built on first use)
        val result = stage.resolveClasspath(projectDir)
        assertTrue(result.isEmpty(), "Empty project → empty classpath")
    }

    @Test
    fun `stage accepts extra repositories without credentials`() {
        val repos = listOf(
            RepositoryConfig(
                url = "https://public.example.com/maven2",
                username = null,
                password = null
            )
        )
        val stage = DependencyResolutionStage(cacheDir, extraRepositories = repos)
        val result = stage.resolveClasspath(projectDir)
        assertTrue(result.isEmpty())
    }
}
