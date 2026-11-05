package io.github.skhokhlov.rewriterunner.integration

import io.github.skhokhlov.rewriterunner.ExecutorPhase
import io.github.skhokhlov.rewriterunner.RewriteRunner
import io.github.skhokhlov.rewriterunner.UsedExecutionStage
import io.github.skhokhlov.rewriterunner.lst.ParserFileSelection
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Real plugin exclusions must partition custom files even inside Maven/Gradle subprojects. */
@Tags("real-plugin")
class CustomParserRealPluginTest :
    FunSpec({
        val windows = System.getProperty("os.name").contains("win", true)
        for (scenario in listOf(
            PluginScenarios.gradleMultiProject,
            PluginScenarios.mavenMultiModule
        )) {
            test(
                "real ${scenario.name}: custom files run exactly once through specialized ownership"
            )
                .config(enabled = !windows) {
                    requireMavenCentralReachable()
                    val dir = Files.createTempDirectory("custom-real-plugin-")
                    val cache = Files.createTempDirectory("custom-real-cache-")
                    try {
                        scenario.setUpProject(dir)
                        installRealWrapper(dir)
                        val customDir = dir.resolve("module-a/spec").createDirectories()
                        customDir.resolve("sample.language").writeText("BEFORE\n")
                        customDir.resolve("schema.json").writeText("""{"key":"BEFORE"}""")
                        val pluginFile = dir.resolve("module-b/config.json")
                        pluginFile.writeText("""{"key":"BEFORE"}""")
                        dir.writeFindAndReplaceRecipe(find = "BEFORE", replace = "BEFORE_AFTER")
                        val result = RewriteRunner.builder().projectDir(dir).cacheDir(cache)
                            .activeRecipe("com.example.integration.FindAndReplace")
                            .rewriteConfigContent(dir.resolve("rewrite.yaml").readText())
                            .plainTextMasks(listOf("**/*.language"))
                            .configure {
                                it.registerParser(
                                    "language",
                                    IntegrationLanguageProvider::class.java
                                )
                                it.registerParser(
                                    "schema",
                                    IntegrationLanguageProvider::class.java,
                                    ParserFileSelection(
                                        pathGlobs = listOf("module-a/spec/schema.json")
                                    ),
                                    overrides = setOf("json")
                                )
                            }.build().run()
                        assertEquals(
                            UsedExecutionStage.PLUGIN,
                            result.executionDiagnostics.stageUsed
                        )
                        assertTrue(result.rawDiffs.containsKey(Path.of("module-b/config.json")))
                        assertEquals("""{"key":"BEFORE_AFTER"}""", pluginFile.readText())
                        assertEquals(
                            "BEFORE_AFTER\n",
                            customDir.resolve("sample.language").readText()
                        )
                        assertEquals(
                            """{"key":"BEFORE_AFTER"}""",
                            customDir.resolve("schema.json").readText()
                        )
                        assertEquals(2, result.executionDiagnostics.parsedFileCount)
                        assertTrue(
                            result.executionDiagnostics.executorAttempts.any {
                                it.phase == ExecutorPhase.SPECIALIZED_ONLY
                            }
                        )
                        assertTrue(
                            result.executionDiagnostics.executorAttempts.none {
                                it.phase == ExecutorPhase.FULL_FALLBACK
                            }
                        )
                    } finally {
                        dir.toFile().deleteRecursively()
                        cache.toFile().deleteRecursively()
                    }
                }
        }
    })
