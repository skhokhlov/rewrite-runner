package io.github.skhokhlov.rewriterunner.execution

import io.github.skhokhlov.rewriterunner.ExecutionMode
import io.github.skhokhlov.rewriterunner.ExecutorPhase
import io.github.skhokhlov.rewriterunner.RewriteRunner
import io.github.skhokhlov.rewriterunner.lst.FailingLanguageProvider
import io.github.skhokhlov.rewriterunner.lst.ParserFileSelection
import io.github.skhokhlov.rewriterunner.lst.TestLanguageProvider
import io.github.skhokhlov.rewriterunner.lst.TestSemanticLanguageProvider
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@Tags("worker")
class CustomParserWorkerTest :
    FunSpec({
        var dir = Path.of("")
        beforeEach { dir = Files.createTempDirectory("custom-parser-worker-") }
        afterEach { dir.toFile().deleteRecursively() }

        val recipe = """
        type: specs.openrewrite.org/v1beta/recipe
        name: example.CustomLanguageMigration
        recipeList:
          - org.openrewrite.text.FindAndReplace:
              find: BEFORE
              replace: AFTER
        """.trimIndent()
        fun builder() = RewriteRunner.builder().projectDir(dir).cacheDir(dir.resolve("cache"))
            .activeRecipe(
                "example.CustomLanguageMigration"
            ).rewriteConfigContent(recipe).skipPluginRun(true)

        for (mode in ExecutionMode.entries) {
            test("semantic recipes operate on custom file types in $mode") {
                val file = dir.resolve("example.semantic").also {
                    it.writeText("""{"old": true}""")
                }
                val result = builder().executionMode(mode)
                    .activeRecipe("example.SemanticMigration")
                    .rewriteConfigContent(
                        """
                    type: specs.openrewrite.org/v1beta/recipe
                    name: example.SemanticMigration
                    recipeList:
                      - org.openrewrite.json.ChangeKey:
                          oldKeyPath: $.old
                          newKey: '"renamed"'
                        """.trimIndent()
                    )
                    .configure {
                        it.registerParser("semantic", TestSemanticLanguageProvider::class.java)
                    }
                    .build().run()
                assertTrue(file.readText().contains("\"renamed\""), file.readText())
                assertEquals(1, result.changeCount)
                assertTrue(result.executionDiagnostics.parseFailures.isEmpty())
            }
            for (dryRun in listOf(true, false)) {
                test("custom provider and settings execute in $mode with dryRun=$dryRun") {
                    val file = dir.resolve("example.mylang").also { it.writeText("BEFORE\n") }
                    dir.resolve("ordinary.properties").writeText("key=BEFORE\n")
                    val result = builder().executionMode(mode).dryRun(dryRun).configure {
                        it.registerParser(
                            "my-language",
                            TestLanguageProvider::class.java,
                            options = mapOf("marker" to "settings arrived in the executor")
                        )
                    }.build().run()
                    assertEquals(
                        "settings arrived in the executor",
                        dir.resolve("provider.log").readText()
                    )
                    assertEquals(if (dryRun) "BEFORE\n" else "AFTER\n", file.readText())
                    assertEquals(
                        setOf(Path.of("example.mylang"), Path.of("ordinary.properties")),
                        (
                            result.rawDiffs.keys + result.results.map {
                                it.after!!.sourcePath
                            }
                            ).toSet()
                    )
                    assertTrue(result.executionDiagnostics.parseFailures.isEmpty())
                    assertTrue(
                        result.executionDiagnostics.executorAttempts.any {
                            it.phase ==
                                ExecutorPhase.FULL_FALLBACK
                        }
                    )
                    assertEquals(mode == ExecutionMode.IN_PROCESS, result.results.isNotEmpty())
                }
            }
        }
        test(
            "missing worker provider fails with registration context and does not retry in process"
        ) {
            dir.resolve("example.mylang").writeText("BEFORE\n")
            val failure = assertFailsWith<ForkedWorkerException> {
                builder().configure {
                    it.parsers += io.github.skhokhlov.rewriterunner.lst.ParserRegistration(
                        "missing-language",
                        "missing.Provider",
                        ParserFileSelection(extensions = setOf(".mylang"))
                    )
                }.build().run()
            }
            assertTrue(failure.message!!.contains("missing-language"), failure.message)
            assertEquals("BEFORE\n", dir.resolve("example.mylang").readText())
        }
        test("provider construction failure is terminal in full fallback") {
            dir.resolve("example.mylang").writeText("BEFORE\n")
            val failure = assertFailsWith<ForkedWorkerException> {
                builder().configure {
                    it.registerParser(
                        "broken",
                        FailingLanguageProvider::class.java,
                        ParserFileSelection(extensions = setOf(".mylang"))
                    )
                }.build().run()
            }
            assertTrue(failure.message!!.contains("broken"), failure.message)
        }
    })
