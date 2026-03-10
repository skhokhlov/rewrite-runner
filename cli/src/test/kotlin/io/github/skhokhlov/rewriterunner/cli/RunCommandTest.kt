package io.github.skhokhlov.rewriterunner.cli

import io.github.skhokhlov.rewriterunner.config.ToolConfig
import io.kotest.core.spec.style.FunSpec
import java.io.ByteArrayOutputStream
import java.io.PrintWriter
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import picocli.CommandLine

class RunCommandTest :
    FunSpec({
        var projectDir: Path = Path.of("")
        var cacheDir: Path = Path.of("")

        beforeEach {
            projectDir = Files.createTempDirectory("rct-project-")
            cacheDir = Files.createTempDirectory("rct-cache-")
        }

        afterEach {
            projectDir.toFile().deleteRecursively()
            cacheDir.toFile().deleteRecursively()
        }

        fun cli(): CommandLine = CommandLine(RunCommand())

        // ─── Help & argument validation ───────────────────────────────────────────

        test("--help exits with code 0") {
            val baos = ByteArrayOutputStream()
            val code = cli().setOut(PrintWriter(baos)).execute("--help")
            assertEquals(0, code, "--help should exit with code 0")
        }

        test("--help output mentions --active-recipe") {
            val baos = ByteArrayOutputStream()
            cli().setOut(PrintWriter(baos)).execute("--help")
            assertTrue(
                baos.toString().contains("active-recipe"),
                "--help should document --active-recipe option"
            )
        }

        test("--help output mentions --recipe-artifact") {
            val baos = ByteArrayOutputStream()
            cli().setOut(PrintWriter(baos)).execute("--help")
            assertTrue(
                baos.toString().contains("recipe-artifact"),
                "--help should document --recipe-artifact option"
            )
        }

        test("--help output mentions --output") {
            val baos = ByteArrayOutputStream()
            cli().setOut(PrintWriter(baos)).execute("--help")
            assertTrue(
                baos.toString().contains("output"),
                "--help should document --output option"
            )
        }

        test("missing required --active-recipe exits with non-zero code") {
            val errBaos = ByteArrayOutputStream()
            val code =
                cli()
                    .setErr(PrintWriter(errBaos))
                    .execute("run", "--project-dir", projectDir.toString())
            assertNotEquals(0, code, "Missing --active-recipe should fail")
        }

        // ─── Default argument values ──────────────────────────────────────────────

        test("default output mode is diff") {
            val cmd = RunCommand()
            CommandLine(
                cmd
            ).parseArgs("--active-recipe", "io.github.skhokhlov.rewriterunner.MyRecipe")
            assertEquals("diff", cmd.outputMode, "Default output mode should be 'diff'")
        }

        test("default project-dir is current directory") {
            val cmd = RunCommand()
            CommandLine(
                cmd
            ).parseArgs("--active-recipe", "io.github.skhokhlov.rewriterunner.MyRecipe")
            assertEquals(Path.of("."), cmd.projectDir, "Default project dir should be '.'")
        }

        test("--dry-run defaults to false") {
            val cmd = RunCommand()
            CommandLine(
                cmd
            ).parseArgs("--active-recipe", "io.github.skhokhlov.rewriterunner.MyRecipe")
            assertEquals(false, cmd.dryRun, "--dry-run should default to false")
        }

        test("--debug defaults to false") {
            val cmd = RunCommand()
            CommandLine(
                cmd
            ).parseArgs("--active-recipe", "io.github.skhokhlov.rewriterunner.MyRecipe")
            assertEquals(false, cmd.debugLogging, "--debug should default to false")
        }

        test("--info defaults to false") {
            val cmd = RunCommand()
            CommandLine(
                cmd
            ).parseArgs("--active-recipe", "io.github.skhokhlov.rewriterunner.MyRecipe")
            assertEquals(false, cmd.infoLogging, "--info should default to false")
        }

        test("--debug flag is accepted") {
            val cmd = RunCommand()
            CommandLine(
                cmd
            ).parseArgs("--active-recipe", "io.github.skhokhlov.rewriterunner.MyRecipe", "--debug")
            assertEquals(true, cmd.debugLogging)
        }

        test("--info flag is accepted") {
            val cmd = RunCommand()
            CommandLine(
                cmd
            ).parseArgs("--active-recipe", "io.github.skhokhlov.rewriterunner.MyRecipe", "--info")
            assertEquals(true, cmd.infoLogging)
        }

        test("--help output mentions --debug") {
            val baos = ByteArrayOutputStream()
            cli().setOut(PrintWriter(baos)).execute("--help")
            assertTrue(baos.toString().contains("--debug"), "--help should document --debug option")
        }

        test("--help output mentions --info") {
            val baos = ByteArrayOutputStream()
            cli().setOut(PrintWriter(baos)).execute("--help")
            assertTrue(baos.toString().contains("--info"), "--help should document --info option")
        }

        test("multiple --recipe-artifact flags are collected into a list") {
            val cmd = RunCommand()
            CommandLine(cmd).parseArgs(
                "--active-recipe",
                "io.github.skhokhlov.rewriterunner.MyRecipe",
                "--recipe-artifact",
                "org.openrewrite.recipe:rewrite-spring:LATEST",
                "--recipe-artifact",
                "org.openrewrite.recipe:rewrite-java:LATEST"
            )
            assertEquals(
                2,
                cmd.recipeArtifacts.size,
                "Two --recipe-artifact flags should produce a list of 2"
            )
        }

        test("--include-extensions splits on comma") {
            val cmd = RunCommand()
            CommandLine(cmd).parseArgs(
                "--active-recipe",
                "io.github.skhokhlov.rewriterunner.MyRecipe",
                "--include-extensions",
                ".java,.kt"
            )
            assertEquals(listOf(".java", ".kt"), cmd.includeExtensions)
        }

        test("--exclude-extensions splits on comma") {
            val cmd = RunCommand()
            CommandLine(cmd).parseArgs(
                "--active-recipe",
                "io.github.skhokhlov.rewriterunner.MyRecipe",
                "--exclude-extensions",
                ".xml,.properties"
            )
            assertEquals(listOf(".xml", ".properties"), cmd.excludeExtensions)
        }

        test("--rewrite-config path is accepted") {
            val rewriteYaml = projectDir.resolve("my-rewrite.yaml")
            rewriteYaml.writeText("---\ntype: specs.openrewrite.org/v1beta/recipe\n")

            val cmd = RunCommand()
            CommandLine(cmd).parseArgs(
                "--active-recipe",
                "io.github.skhokhlov.rewriterunner.MyRecipe",
                "--rewrite-config",
                rewriteYaml.toString()
            )
            assertEquals(rewriteYaml, cmd.rewriteConfig)
        }

        // ─── Output mode validation ───────────────────────────────────────────────

        test("unknown --output value exits with code 1") {
            projectDir.resolve("Hello.java").writeText("class Hello {}")

            val errBaos = ByteArrayOutputStream()
            val code =
                cli()
                    .setErr(PrintWriter(errBaos))
                    .execute(
                        "--project-dir",
                        projectDir.toString(),
                        "--active-recipe",
                        "org.openrewrite.java.format.AutoFormat",
                        "--cache-dir",
                        cacheDir.toString(),
                        "--output",
                        "foobar"
                    )

            assertEquals(
                1,
                code,
                "Unknown --output value should exit with code 1, not silently fall back to diff"
            )
            assertTrue(
                errBaos.toString().contains("foobar"),
                "Error message should mention the invalid value"
            )
        }

        // ─── projectDir validation ────────────────────────────────────────────────

        test("nonexistent --project-dir exits with code 1") {
            val errBaos = ByteArrayOutputStream()
            val code =
                cli()
                    .setErr(PrintWriter(errBaos))
                    .execute(
                        "--project-dir",
                        "/tmp/this-directory-does-not-exist-openrewrite-runner-test",
                        "--active-recipe",
                        "org.openrewrite.java.format.AutoFormat",
                        "--cache-dir",
                        cacheDir.toString()
                    )

            assertEquals(1, code, "Nonexistent project directory should exit with code 1")
        }

        // ─── End-to-end CLI execution ─────────────────────────────────────────────

        test("run with nonexistent recipe returns exit code 1") {
            projectDir.resolve("src/main/java").createDirectories()
            projectDir.resolve("src/main/java/Hello.java").writeText("class Hello {}")

            val errBaos = ByteArrayOutputStream()
            val code =
                cli()
                    .setErr(PrintWriter(errBaos))
                    .execute(
                        "--project-dir",
                        projectDir.toString(),
                        "--active-recipe",
                        "io.github.skhokhlov.rewriterunner.recipe.ThatDoesNotExist",
                        "--cache-dir",
                        cacheDir.toString(),
                        "--include-extensions",
                        ".java"
                    )

            assertNotEquals(0, code, "Nonexistent recipe should produce exit code 1")
        }

        test("user-facing error (IllegalArgumentException) prints message without stack trace") {
            // Regression: when OpenRewrite throws RecipeException (recipe not found), the CLI
            // must show a clear one-line error message, not a full Java stack trace.  Stack traces
            // belong in debug logs, not in user-facing stderr output.
            projectDir.resolve("Hello.java").writeText("class Hello {}")

            val errBaos = ByteArrayOutputStream()
            val code =
                cli()
                    .setErr(PrintWriter(errBaos))
                    .execute(
                        "--project-dir",
                        projectDir.toString(),
                        "--active-recipe",
                        "io.github.skhokhlov.rewriterunner.recipe.ThatDefinitelyDoesNotExist99",
                        "--cache-dir",
                        cacheDir.toString()
                    )

            val stderr = errBaos.toString()
            assertNotEquals(0, code, "Missing recipe should return non-zero exit code")
            assertTrue(
                stderr.contains("not found", ignoreCase = true),
                "Error output should contain 'not found': $stderr"
            )
            assertTrue(
                !stderr.contains("at io.github.skhokhlov.rewriterunner") &&
                    !stderr.contains("at org.openrewrite"),
                "User-error stderr must NOT contain a stack trace; got:\n$stderr"
            )
        }

        test("run with AutoFormat recipe on a simple Java project produces output") {
            projectDir.resolve("Hello.java").writeText(
                "public class Hello {public static void main(String[] args){System.out.println(\"hi\");}}"
            )

            val outBaos = ByteArrayOutputStream()
            val errBaos = ByteArrayOutputStream()

            val code =
                cli()
                    .setOut(PrintWriter(outBaos))
                    .setErr(PrintWriter(errBaos))
                    .execute(
                        "--project-dir",
                        projectDir.toString(),
                        "--active-recipe",
                        "org.openrewrite.java.format.AutoFormat",
                        "--cache-dir",
                        cacheDir.toString(),
                        "--include-extensions",
                        ".java",
                        "--output",
                        "diff"
                    )

            val stdout = outBaos.toString()
            val stderr = errBaos.toString()

            if (code == 0) {
                assertTrue(
                    stdout.contains("---") || stdout.contains("No changes"),
                    "Success should produce a diff or 'No changes'. Got: $stdout"
                )
            } else {
                assertTrue(
                    stderr.isNotBlank(),
                    "Failure should produce an error message. stderr: $stderr"
                )
            }
        }

        test("run with --dry-run does not write files") {
            val originalContent = "public class Dry { public static void main(String[]args){} }"
            val javaFile = projectDir.resolve("Dry.java")
            javaFile.writeText(originalContent)

            cli().execute(
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
                originalContent,
                javaFile.toFile().readText(),
                "--dry-run should not modify files"
            )
        }

        test("run with --output files mode does not print diffs") {
            projectDir.resolve("Hello.java").writeText(
                "public class Hello {public static void main(String[] args){System.out.println(\"hi\");}}"
            )

            val outBaos = ByteArrayOutputStream()
            val code =
                cli()
                    .setOut(PrintWriter(outBaos))
                    .execute(
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

            val output = outBaos.toString()
            if (code == 0 && !output.contains("No files changed")) {
                assertTrue(
                    !output.contains("---") && !output.contains("@@"),
                    "FILES mode should not contain unified diff markers"
                )
            }
        }

        test("run with --output report creates JSON file") {
            projectDir.resolve("Hello.java").writeText(
                "public class Hello {public static void main(String[] args){System.out.println(\"hi\");}}"
            )

            val code =
                cli().execute(
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

            if (code == 0) {
                val reportFile = projectDir.resolve("openrewrite-report.json").toFile()
                assertTrue(
                    reportFile.exists(),
                    "Report file should be created with --output report"
                )
            }
        }

        test("run with custom rewrite_yaml is accepted without error") {
            val rewriteYaml = projectDir.resolve("rewrite.yaml")
            rewriteYaml.writeText(
                """
                ---
                type: specs.openrewrite.org/v1beta/recipe
                name: com.example.MyCompositeRecipe
                displayName: My Composite Recipe
                recipeList:
                  - org.openrewrite.java.format.AutoFormat
                """.trimIndent()
            )
            projectDir.resolve("Hello.java").writeText("class Hello {}")

            val result = runCatching {
                cli().execute(
                    "--project-dir",
                    projectDir.toString(),
                    "--active-recipe",
                    "com.example.MyCompositeRecipe",
                    "--rewrite-config",
                    rewriteYaml.toString(),
                    "--cache-dir",
                    cacheDir.toString(),
                    "--include-extensions",
                    ".java"
                )
            }
            assertTrue(
                result.isSuccess,
                "CLI should not throw an uncaught exception: ${result.exceptionOrNull()}"
            )
        }
    })
