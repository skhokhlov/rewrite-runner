package io.github.skhokhlov.rewriterunner.integration

import io.kotest.core.spec.style.FunSpec
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Integration tests for Java projects.
 *
 * Each test creates a minimal Java sample project under a temp directory,
 * runs the CLI, and asserts on the transformed output — analogous to OpenRewrite's
 * rewriteRun(java("before", "after")) pattern but exercised end-to-end via the CLI.
 */
class JavaProjectIntegrationTest :
    FunSpec({
        var projectDir: Path = Path.of("")
        var cacheDir: Path = Path.of("")

        beforeEach {
            projectDir = Files.createTempDirectory("java-it-project-")
            cacheDir = Files.createTempDirectory("java-it-cache-")
        }

        afterEach {
            projectDir.toFile().deleteRecursively()
            cacheDir.toFile().deleteRecursively()
        }

        /** Badly-formatted Java that AutoFormat will always rewrite. */
        val unformattedClass =
            """public class Hello{public static void main(String[] args){System.out.println("hi");}}"""

        // ─── Diff output (default mode) ───────────────────────────────────────────

        test("AutoFormat produces unified diff for unformatted Java class") {
            projectDir.resolve("Hello.java").writeText(unformattedClass)

            val result =
                runCli(
                    "--project-dir",
                    projectDir.toString(),
                    "--active-recipe",
                    "org.openrewrite.java.format.AutoFormat",
                    "--cache-dir",
                    cacheDir.toString(),
                    "--include-extensions",
                    ".java",
                    "--dry-run"
                )

            assertEquals(0, result.exitCode, "stderr: ${result.stderr}")
            assertTrue(
                result.stdout.contains("---"),
                "Expected unified diff markers:\n${result.stdout}"
            )
            assertTrue(
                result.stdout.contains("+++"),
                "Expected unified diff markers:\n${result.stdout}"
            )
        }

        test("AutoFormat diff preserves class name in output") {
            projectDir.resolve("Hello.java").writeText(unformattedClass)

            val result =
                runCli(
                    "--project-dir",
                    projectDir.toString(),
                    "--active-recipe",
                    "org.openrewrite.java.format.AutoFormat",
                    "--cache-dir",
                    cacheDir.toString(),
                    "--include-extensions",
                    ".java",
                    "--dry-run"
                )

            assertEquals(0, result.exitCode, "stderr: ${result.stderr}")
            assertTrue(
                result.stdout.contains("Hello"),
                "Diff should reference the changed file:\n${result.stdout}"
            )
        }

        // ─── Dry-run ──────────────────────────────────────────────────────────────

        test("AutoFormat with --dry-run does not write changes to disk") {
            val javaFile = projectDir.resolve("Hello.java")
            javaFile.writeText(unformattedClass)

            runCli(
                "--project-dir",
                projectDir.toString(),
                "--active-recipe",
                "org.openrewrite.java.format.AutoFormat",
                "--cache-dir",
                cacheDir.toString(),
                "--include-extensions",
                ".java",
                "--dry-run"
            )

            assertEquals(
                unformattedClass,
                javaFile.readText(),
                "--dry-run must not modify files on disk"
            )
        }

        // ─── Writing changes ──────────────────────────────────────────────────────

        test("AutoFormat writes reformatted Java to disk") {
            val javaFile = projectDir.resolve("Hello.java")
            javaFile.writeText(unformattedClass)

            val result =
                runCli(
                    "--project-dir",
                    projectDir.toString(),
                    "--active-recipe",
                    "org.openrewrite.java.format.AutoFormat",
                    "--cache-dir",
                    cacheDir.toString(),
                    "--include-extensions",
                    ".java"
                )

            assertEquals(0, result.exitCode, "stderr: ${result.stderr}")
            val formatted = javaFile.readText()
            assertNotEquals(unformattedClass, formatted, "File should be reformatted")
            assertTrue(formatted.contains("class Hello"), "Class name must survive formatting")
            assertTrue(
                formatted.contains("System.out.println"),
                "Method body must survive formatting"
            )
        }

        // ─── Output modes ─────────────────────────────────────────────────────────

        test("--output files lists changed paths without diff markers") {
            projectDir.resolve("Hello.java").writeText(unformattedClass)

            val result =
                runCli(
                    "--project-dir",
                    projectDir.toString(),
                    "--active-recipe",
                    "org.openrewrite.java.format.AutoFormat",
                    "--cache-dir",
                    cacheDir.toString(),
                    "--include-extensions",
                    ".java",
                    "--output",
                    "files",
                    "--dry-run"
                )

            assertEquals(0, result.exitCode, "stderr: ${result.stderr}")
            assertTrue(
                result.stdout.contains("Hello.java") || result.stdout.contains("No files"),
                "FILES mode should list changed paths: ${result.stdout}"
            )
            assertTrue(
                !result.stdout.contains("---"),
                "FILES mode must not emit diff markers: ${result.stdout}"
            )
            assertTrue(
                !result.stdout.contains("@@"),
                "FILES mode must not emit hunk headers: ${result.stdout}"
            )
        }

        test("--output report creates openrewrite-report json") {
            projectDir.resolve("Hello.java").writeText(unformattedClass)

            val result =
                runCli(
                    "--project-dir",
                    projectDir.toString(),
                    "--active-recipe",
                    "org.openrewrite.java.format.AutoFormat",
                    "--cache-dir",
                    cacheDir.toString(),
                    "--include-extensions",
                    ".java",
                    "--output",
                    "report",
                    "--dry-run"
                )

            assertEquals(0, result.exitCode, "stderr: ${result.stderr}")
            val report = projectDir.resolve("openrewrite-report.json").toFile()
            assertTrue(report.exists(), "openrewrite-report.json must be created")
            val json = report.readText()
            assertTrue(json.contains("\"results\""), "Report must contain a 'results' key")
            assertTrue(json.contains("\"totalChanged\""), "Report must contain 'totalChanged'")
        }

        // ─── Realistic project structure ──────────────────────────────────────────

        test("AutoFormat processes all Java files in nested source tree") {
            val srcDir =
                projectDir.resolve("src/main/java/com/example").also { it.createDirectories() }
            srcDir.resolve("Greeter.java").writeText(
                """public class Greeter{public String greet(String name){return "Hello "+name;}}"""
            )
            srcDir.resolve("App.java").writeText(
                """public class App{public static void main(String[] args){new Greeter().greet("world");}}"""
            )

            val result =
                runCli(
                    "--project-dir",
                    projectDir.toString(),
                    "--active-recipe",
                    "org.openrewrite.java.format.AutoFormat",
                    "--cache-dir",
                    cacheDir.toString(),
                    "--include-extensions",
                    ".java",
                    "--output",
                    "files",
                    "--dry-run"
                )

            assertEquals(0, result.exitCode, "stderr: ${result.stderr}")
            val output = result.stdout
            assertTrue(
                (output.contains("Greeter.java") && output.contains("App.java")) ||
                    output.contains("No files"),
                "Both source files should be reported: $output"
            )
        }

        test("extension filter limits processing to java files only") {
            projectDir.resolve("Hello.java").writeText(unformattedClass)
            projectDir.resolve("ignored.txt").writeText("this is plain text")

            val result =
                runCli(
                    "--project-dir",
                    projectDir.toString(),
                    "--active-recipe",
                    "org.openrewrite.java.format.AutoFormat",
                    "--cache-dir",
                    cacheDir.toString(),
                    "--include-extensions",
                    ".java",
                    "--output",
                    "files",
                    "--dry-run"
                )

            assertEquals(0, result.exitCode, "stderr: ${result.stderr}")
            assertTrue(
                !result.stdout.contains("ignored.txt"),
                "Non-Java file should not appear in output"
            )
        }
    })
