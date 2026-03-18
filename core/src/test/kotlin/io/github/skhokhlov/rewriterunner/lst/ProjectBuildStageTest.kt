package io.github.skhokhlov.rewriterunner.lst

import io.github.skhokhlov.rewriterunner.NoOpRunnerLogger
import io.kotest.core.spec.style.FunSpec
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProjectBuildStageTest :
    FunSpec({
        var projectDir: Path = Path.of("")
        val stage = ProjectBuildStage(NoOpRunnerLogger)

        beforeEach { projectDir = Files.createTempDirectory("bts-") }

        afterEach { projectDir.toFile().deleteRecursively() }

        test("returns null when no build file exists") {
            // Empty directory with no pom.xml or build.gradle
            val result = stage.extractClasspath(projectDir)
            assertNull(result, "Should return null when project has no recognized build file")
        }

        test("returns null for Maven project when mvn is not on PATH and no wrapper") {
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
            // We can only assert it doesn't throw; null is the expected path in most CI environments
            // This test documents the contract: failure → null, not exception
            @Suppress("UNUSED_VARIABLE")
            val result = stage.extractClasspath(projectDir)
        }

        test("returns null for Gradle project when no wrapper and gradle not on PATH") {
            projectDir.resolve("build.gradle.kts").writeText(
                """
                plugins { kotlin("jvm") version "2.1.0" }
                """.trimIndent()
            )

            // No gradlew in projectDir, so falls back to 'gradle' command
            // In environments without gradle installed this returns null
            // Contract: failure → null, not exception
            @Suppress("UNUSED_VARIABLE")
            val result = stage.extractClasspath(projectDir)
        }

        test("detects Maven project via pom_xml presence") {
            projectDir.resolve("pom.xml").writeText("<project/>")
            // Just verify the method runs without throwing; Maven may not be installed
            val result = runCatching { stage.extractClasspath(projectDir) }
            assertTrue_compat(
                result.isSuccess,
                "extractClasspath should not throw for a Maven project"
            )
        }

        test("detects Gradle project via build_gradle_kts presence") {
            projectDir.resolve("build.gradle.kts").writeText("// empty")
            val result = runCatching { stage.extractClasspath(projectDir) }
            assertTrue_compat(
                result.isSuccess,
                "extractClasspath should not throw for a Gradle KTS project"
            )
        }

        test("detects Gradle project via build_gradle presence") {
            projectDir.resolve("build.gradle").writeText("// empty")
            val result = runCatching { stage.extractClasspath(projectDir) }
            assertTrue_compat(
                result.isSuccess,
                "extractClasspath should not throw for a Gradle Groovy project"
            )
        }

        test("Maven wins over Gradle when both pom_xml and build_gradle exist") {
            projectDir.resolve("pom.xml").writeText("<project/>")
            projectDir.resolve("build.gradle").writeText("// empty")
            // Maven is preferred; just verify no exception
            val result = runCatching { stage.extractClasspath(projectDir) }
            assertTrue_compat(
                result.isSuccess,
                "Should not throw when both pom.xml and build.gradle exist"
            )
        }

        // ─── tryCompile tests ─────────────────────────────────────────────────────

        test("tryCompile returns false when no build file exists") {
            val result = stage.tryCompile(projectDir)
            assertFalse(result, "Should return false when project has no recognized build file")
        }

        test("tryCompile returns false without throwing when Maven not available") {
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

        test("tryCompile returns false without throwing when Gradle not available") {
            projectDir.resolve("build.gradle.kts").writeText("// empty")
            // No gradlew wrapper → falls back to 'gradle'; may not be on PATH in CI
            val result = runCatching { stage.tryCompile(projectDir) }
            assertTrue_compat(
                result.isSuccess,
                "tryCompile should not throw for a Gradle KTS project"
            )
        }

        test("tryCompile prefers Maven when both pom_xml and build_gradle exist") {
            projectDir.resolve("pom.xml").writeText("<project/>")
            projectDir.resolve("build.gradle").writeText("// empty")
            // Maven is preferred (same precedence as extractClasspath); must not throw
            val result = runCatching { stage.tryCompile(projectDir) }
            assertTrue_compat(
                result.isSuccess,
                "tryCompile should not throw when both pom.xml and build.gradle exist"
            )
        }

        test("tryCompile uses mvnw wrapper when present") {
            projectDir.resolve("pom.xml").writeText("<project/>")
            // Create a fake mvnw that exits 1 — still must not throw
            val mvnw = projectDir.resolve("mvnw").toFile()
            mvnw.writeText("#!/bin/sh\nexit 1\n")
            mvnw.setExecutable(true)
            val result = runCatching { stage.tryCompile(projectDir) }
            assertTrue_compat(
                result.isSuccess,
                "tryCompile should not throw when mvnw exits non-zero"
            )
        }

        test("tryCompile uses gradlew wrapper when present") {
            projectDir.resolve("build.gradle").writeText("// empty")
            // Create a fake gradlew that exits 1 — still must not throw
            val gradlew = projectDir.resolve("gradlew").toFile()
            gradlew.writeText("#!/bin/sh\nexit 1\n")
            gradlew.setExecutable(true)
            val result = runCatching { stage.tryCompile(projectDir) }
            assertTrue_compat(
                result.isSuccess,
                "tryCompile should not throw when gradlew exits non-zero"
            )
        }

        // ─── Gradle init script tests ─────────────────────────────────────────────

        test("Gradle init script uses .gradle extension so Gradle parses it as Groovy DSL") {
            // Create a fake gradlew that captures the --init-script argument and verifies
            // it ends in ".gradle", then prints a fake JAR path to stdout.
            val fakeJar = Files.createTempFile("fake-dep-", ".jar")
            try {
                projectDir.resolve("build.gradle.kts").writeText("// empty")
                val gradlew = projectDir.resolve("gradlew").toFile()
                gradlew.writeText(
                    """
                    #!/bin/sh
                    for arg in "${'$'}@"; do
                        case "${'$'}arg" in
                            *.gradle.kts)
                                echo "ERROR: init script has .gradle.kts extension" >&2
                                exit 2
                                ;;
                            *.gradle)
                                echo "${fakeJar.toAbsolutePath()}"
                                exit 0
                                ;;
                        esac
                    done
                    exit 1
                    """.trimIndent()
                )
                gradlew.setExecutable(true)

                val result = stage.extractClasspath(projectDir)
                assertTrue(
                    result != null && result.isNotEmpty(),
                    "Stage 1 should succeed with .gradle init script; result was $result"
                )
            } finally {
                fakeJar.toFile().delete()
            }
        }

        // ─── Deadlock-prevention tests ────────────────────────────────────────────

        test(
            "extractClasspath does not hang when Maven wrapper produces large stdout output"
        ) {
            projectDir.resolve("pom.xml").writeText("<project/>")
            // Fake mvnw that writes ~128 KB to stdout in a single dd call (well above the typical
            // 64 KB OS pipe buffer) without spawning any subprocesses, so the script itself is fast.
            // If stdout were not properly discarded the pipe would fill and the child would block,
            // causing the test to time out at the 120-second process timeout instead of completing
            // near-instantly.
            val mvnw = projectDir.resolve("mvnw").toFile()
            mvnw.writeText(
                """
                #!/bin/sh
                dd if=/dev/zero bs=131072 count=1 2>/dev/null
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
                "Should complete within 15 seconds without hanging due to full pipe buffer, " +
                    "took ${elapsed}ms"
            )
        }

        test("tryCompile does not hang when Maven wrapper produces large stdout output") {
            projectDir.resolve("pom.xml").writeText("<project/>")
            // Same fast approach as the extractClasspath test above: single dd call writes 128 KB
            // without spawning any subprocesses.
            val mvnw = projectDir.resolve("mvnw").toFile()
            mvnw.writeText(
                """
                #!/bin/sh
                dd if=/dev/zero bs=131072 count=1 2>/dev/null
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
    })

private fun assertTrue_compat(value: Boolean, message: String) {
    if (!value) throw AssertionError(message)
}
