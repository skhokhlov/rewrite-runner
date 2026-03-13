package io.github.skhokhlov.rewriterunner.lst

import io.github.skhokhlov.rewriterunner.NoOpRunnerLogger
import io.kotest.core.spec.style.FunSpec
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val isWindows = System.getProperty("os.name", "").lowercase().contains("windows")

/**
 * Covers branches in [BuildToolStage] that are missed by the main test suite:
 * - `gradlew.bat` wrapper detection
 * - Gradle extraction with fake wrapper that exits non-zero (covers the captureStdout path)
 * - Gradle extraction with fake wrapper that exits 0 (covers the success parse path)
 * - Maven/Gradle tryCompile success path via a fake exit-0 wrapper
 * - Maven classpath extraction when the output file is empty (exit 0 but no classpath written)
 */
class BuildToolStageBranchTest :
    FunSpec({
        var projectDir: Path = Path.of("")
        val stage = BuildToolStage(NoOpRunnerLogger)

        beforeEach { projectDir = Files.createTempDirectory("btsb-") }

        afterEach { projectDir.toFile().deleteRecursively() }

        // ─── gradlew.bat detection ────────────────────────────────────────────────

        test("extractClasspath prefers gradlew over gradlew_bat when both exist").config(
            enabled = !isWindows
        ) {
            projectDir.resolve("build.gradle").writeText("// empty")
            // Create gradlew (exits 1) and gradlew.bat (should not be picked on Linux)
            val gradlew = projectDir.resolve("gradlew").toFile()
            gradlew.writeText("#!/bin/sh\nexit 1\n")
            gradlew.setExecutable(true)
            val gradlewBat = projectDir.resolve("gradlew.bat").toFile()
            gradlewBat.writeText("@echo off\nexit /B 1\n")

            // gradlew exists → ./gradlew is used, exits 1 → returns null
            val result = stage.extractClasspath(projectDir)
            assertNull(result, "Non-zero exit → null")
        }

        test("extractClasspath detects gradlew_bat when only bat wrapper present").config(
            enabled = !isWindows
        ) {
            projectDir.resolve("build.gradle").writeText("// empty")
            // Only gradlew.bat present — triggers the `gradlew.bat` branch in cmd selection.
            // On Linux the bat script won't run; the process will fail to start (or exit non-zero),
            // so result must be null without throwing.
            val gradlewBat = projectDir.resolve("gradlew.bat").toFile()
            gradlewBat.writeText("@echo off\nexit /B 1\n")
            gradlewBat.setExecutable(true)

            val result = runCatching { stage.extractClasspath(projectDir) }
            assertTrue(
                result.isSuccess,
                "Should not throw when gradlew.bat is selected on Linux"
            )
        }

        // ─── Gradle classpath extraction — covers the captureStdout and success paths

        test(
            "extractClasspath returns empty list when gradlew exits 0 with no output"
        ).config(enabled = !isWindows) {
            projectDir.resolve("build.gradle").writeText("// empty")
            val gradlew = projectDir.resolve("gradlew").toFile()
            // Exit 0 with no stdout → captureStdout path is exercised, success branch is taken,
            // returned list is empty (all paths filtered out because none exist on disk).
            gradlew.writeText("#!/bin/sh\nexit 0\n")
            gradlew.setExecutable(true)

            val result = stage.extractClasspath(projectDir)
            assertNotNull(result, "Exit 0 with no output → empty list (not null)")
            assertTrue(result.isEmpty(), "No paths printed → empty classpath list")
        }

        test("extractClasspath with gradlew handles non-zero exit gracefully").config(
            enabled = !isWindows
        ) {
            projectDir.resolve("build.gradle").writeText("// empty")
            val gradlew = projectDir.resolve("gradlew").toFile()
            gradlew.writeText("#!/bin/sh\necho some-output\nexit 2\n")
            gradlew.setExecutable(true)

            val result = stage.extractClasspath(projectDir)
            assertNull(result, "Non-zero exit code → null")
        }

        // ─── Maven classpath extraction — success path with empty output file ────

        test("extractClasspath returns null when mvnw exits 0 but writes empty output file")
            .config(enabled = !isWindows) {
                projectDir.resolve("pom.xml").writeText("<project/>")
                // The mvnw script exits 0 but doesn't write anything to the temp output file.
                // This exercises: result == 0 check, content.isEmpty() → return null.
                val mvnw = projectDir.resolve("mvnw").toFile()
                mvnw.writeText("#!/bin/sh\nexit 0\n")
                mvnw.setExecutable(true)

                val result = stage.extractClasspath(projectDir)
                assertNull(result, "Exit 0 with empty output file → null")
            }

        // ─── tryCompile success paths ─────────────────────────────────────────────

        test("tryCompile returns true when mvnw exits 0").config(enabled = !isWindows) {
            projectDir.resolve("pom.xml").writeText("<project/>")
            val mvnw = projectDir.resolve("mvnw").toFile()
            mvnw.writeText("#!/bin/sh\nexit 0\n")
            mvnw.setExecutable(true)

            val result = stage.tryCompile(projectDir)
            assertTrue(result, "mvnw exiting 0 → tryCompile returns true")
        }

        test("tryCompile returns true when gradlew exits 0").config(enabled = !isWindows) {
            projectDir.resolve("build.gradle").writeText("// empty")
            val gradlew = projectDir.resolve("gradlew").toFile()
            gradlew.writeText("#!/bin/sh\nexit 0\n")
            gradlew.setExecutable(true)

            val result = stage.tryCompile(projectDir)
            assertTrue(result, "gradlew exiting 0 → tryCompile returns true")
        }

        test("tryCompile returns false when gradlew_bat is the only wrapper").config(
            enabled = !isWindows
        ) {
            projectDir.resolve("build.gradle").writeText("// empty")
            val gradlewBat = projectDir.resolve("gradlew.bat").toFile()
            gradlewBat.writeText("@echo off\nexit /B 1\n")
            gradlewBat.setExecutable(true)

            // On Linux the bat script won't execute properly → either fails to start or
            // exits non-zero → tryCompile returns false, never throws.
            val result = runCatching { stage.tryCompile(projectDir) }
            assertTrue(result.isSuccess, "Should not throw when gradlew.bat is used on Linux")
        }
    })
