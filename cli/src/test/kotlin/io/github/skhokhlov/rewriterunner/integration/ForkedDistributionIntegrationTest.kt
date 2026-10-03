package io.github.skhokhlov.rewriterunner.integration

import io.kotest.core.spec.style.FunSpec
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
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

        test("fat JAR reports a corrupt cached recipe through the forked worker") {
            val cache = projectDir.resolve("cache")
            val artifactDir = Files.createDirectories(
                cache.resolve("repository/com/example/recipes/1.0")
            )
            artifactDir.resolve("recipes-1.0.pom").writeText(
                """
                <project><modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId><artifactId>recipes</artifactId>
                <version>1.0</version></project>
                """.trimIndent()
            )
            val jar = artifactDir.resolve("recipes-1.0.jar")
            JarOutputStream(Files.newOutputStream(jar)).use { out ->
                out.putNextEntry(JarEntry("META-INF/rewrite/recipes.yml"))
                out.write(projectDir.resolve("rewrite.yaml").readText().toByteArray())
                out.closeEntry()
            }
            Files.delete(projectDir.resolve("rewrite.yaml"))
            val javaName = if (System.getProperty(
                    "os.name"
                ).contains("win", true)
            ) {
                "java.exe"
            } else {
                "java"
            }
            val java = Path.of(System.getProperty("java.home"), "bin", javaName)
            val fatJar = Path.of(System.getProperty("rewriterunner.test.fatJar"))

            fun runCli(logName: String): Pair<Int, String> {
                val log = projectDir.resolve(logName)
                val process = ProcessBuilder(
                    java.toString(), "-jar", fatJar.toString(),
                    "--project-dir=$projectDir",
                    "--active-recipe=com.example.ReplaceOld",
                    "--recipe-artifact=com.example:recipes:1.0",
                    "--cache-dir=$cache",
                    "--no-maven-central",
                    "--skip-plugin-run",
                    "--execution-mode=forked",
                    "--lst-worker-timeout=30s",
                    "--dry-run",
                    "--plain-text-masks=**/*.txt"
                ).redirectErrorStream(true).redirectOutput(log.toFile()).start()
                try {
                    assertTrue(process.waitFor(60, TimeUnit.SECONDS), "CLI timed out")
                    return process.exitValue() to log.readText()
                } finally {
                    if (process.isAlive) {
                        process.descendants().forEach { it.destroyForcibly() }
                        process.destroyForcibly()
                        process.waitFor(5, TimeUnit.SECONDS)
                    }
                }
            }

            val (goodExit, goodOutput) = runCli("good.log")
            assertEquals(0, goodExit, goodOutput)
            assertTrue(goodOutput.contains("+new"), goodOutput)
            Files.write(jar, Files.readAllBytes(jar).copyOf(64))
            val (badExit, badOutput) = runCli("bad.log")
            assertTrue(badExit != 0, badOutput)
            assertTrue(badOutput.contains("Recipe classpath entry is unusable"), badOutput)
            assertTrue(badOutput.contains("recipes-1.0.jar"), badOutput)
            assertFalse(badOutput.contains("Recipe 'com.example.ReplaceOld' not found"), badOutput)
            assertEquals("old\n", projectDir.resolve("sample.txt").readText())
        }

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
