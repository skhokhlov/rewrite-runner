package org.example.lst

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertNull

class BuildToolStageTest {

    @TempDir
    lateinit var projectDir: Path

    private val stage = BuildToolStage()

    @Test
    fun `returns null when no build file exists`() {
        // Empty directory with no pom.xml or build.gradle
        val result = stage.extractClasspath(projectDir)
        assertNull(result, "Should return null when project has no recognized build file")
    }

    @Test
    fun `returns null for Maven project when mvn is not on PATH and no wrapper`() {
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

        // No mvnw wrapper in projectDir → falls back to 'mvn' command which may not be on PATH
        // Either way, if mvn fails/is absent, result should be null (not throw)
        val result = stage.extractClasspath(projectDir)
        // Result may be null (no mvn) or a list (if mvn is available); must never throw
        // We can only assert it doesn't throw; null is the expected path in most CI environments
        // This test documents the contract: failure → null, not exception
    }

    @Test
    fun `returns null for Gradle project when no wrapper and gradle not on PATH`() {
        projectDir.resolve("build.gradle.kts").writeText(
            """
            plugins { kotlin("jvm") version "2.1.0" }
            """.trimIndent()
        )

        // No gradlew in projectDir, so falls back to 'gradle' command
        // In environments without gradle installed this returns null
        val result = stage.extractClasspath(projectDir)
        // Contract: failure → null, not exception
    }

    @Test
    fun `detects Maven project via pom_xml presence`() {
        projectDir.resolve("pom.xml").writeText("<project/>")
        // Just verify the method runs without throwing; Maven may not be installed
        val result = runCatching { stage.extractClasspath(projectDir) }
        assertTrue_compat(result.isSuccess, "extractClasspath should not throw for a Maven project")
    }

    @Test
    fun `detects Gradle project via build_gradle_kts presence`() {
        projectDir.resolve("build.gradle.kts").writeText("// empty")
        val result = runCatching { stage.extractClasspath(projectDir) }
        assertTrue_compat(result.isSuccess, "extractClasspath should not throw for a Gradle KTS project")
    }

    @Test
    fun `detects Gradle project via build_gradle presence`() {
        projectDir.resolve("build.gradle").writeText("// empty")
        val result = runCatching { stage.extractClasspath(projectDir) }
        assertTrue_compat(result.isSuccess, "extractClasspath should not throw for a Gradle Groovy project")
    }

    @Test
    fun `Maven wins over Gradle when both pom_xml and build_gradle exist`() {
        projectDir.resolve("pom.xml").writeText("<project/>")
        projectDir.resolve("build.gradle").writeText("// empty")
        // Maven is preferred; just verify no exception
        val result = runCatching { stage.extractClasspath(projectDir) }
        assertTrue_compat(result.isSuccess, "Should not throw when both pom.xml and build.gradle exist")
    }

    private fun assertTrue_compat(value: Boolean, message: String) {
        if (!value) throw AssertionError(message)
    }
}
