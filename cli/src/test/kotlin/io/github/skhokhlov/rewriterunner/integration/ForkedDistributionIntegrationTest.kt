package io.github.skhokhlov.rewriterunner.integration

import io.kotest.core.spec.style.FunSpec
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Exercises the release-shaped fat JAR rather than an in-process Picocli helper. */
class ForkedDistributionIntegrationTest :
    FunSpec({
        var projectDir: Path = Path.of("")

        beforeEach {
            projectDir = Files.createTempDirectory("forked-distribution-project-")
            projectDir.resolve("rewrite.yaml").writeText(
                """
                ---
                type: specs.openrewrite.org/v1beta/recipe
                name: com.example.ReplaceOld
                recipeList:
                  - org.openrewrite.text.FindAndReplace:
                      find: old
                      replace: new
                      regex: false
                      filePattern: "**/*.txt"
                      plaintextOnly: true
                """.trimIndent()
            )
            projectDir.resolve("sample.txt").writeText("old\n")
        }

        afterEach { projectDir.toFile().deleteRecursively() }

        test("fat JAR starts a separate worker and reports its observed heap") {
            val fatJar = Path.of(System.getProperty("rewriterunner.test.fatJar"))
            assertTrue(Files.isRegularFile(fatJar), "Missing fat JAR at $fatJar")
            val java =
                Path.of(
                    System.getProperty("java.home"),
                    "bin",
                    if (System.getProperty(
                            "os.name"
                        ).contains("win", ignoreCase = true)
                    ) {
                        "java.exe"
                    } else {
                        "java"
                    }
                )
            val process =
                ProcessBuilder(
                    java.toString(),
                    "-jar",
                    fatJar.toString(),
                    "--project-dir=$projectDir",
                    "--active-recipe=com.example.ReplaceOld",
                    "--skip-plugin-run",
                    "--plain-text-masks=**/*.txt",
                    "--lst-worker-jvm-arg=-Xmx128m",
                    "--output=report"
                )
                    .redirectErrorStream(true)
                    .start()
            val completed = process.waitFor(90, TimeUnit.SECONDS)
            val output = process.inputStream.bufferedReader().readText()

            assertTrue(completed, "fat JAR did not complete: $output")
            assertEquals(0, process.exitValue(), output)
            assertEquals("new\n", projectDir.resolve("sample.txt").readText())

            val report = projectDir.resolve("openrewrite-report.json").readText()
            val worker = Regex("""(?s)\{[^{}]*"executor"\s*:\s*"LST_WORKER"[^{}]*}""")
                .find(report)
                ?.value
            assertNotNull(worker, report)
            val workerPid = numberField(worker, "processId")
            assertTrue(workerPid > 0)
            assertTrue(workerPid != ProcessHandle.current().pid())
            assertEquals(
                128L * 1024L * 1024L,
                numberField(worker, "observedMaximumHeapBytes")
            )
            assertFalse(ProcessHandle.of(workerPid).map { it.isAlive }.orElse(false))
        }
    })

private fun numberField(jsonObject: String, name: String): Long = Regex("\"$name\"\\s*:\\s*(\\d+)")
    .find(jsonObject)
    ?.groupValues
    ?.get(1)
    ?.toLong()
    ?: error("Missing numeric $name in $jsonObject")
