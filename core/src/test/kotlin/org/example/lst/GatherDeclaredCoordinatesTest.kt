package org.example.lst

import org.example.config.ToolConfig
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [LstBuilder.gatherDeclaredCoordinates].
 *
 * Verifies that Stage 3 receives proper Maven coordinate strings (groupId:artifactId:version)
 * and NOT file-system paths (which would cause [DirectParseStage.parseCoord] to fail).
 */
class GatherDeclaredCoordinatesTest {

    @TempDir
    lateinit var projectDir: Path

    private val failingBuildTool = object : BuildToolStage() {
        override fun extractClasspath(projectDir: Path): List<Path>? = null
    }

    private fun lstBuilder(depStage: DependencyResolutionStage): LstBuilder = LstBuilder(
        cacheDir = projectDir.resolve("cache"),
        toolConfig = ToolConfig(),
        buildToolStage = failingBuildTool,
        depResolutionStage = depStage,
    )

    @Test
    fun `returns Maven coordinate strings for Maven project`() {
        projectDir.resolve("pom.xml").writeText(
            """
            <project>
              <dependencies>
                <dependency>
                  <groupId>org.apache.commons</groupId>
                  <artifactId>commons-lang3</artifactId>
                  <version>3.12.0</version>
                </dependency>
                <dependency>
                  <groupId>com.google.guava</groupId>
                  <artifactId>guava</artifactId>
                  <version>33.0.0-jre</version>
                </dependency>
              </dependencies>
            </project>
            """.trimIndent()
        )

        val noOpDepStage = object : DependencyResolutionStage(
            cacheDir = projectDir.resolve("cache"),
            extraRepositories = emptyList(),
        ) {
            override fun resolveClasspath(projectDir: Path): List<Path> = emptyList()
        }

        val coords = lstBuilder(noOpDepStage).gatherDeclaredCoordinates(projectDir)

        assertTrue(coords.isNotEmpty(), "Should return coordinates for project with dependencies")
        coords.forEach { coord ->
            val parts = coord.split(":")
            assertEquals(3, parts.size, "Coordinate must be groupId:artifactId:version, got: $coord")
            assertFalse(coord.startsWith("/"), "Coordinate must not be a file path, got: $coord")
            assertFalse(coord.endsWith(".jar"), "Coordinate must not end with .jar, got: $coord")
            assertTrue(parts[0].isNotBlank(), "groupId must not be blank in: $coord")
            assertTrue(parts[1].isNotBlank(), "artifactId must not be blank in: $coord")
            assertTrue(parts[2].isNotBlank(), "version must not be blank in: $coord")
        }
    }

    @Test
    fun `returns Maven coordinate strings for Gradle project`() {
        projectDir.resolve("build.gradle.kts").writeText(
            """
            dependencies {
                implementation("org.apache.commons:commons-lang3:3.12.0")
                api("com.fasterxml.jackson.core:jackson-databind:2.18.2")
            }
            """.trimIndent()
        )

        val noOpDepStage = object : DependencyResolutionStage(
            cacheDir = projectDir.resolve("cache"),
            extraRepositories = emptyList(),
        ) {
            override fun resolveClasspath(projectDir: Path): List<Path> = emptyList()
        }

        val coords = lstBuilder(noOpDepStage).gatherDeclaredCoordinates(projectDir)

        assertTrue(coords.isNotEmpty(), "Should return coordinates for Gradle project with dependencies")
        coords.forEach { coord ->
            val parts = coord.split(":")
            assertEquals(3, parts.size, "Coordinate must be groupId:artifactId:version, got: $coord")
            assertFalse(coord.startsWith("/"), "Coordinate must not be a file path, got: $coord")
            assertFalse(coord.endsWith(".jar"), "Coordinate must not end with .jar, got: $coord")
        }
    }

    @Test
    fun `returns empty list when no build file exists`() {
        val noOpDepStage = object : DependencyResolutionStage(
            cacheDir = projectDir.resolve("cache"),
            extraRepositories = emptyList(),
        ) {
            override fun resolveClasspath(projectDir: Path): List<Path> = emptyList()
        }

        val coords = lstBuilder(noOpDepStage).gatherDeclaredCoordinates(projectDir)
        assertEquals(0, coords.size, "Should return empty list when no build file present")
    }

    @Test
    fun `returns empty list when depResolutionStage parseMavenDependencies throws`() {
        projectDir.resolve("pom.xml").writeText("<project><invalid/></project>")

        val throwingDepStage = object : DependencyResolutionStage(
            cacheDir = projectDir.resolve("cache"),
            extraRepositories = emptyList(),
        ) {
            override fun resolveClasspath(projectDir: Path): List<Path> =
                throw RuntimeException("Simulated failure")
        }

        // gatherDeclaredCoordinates must not propagate the exception
        val coords = lstBuilder(throwingDepStage).gatherDeclaredCoordinates(projectDir)
        assertEquals(0, coords.size, "Should return empty on exception, not propagate it")
    }

    @Test
    fun `coordinates do not contain file-path separators`() {
        projectDir.resolve("pom.xml").writeText(
            """
            <project>
              <dependencies>
                <dependency>
                  <groupId>junit</groupId>
                  <artifactId>junit</artifactId>
                  <version>4.13.2</version>
                </dependency>
              </dependencies>
            </project>
            """.trimIndent()
        )

        val noOpDepStage = object : DependencyResolutionStage(
            cacheDir = projectDir.resolve("cache"),
            extraRepositories = emptyList(),
        ) {
            override fun resolveClasspath(projectDir: Path): List<Path> = emptyList()
        }

        val coords = lstBuilder(noOpDepStage).gatherDeclaredCoordinates(projectDir)
        coords.forEach { coord ->
            assertFalse(
                coord.contains("/") || coord.contains("\\"),
                "Coordinate '$coord' must not contain file-path separators"
            )
        }
    }
}
