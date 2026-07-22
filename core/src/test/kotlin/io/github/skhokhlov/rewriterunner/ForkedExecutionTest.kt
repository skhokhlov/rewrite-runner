package io.github.skhokhlov.rewriterunner

import io.kotest.core.spec.style.FunSpec
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ForkedExecutionTest :
    FunSpec({
        var projectDir: Path = Path.of("")
        var cacheDir: Path = Path.of("")

        beforeEach {
            projectDir = Files.createTempDirectory("forked-runner-project-")
            cacheDir = Files.createTempDirectory("forked-runner-cache-")
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

        afterEach {
            projectDir.toFile().deleteRecursively()
            cacheDir.toFile().deleteRecursively()
        }

        test("forked mode is the default and returns transportable diffs instead of Results") {
            val result =
                RewriteRunner.builder()
                    .projectDir(projectDir)
                    .activeRecipe("com.example.ReplaceOld")
                    .cacheDir(cacheDir)
                    .skipPluginRun(true)
                    .plainTextMasks(listOf("**/*.txt"))
                    .build()
                    .run()

            val worker = result.executionDiagnostics.executorAttempts.single {
                it.executor == LogicalExecutor.LST_WORKER
            }
            assertTrue(result.results.isEmpty())
            assertTrue(result.rawDiffs.containsKey(Path.of("sample.txt")))
            assertEquals("new\n", projectDir.resolve("sample.txt").readText())
            assertNotEquals(ProcessHandle.current().pid(), worker.processId)
            assertEquals(ExecutorOutcome.SUCCESS, worker.outcome)
        }

        test("worker observes an explicit JVM heap argument") {
            val result =
                RewriteRunner.builder()
                    .projectDir(projectDir)
                    .activeRecipe("com.example.ReplaceOld")
                    .cacheDir(cacheDir)
                    .skipPluginRun(true)
                    .plainTextMasks(listOf("**/*.txt"))
                    .lstWorkerJvmArgs(listOf("-Xmx128m"))
                    .build()
                    .run()

            val worker = result.executionDiagnostics.executorAttempts.single {
                it.executor == LogicalExecutor.LST_WORKER
            }
            val expected = 128L * 1024L * 1024L
            assertEquals(expected, worker.requestedMaximumHeapBytes)
            assertTrue(
                worker.observedMaximumHeapBytes!! in expected..(expected + 2L * 1024L * 1024L),
                "worker reported ${worker.observedMaximumHeapBytes} rather than an approximately 128 MiB heap"
            )
        }

        test("forked and in-process execution produce matching changed paths and diffs") {
            val inProcessProjectDir = Files.createTempDirectory("in-process-runner-project-")
            val inProcessCacheDir = Files.createTempDirectory("in-process-runner-cache-")
            try {
                configureProject(inProcessProjectDir)
                val forked = runner(projectDir, cacheDir, ExecutionMode.FORKED).run()
                val inProcess =
                    runner(inProcessProjectDir, inProcessCacheDir, ExecutionMode.IN_PROCESS).run()

                assertTrue(forked.results.isEmpty())
                assertTrue(inProcess.results.isNotEmpty())
                assertEquals(normalizedChangedPaths(inProcess), normalizedChangedPaths(forked))
                assertEquals(inProcessDiffs(inProcess), forked.rawDiffs.normalizePaths())
            } finally {
                inProcessProjectDir.toFile().deleteRecursively()
                inProcessCacheDir.toFile().deleteRecursively()
            }
        }

        test("custom change writers are rejected before a forked run starts") {
            val error = kotlin.test.assertFailsWith<IllegalArgumentException> {
                RewriteRunner.builder()
                    .projectDir(projectDir)
                    .activeRecipe("com.example.ReplaceOld")
                    .changeWriter(object : io.github.skhokhlov.rewriterunner.apply.ChangeWriter {
                        override fun apply(results: List<org.openrewrite.Result>) =
                            io.github.skhokhlov.rewriterunner.apply.WriteOutcome.EMPTY
                    })
                    .build()
                    .run()
            }

            assertTrue(error.message!!.contains("executionMode(IN_PROCESS)"))
            assertFalse(projectDir.resolve("sample.txt").readText().contains("new"))
        }
    })

private fun configureProject(projectDir: Path) {
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

private fun runner(projectDir: Path, cacheDir: Path, mode: ExecutionMode): RewriteRunner =
    RewriteRunner.builder()
        .projectDir(projectDir)
        .activeRecipe("com.example.ReplaceOld")
        .cacheDir(cacheDir)
        .skipPluginRun(true)
        .plainTextMasks(listOf("**/*.txt"))
        .executionMode(mode)
        .build()

private fun normalizedChangedPaths(result: RunResult): Set<String> = result.changedFiles
    .map(result.projectDir::relativize)
    .map { it.toString().replace('\\', '/') }
    .toSet()

private fun inProcessDiffs(result: RunResult): Map<String, String> =
    result.results.associate { rewriteResult ->
        val path = requireNotNull(rewriteResult.after ?: rewriteResult.before).sourcePath
        path.toString().replace('\\', '/') to rewriteResult.diff()
    }

private fun Map<Path, String>.normalizePaths(): Map<String, String> =
    entries.associate { (path, diff) ->
        path.toString().replace('\\', '/') to diff
    }
