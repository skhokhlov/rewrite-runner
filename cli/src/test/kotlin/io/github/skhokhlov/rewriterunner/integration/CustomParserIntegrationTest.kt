package io.github.skhokhlov.rewriterunner.integration

import io.github.skhokhlov.rewriterunner.ExecutionMode
import io.github.skhokhlov.rewriterunner.ExecutorOutcome
import io.github.skhokhlov.rewriterunner.ExecutorPhase
import io.github.skhokhlov.rewriterunner.LogicalExecutor
import io.github.skhokhlov.rewriterunner.RewriteRunner
import io.github.skhokhlov.rewriterunner.UsedExecutionStage
import io.github.skhokhlov.rewriterunner.lst.ParserContext
import io.github.skhokhlov.rewriterunner.lst.ParserFileSelection
import io.github.skhokhlov.rewriterunner.lst.ParserProvider
import io.github.skhokhlov.rewriterunner.lst.ParserRegistration
import io.github.skhokhlov.rewriterunner.lst.SpecializedOwnership
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.openrewrite.Parser
import org.openrewrite.text.PlainTextParser

class IntegrationLanguageProvider : ParserProvider {
    override fun defaultSelection() = ParserFileSelection(extensions = setOf(".language"))
    override fun create(context: ParserContext, options: Map<String, String>): Parser =
        PlainTextParser.builder().build()
}

private val customParserWindows = System.getProperty("os.name").contains("win", true)

