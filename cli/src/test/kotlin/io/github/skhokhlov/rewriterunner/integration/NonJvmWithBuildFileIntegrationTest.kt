package io.github.skhokhlov.rewriterunner.integration

import io.kotest.core.spec.style.FunSpec
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Integration tests for the non-JVM workflow matrix described in issue #175.
 *
 * Covers YAML-only and Dockerfile-only recipes in projects that also contain a Gradle or Maven
 * build file. For each combination the tests distinguish:
 * - `--skip-plugin-run` (LST fallback path, no build-tool wrapper invoked)
 * - default plugin-first path (fake wrapper validates orchestration end-to-end)
 *
 * All plugin-first tests require POSIX `chmod +x` and are skipped on Windows.
 */
class NonJvmWithBuildFileIntegrationTest :
    FunSpec({
        var projectDir: Path = Path.of("")
        var cacheDir: Path = Path.of("")

        beforeEach {
            projectDir = Files.createTempDirectory("njvm-it-project-")
            cacheDir = Files.createTempDirectory("njvm-it-cache-")
        }

        afterEach {
            projectDir.toFile().deleteRecursively()
            cacheDir.toFile().deleteRecursively()
        }

        // ─── Section 1: YAML-only + build.gradle.kts ─────────────────────────────

        test("yaml + build.gradle.kts: --skip-plugin-run modifies yaml via LST fallback") {
            projectDir.resolve("build.gradle.kts").writeText("")
            val yamlFile = projectDir.resolve("application.yaml")
            yamlFile.writeText("port: PLACEHOLDER\n")
            projectDir.writeFindAndReplaceRecipe(find = "PLACEHOLDER", replace = "8080")
            projectDir.writeFakeGradlew(
                "application.yaml",
                "port: PLACEHOLDER",
                "port: 8080",
                "port: 8080\n"
            )

            val result =
                runCli(
                    "--project-dir", projectDir.toString(),
                    "--active-recipe", "com.example.integration.FindAndReplace",
                    "--rewrite-config", projectDir.resolve("rewrite.yaml").toString(),
                    "--cache-dir", cacheDir.toString(),
                    "--skip-plugin-run"
                )

            assertEquals(0, result.exitCode, "stderr: ${result.stderr}")
            assertTrue(yamlFile.readText().contains("8080"), "yaml should be updated via LST")
            assertFalse(
                projectDir.resolve("wrapper-calls.log").exists(),
                "gradlew must not be called"
            )
        }

        test("yaml + build.gradle.kts: --skip-plugin-run --dry-run does not mutate yaml") {
            projectDir.resolve("build.gradle.kts").writeText("")
            val yamlFile = projectDir.resolve("application.yaml")
            val original = "port: PLACEHOLDER\n"
            yamlFile.writeText(original)
            projectDir.writeFindAndReplaceRecipe(find = "PLACEHOLDER", replace = "8080")

            runCli(
                "--project-dir", projectDir.toString(),
                "--active-recipe", "com.example.integration.FindAndReplace",
                "--rewrite-config", projectDir.resolve("rewrite.yaml").toString(),
                "--cache-dir", cacheDir.toString(),
                "--skip-plugin-run",
                "--dry-run"
            )

            assertEquals(
                original,
                yamlFile.readText(),
                "--dry-run must not write yaml changes to disk"
            )
        }

        test(
            "yaml + build.gradle.kts: plugin-first invokes gradlew wrapper and modifies yaml"
        ).config(enabled = !isWindows) {
            projectDir.resolve("build.gradle.kts").writeText("")
            val yamlFile = projectDir.resolve("application.yaml")
            yamlFile.writeText("port: PLACEHOLDER\n")
            projectDir.writeFindAndReplaceRecipe(find = "PLACEHOLDER", replace = "8080")
            projectDir.writeFakeGradlew(
                "application.yaml",
                "port: PLACEHOLDER",
                "port: 8080",
                "port: 8080\n"
            )

            val result =
                runCli(
                    "--project-dir", projectDir.toString(),
                    "--active-recipe", "com.example.integration.FindAndReplace",
                    "--rewrite-config", projectDir.resolve("rewrite.yaml").toString(),
                    "--cache-dir", cacheDir.toString(),
                    "--output", "files"
                )

            assertEquals(0, result.exitCode, "stderr: ${result.stderr}")
            assertTrue(
                yamlFile.readText().contains("8080"),
                "yaml should be updated via plugin-first"
            )
            assertEquals(
                "rewriteDryRun\nrewriteRun\n",
                projectDir.resolve("wrapper-calls.log").readText(),
                "gradlew must be called dry-run then apply"
            )
        }

        // ─── Section 2: YAML-only + pom.xml ──────────────────────────────────────

        test("yaml + pom.xml: --skip-plugin-run modifies yaml via LST fallback") {
            projectDir.resolve("pom.xml").writeText("<project/>")
            val yamlFile = projectDir.resolve("application.yaml")
            yamlFile.writeText("port: PLACEHOLDER\n")
            projectDir.writeFindAndReplaceRecipe(find = "PLACEHOLDER", replace = "8080")
            projectDir.writeFakeMvnwSimple(
                "application.yaml",
                "port: PLACEHOLDER",
                "port: 8080",
                "port: 8080\n"
            )

            val result =
                runCli(
                    "--project-dir", projectDir.toString(),
                    "--active-recipe", "com.example.integration.FindAndReplace",
                    "--rewrite-config", projectDir.resolve("rewrite.yaml").toString(),
                    "--cache-dir", cacheDir.toString(),
                    "--skip-plugin-run"
                )

            assertEquals(0, result.exitCode, "stderr: ${result.stderr}")
            assertTrue(yamlFile.readText().contains("8080"), "yaml should be updated via LST")
            assertFalse(projectDir.resolve("wrapper-calls.log").exists(), "mvnw must not be called")
        }

        test(
            "yaml + pom.xml: plugin-first invokes mvnw wrapper and modifies yaml"
        ).config(enabled = !isWindows) {
            projectDir.resolve("pom.xml").writeText("<project/>")
            val yamlFile = projectDir.resolve("application.yaml")
            yamlFile.writeText("port: PLACEHOLDER\n")
            projectDir.writeFindAndReplaceRecipe(find = "PLACEHOLDER", replace = "8080")
            projectDir.writeFakeMvnwSimple(
                "application.yaml",
                "port: PLACEHOLDER",
                "port: 8080",
                "port: 8080\n"
            )

            val result =
                runCli(
                    "--project-dir", projectDir.toString(),
                    "--active-recipe", "com.example.integration.FindAndReplace",
                    "--rewrite-config", projectDir.resolve("rewrite.yaml").toString(),
                    "--cache-dir", cacheDir.toString(),
                    "--output", "files"
                )

            assertEquals(0, result.exitCode, "stderr: ${result.stderr}")
            assertTrue(
                yamlFile.readText().contains("8080"),
                "yaml should be updated via plugin-first"
            )
            assertEquals(
                "dryRun\nrun\n",
                projectDir.resolve("wrapper-calls.log").readText(),
                "mvnw must be called dry-run then apply"
            )
        }

        // ─── Section 3: Dockerfile-only + build.gradle.kts ───────────────────────

        test(
            "dockerfile + build.gradle.kts: --skip-plugin-run modifies dockerfile via LST fallback"
        ) {
            projectDir.resolve("build.gradle.kts").writeText("")
            val dockerfile = projectDir.resolve("Dockerfile")
            dockerfile.writeText("FROM ubuntu:PLACEHOLDER\n")
            projectDir.writeFindAndReplaceRecipe(find = "PLACEHOLDER", replace = "22.04")
            projectDir.writeFakeGradlew(
                "Dockerfile",
                "FROM ubuntu:PLACEHOLDER",
                "FROM ubuntu:22.04",
                "FROM ubuntu:22.04\n"
            )

            val result =
                runCli(
                    "--project-dir", projectDir.toString(),
                    "--active-recipe", "com.example.integration.FindAndReplace",
                    "--rewrite-config", projectDir.resolve("rewrite.yaml").toString(),
                    "--cache-dir", cacheDir.toString(),
                    "--skip-plugin-run"
                )

            assertEquals(0, result.exitCode, "stderr: ${result.stderr}")
            assertTrue(
                dockerfile.readText().contains("ubuntu:22.04"),
                "Dockerfile should be updated via LST"
            )
            assertFalse(
                projectDir.resolve("wrapper-calls.log").exists(),
                "gradlew must not be called"
            )
        }

        test(
            "dockerfile + build.gradle.kts: plugin-first invokes gradlew wrapper and modifies dockerfile"
        ).config(enabled = !isWindows) {
            projectDir.resolve("build.gradle.kts").writeText("")
            val dockerfile = projectDir.resolve("Dockerfile")
            dockerfile.writeText("FROM ubuntu:PLACEHOLDER\n")
            projectDir.writeFindAndReplaceRecipe(find = "PLACEHOLDER", replace = "22.04")
            projectDir.writeFakeGradlew(
                "Dockerfile",
                "FROM ubuntu:PLACEHOLDER",
                "FROM ubuntu:22.04",
                "FROM ubuntu:22.04\n"
            )

            val result =
                runCli(
                    "--project-dir", projectDir.toString(),
                    "--active-recipe", "com.example.integration.FindAndReplace",
                    "--rewrite-config", projectDir.resolve("rewrite.yaml").toString(),
                    "--cache-dir", cacheDir.toString(),
                    "--output", "files"
                )

            assertEquals(0, result.exitCode, "stderr: ${result.stderr}")
            assertTrue(
                dockerfile.readText().contains("ubuntu:22.04"),
                "Dockerfile should be updated via plugin-first"
            )
            assertEquals(
                "rewriteDryRun\nrewriteRun\n",
                projectDir.resolve("wrapper-calls.log").readText(),
                "gradlew must be called dry-run then apply"
            )
        }

        // ─── Section 4: Dockerfile-only + pom.xml ────────────────────────────────

        test("dockerfile + pom.xml: --skip-plugin-run modifies dockerfile via LST fallback") {
            projectDir.resolve("pom.xml").writeText("<project/>")
            val dockerfile = projectDir.resolve("Dockerfile")
            dockerfile.writeText("FROM ubuntu:PLACEHOLDER\n")
            projectDir.writeFindAndReplaceRecipe(find = "PLACEHOLDER", replace = "22.04")
            projectDir.writeFakeMvnwSimple(
                "Dockerfile",
                "FROM ubuntu:PLACEHOLDER",
                "FROM ubuntu:22.04",
                "FROM ubuntu:22.04\n"
            )

            val result =
                runCli(
                    "--project-dir", projectDir.toString(),
                    "--active-recipe", "com.example.integration.FindAndReplace",
                    "--rewrite-config", projectDir.resolve("rewrite.yaml").toString(),
                    "--cache-dir", cacheDir.toString(),
                    "--skip-plugin-run"
                )

            assertEquals(0, result.exitCode, "stderr: ${result.stderr}")
            assertTrue(
                dockerfile.readText().contains("ubuntu:22.04"),
                "Dockerfile should be updated via LST"
            )
            assertFalse(projectDir.resolve("wrapper-calls.log").exists(), "mvnw must not be called")
        }

        test(
            "dockerfile + pom.xml: plugin-first invokes mvnw wrapper and modifies dockerfile"
        ).config(enabled = !isWindows) {
            projectDir.resolve("pom.xml").writeText("<project/>")
            val dockerfile = projectDir.resolve("Dockerfile")
            dockerfile.writeText("FROM ubuntu:PLACEHOLDER\n")
            projectDir.writeFindAndReplaceRecipe(find = "PLACEHOLDER", replace = "22.04")
            projectDir.writeFakeMvnwSimple(
                "Dockerfile",
                "FROM ubuntu:PLACEHOLDER",
                "FROM ubuntu:22.04",
                "FROM ubuntu:22.04\n"
            )

            val result =
                runCli(
                    "--project-dir", projectDir.toString(),
                    "--active-recipe", "com.example.integration.FindAndReplace",
                    "--rewrite-config", projectDir.resolve("rewrite.yaml").toString(),
                    "--cache-dir", cacheDir.toString(),
                    "--output", "files"
                )

            assertEquals(0, result.exitCode, "stderr: ${result.stderr}")
            assertTrue(
                dockerfile.readText().contains("ubuntu:22.04"),
                "Dockerfile should be updated via plugin-first"
            )
            assertEquals(
                "dryRun\nrun\n",
                projectDir.resolve("wrapper-calls.log").readText(),
                "mvnw must be called dry-run then apply"
            )
        }

        // ─── Section 5: --exclude-paths removes JVM files, YAML still processed ──

        test(
            "exclude-paths removes all jvm files: yaml still processed, classpath stages skipped"
        ) {
            projectDir.resolve("build.gradle.kts").writeText("")
            projectDir.resolve("App.java").writeText("class App {}\n")
            val yamlFile = projectDir.resolve("application.yaml")
            yamlFile.writeText("port: PLACEHOLDER\n")
            projectDir.writeFindAndReplaceRecipe(find = "PLACEHOLDER", replace = "8080")
            projectDir.writeFakeGradlew(
                "application.yaml",
                "port: PLACEHOLDER",
                "port: 8080",
                "port: 8080\n"
            )

            // Exclude both .java sources and .kts build scripts so no JVM file survives — this is
            // the scenario where LstBuilder skips classpath resolution stages entirely.
            val result =
                runCli(
                    "--project-dir", projectDir.toString(),
                    "--active-recipe", "com.example.integration.FindAndReplace",
                    "--rewrite-config", projectDir.resolve("rewrite.yaml").toString(),
                    "--cache-dir", cacheDir.toString(),
                    "--skip-plugin-run",
                    "--exclude-paths", "**/*.java,**/*.kts"
                )

            assertEquals(0, result.exitCode, "stderr: ${result.stderr}")
            assertTrue(
                yamlFile.readText().contains("8080"),
                "yaml should be updated when jvm files are excluded"
            )
            assertEquals(
                "class App {}\n",
                projectDir.resolve("App.java").readText(),
                "java file must be untouched"
            )
            assertFalse(
                projectDir.resolve("wrapper-calls.log").exists(),
                "gradlew must not be called — no JVM files survive excludePaths"
            )
        }
    })
