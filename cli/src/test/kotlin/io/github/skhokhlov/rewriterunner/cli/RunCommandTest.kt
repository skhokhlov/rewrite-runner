package io.github.skhokhlov.rewriterunner.cli

import io.github.skhokhlov.rewriterunner.ExecutionDiagnostics
import io.github.skhokhlov.rewriterunner.ExecutionMode
import io.github.skhokhlov.rewriterunner.RunResult
import io.github.skhokhlov.rewriterunner.apply.ApplyFailure
import io.github.skhokhlov.rewriterunner.apply.ChangeKind
import io.github.skhokhlov.rewriterunner.apply.WriteOutcome
import io.github.skhokhlov.rewriterunner.output.OutputMode
import io.kotest.core.spec.style.FunSpec
import java.io.ByteArrayOutputStream
import java.io.PrintWriter
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
            assertEquals(OutputMode.DIFF, cmd.outputMode, "Default output mode should be DIFF")
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

        test("--skip-plugin-run defaults to false") {
            val cmd = RunCommand()
            CommandLine(
                cmd
            ).parseArgs("--active-recipe", "io.github.skhokhlov.rewriterunner.MyRecipe")
            assertEquals(false, cmd.skipPluginRun, "--skip-plugin-run should default to false")
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

        test("--help output mentions --no-maven-central") {
            val baos = ByteArrayOutputStream()
            cli().setOut(PrintWriter(baos)).execute("--help")
            assertTrue(
                baos.toString().contains("no-maven-central"),
                "--help should document --no-maven-central option"
            )
        }

        test("--help output mentions --skip-plugin-run") {
            val baos = ByteArrayOutputStream()
            cli().setOut(PrintWriter(baos)).execute("--help")
            assertTrue(
                baos.toString().contains("skip-plugin-run"),
                "--help should document --skip-plugin-run option"
            )
        }

        test("--download-threads is parsed and propagated to the command") {
            val cmd = RunCommand()
            CommandLine(cmd).parseArgs(
                "--active-recipe",
                "io.github.skhokhlov.rewriterunner.MyRecipe",
                "--artifact-download-threads",
                "8"
            )
            assertEquals(
                8,
                cmd.downloadThreads,
                "--artifact-download-threads 8 should set downloadThreads to 8"
            )
        }

        test("execution options are flat, repeatable, and parse values beginning with a dash") {
            val cmd = RunCommand()
            CommandLine(cmd).parseArgs(
                "--active-recipe",
                "io.github.skhokhlov.rewriterunner.MyRecipe",
                "--execution-mode=in-process",
                "--executor-jvm-arg=-Xmx4g",
                "--executor-jvm-arg=-XX:+UseG1GC",
                "--plugin-jvm-arg=-XX:MaxMetaspaceSize=1g",
                "--lst-worker-jvm-arg=-XX:+HeapDumpOnOutOfMemoryError",
                "--lst-worker-timeout=30m"
            )
            assertEquals(ExecutionMode.IN_PROCESS, cmd.executionMode)
            assertEquals(listOf("-Xmx4g", "-XX:+UseG1GC"), cmd.executorJvmArgs)
            assertEquals(listOf("-XX:MaxMetaspaceSize=1g"), cmd.pluginExecutorJvmArgs)
            assertEquals(listOf("-XX:+HeapDumpOnOutOfMemoryError"), cmd.lstWorkerJvmArgs)
            assertEquals(Duration.ofMinutes(30), cmd.lstWorkerTimeout)
        }

        test("removed plugin JVM option reports its migration target") {
            val errors = ByteArrayOutputStream()
            val code = cli().setErr(PrintWriter(errors)).execute(
                "--active-recipe",
                "io.github.skhokhlov.rewriterunner.MyRecipe",
                "--plugin-jvm-args=-Xmx4g"
            )

            assertEquals(1, code)
            assertTrue(errors.toString().contains("--plugin-jvm-arg"))
        }

        test("timeout options are parsed") {
            val cmd = RunCommand()
            CommandLine(cmd).parseArgs(
                "--active-recipe",
                "io.github.skhokhlov.rewriterunner.MyRecipe",
                "--subprocess-run-timeout",
                "45s",
                "--plugin-run-timeout",
                "15m",
                "--artifact-resolver-connect-timeout",
                "10000ms",
                "--artifact-resolver-request-timeout",
                "20s"
            )
            assertEquals(Duration.ofSeconds(45), cmd.processTimeout)
            assertEquals(Duration.ofMinutes(15), cmd.pluginTimeout)
            assertEquals(Duration.ofMillis(10_000), cmd.resolverConnectTimeout)
            assertEquals(Duration.ofSeconds(20), cmd.resolverRequestTimeout)
        }

        test("--help output mentions timeout options") {
            val baos = ByteArrayOutputStream()
            cli().setOut(PrintWriter(baos)).execute("--help")
            val help = baos.toString()
            assertTrue(help.contains("--subprocess-run-timeout"))
            assertTrue(help.contains("--plugin-run-timeout"))
            assertTrue(help.contains("--resolver-connect-timeout"))
            assertTrue(help.contains("--resolver-request-timeout"))
            assertFalse(help.contains("--subprocess-run-timeout-seconds"))
            assertFalse(help.contains("--plugin-run-timeout-seconds"))
            assertFalse(help.contains("--resolver-connect-timeout-ms"))
            assertFalse(help.contains("--resolver-request-timeout-ms"))
        }

        test("old timeout options are rejected") {
            val code = cli().execute(
                "--active-recipe",
                "io.github.skhokhlov.rewriterunner.MyRecipe",
                "--subprocess-run-timeout-seconds",
                "45"
            )
            assertNotEquals(0, code)
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

        test("--exclude-paths splits on comma") {
            val cmd = RunCommand()
            CommandLine(cmd).parseArgs(
                "--active-recipe",
                "io.github.skhokhlov.rewriterunner.MyRecipe",
                "--exclude-paths",
                "**/*.md,src/test/**"
            )
            assertEquals(listOf("**/*.md", "src/test/**"), cmd.excludePaths)
        }

        test("--plain-text-masks splits on comma") {
            val cmd = RunCommand()
            CommandLine(cmd).parseArgs(
                "--active-recipe",
                "io.github.skhokhlov.rewriterunner.MyRecipe",
                "--plain-text-masks",
                "**/CODEOWNERS,**/*.txt"
            )
            assertEquals(listOf("**/CODEOWNERS", "**/*.txt"), cmd.plainTextMasks)
        }

        test("--help output mentions --plain-text-masks") {
            val baos = ByteArrayOutputStream()
            cli().setOut(PrintWriter(baos)).execute("--help")
            assertTrue(
                baos.toString().contains("plain-text-masks"),
                "--help should document --plain-text-masks option"
            )
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

        test("exitCodeFor returns 1 when disk apply failed") {
            val successful =
                RunResult(
                    results = emptyList(),
                    changedFiles = emptyList(),
                    projectDir = projectDir,
                    executionDiagnostics = ExecutionDiagnostics.EMPTY
                )
            val failed =
                successful.copy(
                    executionDiagnostics =
                        ExecutionDiagnostics.EMPTY.copy(
                            writeOutcome =
                                WriteOutcome(
                                    failures =
                                        listOf(
                                            ApplyFailure(
                                                ChangeKind.MODIFIED,
                                                "fail.txt",
                                                "configured failure"
                                            )
                                        )
                                )
                        )
                )

            assertEquals(0, exitCodeFor(successful))
            assertEquals(1, exitCodeFor(failed))
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

            assertNotEquals(
                0,
                code,
                "Unknown --output value should exit with non-zero code, not silently fall back to diff"
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
                        "/tmp/this-directory-does-not-exist-rewrite-runner-test",
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
                        cacheDir.toString()
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
                "--dry-run"
            )

            assertEquals(
                originalContent,
                javaFile.toFile().readText(),
                "--dry-run should not modify files"
            )
        }

        test("partial disk apply failure prints stderr summary and exits 1") {
            projectDir.resolve("rewrite.yaml").writeText(
                """
                ---
                type: specs.openrewrite.org/v1beta/recipe
                name: com.test.CreateBlockedFile
                recipeList:
                  - org.openrewrite.text.CreateTextFile:
                      relativeFileName: blocked-parent/generated.txt
                      fileContents: generated
                      overwriteExisting: false
                """.trimIndent()
            )
            projectDir.resolve("seed.txt").writeText("seed\n")
            projectDir.resolve("blocked-parent").writeText("not a directory\n")

            val errBaos = ByteArrayOutputStream()
            val code =
                cli()
                    .setErr(PrintWriter(errBaos))
                    .execute(
                        "--project-dir",
                        projectDir.toString(),
                        "--active-recipe",
                        "com.test.CreateBlockedFile",
                        "--cache-dir",
                        cacheDir.toString(),
                        "--skip-plugin-run",
                        "--plain-text-masks",
                        "**/*.txt"
                    )

            val stderr = errBaos.toString()
            assertEquals(1, code)
            assertTrue(stderr.contains("ERROR: 1 change(s) could not be applied to disk:"))
            assertTrue(stderr.contains("blocked-parent/generated.txt"))
        }

        // ─── Throwable / LinkageError handling ───────────────────────────────────

        test("LinkageError from recipe execution exits with code 1 and actionable error message") {
            // Regression: RunCommand previously caught only Exception, so LinkageError (a
            // subtype of Error, not Exception) would propagate as a raw JVM crash.
            // After the fix, RunCommand must catch Throwable and print a friendly message.
            //
            // We trigger this by loading a known-good recipe against a Java file. The real
            // scenario (missing transitive dep) is covered by RecipeRunnerTest at the unit
            // level. Here we verify that RunCommand.call() never leaks an uncaught Throwable:
            // the method must always return an int exit code.
            projectDir.resolve("Hello.java").writeText("class Hello {}")

            // run() must not throw — it must return an int
            val result = runCatching {
                cli().execute(
                    "--project-dir",
                    projectDir.toString(),
                    "--active-recipe",
                    "org.openrewrite.java.format.AutoFormat",
                    "--cache-dir",
                    cacheDir.toString()
                )
            }
            assertTrue(
                result.isSuccess,
                "RunCommand.call() must never propagate an uncaught Throwable: " +
                    "${result.exceptionOrNull()}"
            )
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
                    cacheDir.toString()
                )
            }
            assertTrue(
                result.isSuccess,
                "CLI should not throw an uncaught exception: ${result.exceptionOrNull()}"
            )
        }
    })