@Tags("integration")
class CustomParserIntegrationTest :
    FunSpec({
        var dir = Path.of("")
        beforeEach { dir = Files.createTempDirectory("custom-stage0-") }
        afterEach { dir.toFile().deleteRecursively() }
        val recipe = """
        type: specs.openrewrite.org/v1beta/recipe
        name: example.Migration
        recipeList:
          - org.openrewrite.text.FindAndReplace:
              find: BEFORE
              replace: AFTER
        """.trimIndent()
        fun builder() = RewriteRunner.builder().projectDir(dir).cacheDir(dir.resolve("cache"))
            .activeRecipe("example.Migration").rewriteConfigContent(recipe)
            .configure { it.registerParser("language", IntegrationLanguageProvider::class.java) }
        fun pom(directory: Path) {
            directory.createDirectories()
            directory.resolve("pom.xml").writeText(
                """
            <project><modelVersion>4.0.0</modelVersion><groupId>example</groupId>
            <artifactId>sample</artifactId><version>1</version></project>
                """.trimIndent()
            )
        }
        fun plugin(tool: String, expectSchema: Boolean = true) {
            dir.resolve("plugin.properties").writeText("key=old\n")
            if (tool == "Gradle") {
                dir.resolve("settings.gradle.kts").writeText("rootProject.name = \"test\"\n")
                dir.resolve("build.gradle.kts").writeText("")
                dir.writeFakeGradlewWithExclusionChecks(
                    "plugin.properties",
                    "key=old",
                    "key=plugin",
                    "key=plugin\n",
                    SpecializedOwnership.stage0ExcludeGlobs + listOf("sample.language") +
                        if (expectSchema) listOf("schema.json") else emptyList()
                )
            } else {
                pom(dir)
                dir.writeFakeMvnwSimple(
                    "plugin.properties",
                    "key=old",
                    "key=plugin",
                    "key=plugin\n"
                )
                val wrapper = dir.resolve("mvnw")
                wrapper.writeText(
                    wrapper.readText().replace(
                        "goal=\"\"",
                        """
                case " ${'$'}* " in
                  *sample.language*schema.json*) ;;
                  *) echo "missing custom exclusions" >&2; exit 2 ;;
                esac
                goal=""
                        """.trimIndent()
                    )
                )
            }
        }
        for (tool in listOf("Gradle", "Maven")) {
            for (mode in ExecutionMode.entries) {
                for (dryRun in listOf(false, true)) {
                    test(
                        "$tool Stage 0 merges custom and built-in specialized results in $mode, dryRun=$dryRun"
                    ).config(enabled = !customParserWindows) {
                        dir.resolve("sample.language").writeText("BEFORE\n")
                        dir.resolve(
                            "schema.json"
                        ).writeText("BEFORE custom language, invalid JSON\n")
                        dir.resolve("main.tf").writeText("value = \"BEFORE\"\n")
                        dir.resolve("untouched.properties").writeText("key=BEFORE\n")
                        plugin(tool)
                        val result = builder().executionMode(mode).dryRun(dryRun).configure {
                            it.registerParser(
                                "schema",
                                IntegrationLanguageProvider::class.java,
                                ParserFileSelection(fileNames = setOf("schema.json")),
                                overrides = setOf("json")
                            )
                        }.build().run()
                        assertEquals(
                            UsedExecutionStage.PLUGIN,
                            result.executionDiagnostics.stageUsed
                        )
                        val paths =
                            result.rawDiffs.keys + result.results.map { it.after!!.sourcePath }
                        assertEquals(
                            setOf("plugin.properties", "sample.language", "schema.json", "main.tf"),
                            paths.map {
                                it.toString()
                            }.toSet()
                        )
                        assertEquals(
                            if (dryRun) "BEFORE\n" else "AFTER\n",
                            dir.resolve("sample.language").readText()
                        )
                        assertEquals("key=BEFORE\n", dir.resolve("untouched.properties").readText())
                        assertEquals(3, result.executionDiagnostics.parsedFileCount)
                        assertTrue(result.executionDiagnostics.parseFailures.isEmpty())
                        assertTrue(
                            result.executionDiagnostics.executorAttempts.any {
                                it.phase ==
                                    ExecutorPhase.SPECIALIZED_ONLY
                            }
                        )
                        assertTrue(
                            result.executionDiagnostics.executorAttempts.none {
                                it.phase ==
                                    ExecutorPhase.FULL_FALLBACK
                            }
                        )
                    }
                }
            }
        }
        test(
            "Stage 0 no changes still executes custom parsers"
        ).config(enabled = !customParserWindows) {
            dir.resolve("build.gradle").writeText("")
            dir.resolve("gradlew").also {
                it.writeText("#!/bin/sh\nexit 0\n")
                it.toFile().setExecutable(true)
            }
            dir.resolve("sample.language").writeText("BEFORE\n")
            val result = builder().build().run()
            assertEquals("AFTER\n", dir.resolve("sample.language").readText())
            assertEquals(UsedExecutionStage.PLUGIN, result.executionDiagnostics.stageUsed)
        }
        test(
            "provider failure after Stage 0 preserves plugin changes"
        ).config(enabled = !customParserWindows) {
            dir.resolve("sample.language").writeText("BEFORE\n")
            dir.resolve("schema.json").writeText("{}")
            plugin("Gradle", expectSchema = false)
            val result = builder().configure {
                val index = it.parsers.indexOfFirst { p -> p.id == "language" }
                it.parsers[index] = it.parsers[index].copy(providerClassName = "missing.Provider")
            }.build().run()
            assertEquals("key=plugin\n", dir.resolve("plugin.properties").readText())
            assertEquals("BEFORE\n", dir.resolve("sample.language").readText())
            assertEquals(setOf(Path.of("plugin.properties")), result.rawDiffs.keys)
            assertTrue(
                result.executionDiagnostics.executorAttempts.any {
                    it.phase == ExecutorPhase.SPECIALIZED_ONLY &&
                        it.outcome == ExecutorOutcome.FAILED
                }
            )
        }
        test(
            "no custom candidates avoids starting an LST worker"
        ).config(enabled = !customParserWindows) {
            dir.resolve("build.gradle").writeText("")
            dir.resolve("gradlew").also {
                it.writeText("#!/bin/sh\nexit 0\n")
                it.toFile().setExecutable(true)
            }
            val result = builder().workerCommandFactory {
                error("Worker must not start")
            }.build().run()
            assertEquals(UsedExecutionStage.PLUGIN, result.executionDiagnostics.stageUsed)
            assertTrue(
                result.executionDiagnostics.executorAttempts.none {
                    it.executor ==
                        LogicalExecutor.LST_WORKER
                }
            )
        }
        test("unrepresentable Stage 0 exclusions use full fallback safely").config(
            enabled = !customParserWindows
        ) {
            dir.resolve("build.gradle").writeText("")
            dir.resolve("gradlew").also {
                it.writeText(
                    """
                    #!/bin/sh
                    case "${'$'}1" in
                      rewriteDryRun|rewriteRun) touch plugin-was-run ;;
                    esac
                    exit 1
                    """.trimIndent()
                )
                it.toFile().setExecutable(true)
            }
            dir.resolve("comma,name.language").writeText("BEFORE\n")
            val result = builder().build().run()
            assertEquals("AFTER\n", dir.resolve("comma,name.language").readText())
            assertTrue(!Files.exists(dir.resolve("plugin-was-run")))
            assertTrue(
                result.executionDiagnostics.executorAttempts.any {
                    it.phase == ExecutorPhase.FULL_FALLBACK
                }
            )
        }
        test(
            "ambiguous assignments fail before Stage 0 can modify project files"
        ).config(enabled = !customParserWindows) {
            dir.resolve("schema.json").writeText("BEFORE\n")
            plugin("Gradle")
            assertFailsWith<IllegalArgumentException> {
                builder().configure {
                    it.registerParser(
                        "conflict",
                        IntegrationLanguageProvider::class.java,
                        ParserFileSelection(fileNames = setOf("schema.json"))
                    )
                }.build().run()
            }
            assertEquals("key=old\n", dir.resolve("plugin.properties").readText())
        }
        test(
            "partial Stage 0 keeps collision protection while full fallback includes custom files"
        ).config(enabled = !customParserWindows) {
            val a = dir.resolve("a")
            val b = dir.resolve("b")
            pom(a)
            pom(b)
            a.resolve("plugin.properties").writeText("key=old\n")
            a.writeFakeMvnwSimple("plugin.properties", "key=old", "key=BEFORE", "key=BEFORE\n")
            b.resolve("mvnw").also {
                it.writeText("#!/bin/sh\nexit 1\n")
                it.toFile().setExecutable(true)
            }
            b.resolve("sample.language").writeText("BEFORE\n")
            b.resolve("ordinary.properties").writeText("key=BEFORE\n")
            val result = builder().build().run()
            assertEquals(UsedExecutionStage.PLUGIN, result.executionDiagnostics.stageUsed)
            assertEquals("key=BEFORE\n", a.resolve("plugin.properties").readText())
            assertEquals("AFTER\n", b.resolve("sample.language").readText())
            assertEquals("key=AFTER\n", b.resolve("ordinary.properties").readText())
            assertTrue(
                result.executionDiagnostics.executorAttempts.any {
                    it.phase ==
                        ExecutorPhase.FULL_FALLBACK
                }
            )
        }
    })
