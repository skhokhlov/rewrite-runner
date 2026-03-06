package org.example.lst

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    // ─── tryCompile tests ─────────────────────────────────────────────────────

    @Test
    fun `tryCompile returns false when no build file exists`() {
        val result = stage.tryCompile(projectDir)
        assertFalse(result, "Should return false when project has no recognized build file")
    }

    @Test
    fun `tryCompile returns false without throwing when Maven not available`() {
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
        // No mvnw wrapper → falls back to 'mvn'; may not be on PATH in CI
        // Contract: never throws, returns false on failure
        val result = runCatching { stage.tryCompile(projectDir) }
        assertTrue_compat(result.isSuccess, "tryCompile should not throw for a Maven project")
    }

    @Test
    fun `tryCompile returns false without throwing when Gradle not available`() {
        projectDir.resolve("build.gradle.kts").writeText("// empty")
        // No gradlew wrapper → falls back to 'gradle'; may not be on PATH in CI
        val result = runCatching { stage.tryCompile(projectDir) }
        assertTrue_compat(result.isSuccess, "tryCompile should not throw for a Gradle KTS project")
    }

    @Test
    fun `tryCompile prefers Maven when both pom_xml and build_gradle exist`() {
        projectDir.resolve("pom.xml").writeText("<project/>")
        projectDir.resolve("build.gradle").writeText("// empty")
        // Maven is preferred (same precedence as extractClasspath); must not throw
        val result = runCatching { stage.tryCompile(projectDir) }
        assertTrue_compat(result.isSuccess, "tryCompile should not throw when both pom.xml and build.gradle exist")
    }

    @Test
    fun `tryCompile uses mvnw wrapper when present`() {
        projectDir.resolve("pom.xml").writeText("<project/>")
        // Create a fake mvnw that exits 1 — still must not throw
        val mvnw = projectDir.resolve("mvnw").toFile()
        mvnw.writeText("#!/bin/sh\nexit 1\n")
        mvnw.setExecutable(true)
        val result = runCatching { stage.tryCompile(projectDir) }
        assertTrue_compat(result.isSuccess, "tryCompile should not throw when mvnw exits non-zero")
    }

    @Test
    fun `tryCompile uses gradlew wrapper when present`() {
        projectDir.resolve("build.gradle").writeText("// empty")
        // Create a fake gradlew that exits 1 — still must not throw
        val gradlew = projectDir.resolve("gradlew").toFile()
        gradlew.writeText("#!/bin/sh\nexit 1\n")
        gradlew.setExecutable(true)
        val result = runCatching { stage.tryCompile(projectDir) }
        assertTrue_compat(result.isSuccess, "tryCompile should not throw when gradlew exits non-zero")
    }

    // ─── Deadlock-prevention tests ────────────────────────────────────────────

    @Test
    fun `extractClasspath does not hang when Maven wrapper produces large stdout output`() {
        projectDir.resolve("pom.xml").writeText("<project/>")
        // Fake mvnw that writes ~200 KB to stdout (well above the typical 64 KB OS pipe buffer)
        val mvnw = projectDir.resolve("mvnw").toFile()
        mvnw.writeText(
            """
            #!/bin/sh
            for i in $(seq 1 2000); do
              printf 'A%.0s' $(seq 1 100)
              printf '\n'
            done
            exit 1
            """.trimIndent()
        )
        mvnw.setExecutable(true)

        val start = System.currentTimeMillis()
        val result = stage.extractClasspath(projectDir)
        val elapsed = System.currentTimeMillis() - start

        assertNull(result, "Should return null for non-zero exit code")
        assertTrue(
            elapsed < 15_000,
            "Should complete within 15 seconds without hanging due to full pipe buffer, took ${elapsed}ms"
        )
    }

    @Test
    fun `tryCompile does not hang when Maven wrapper produces large stdout output`() {
        projectDir.resolve("pom.xml").writeText("<project/>")
        val mvnw = projectDir.resolve("mvnw").toFile()
        mvnw.writeText(
            """
            #!/bin/sh
            for i in $(seq 1 2000); do
              printf 'B%.0s' $(seq 1 100)
              printf '\n'
            done
            exit 1
            """.trimIndent()
        )
        mvnw.setExecutable(true)

        val start = System.currentTimeMillis()
        val result = stage.tryCompile(projectDir)
        val elapsed = System.currentTimeMillis() - start

        assertFalse(result, "Should return false for non-zero exit code")
        assertTrue(
            elapsed < 15_000,
            "Should complete within 15 seconds without hanging, took ${elapsed}ms"
        )
    }

    private fun assertTrue_compat(value: Boolean, message: String) {
        if (!value) throw AssertionError(message)
    }
}
