package io.github.skhokhlov.rewriterunner

import io.github.skhokhlov.rewriterunner.apply.ChangeKind
import io.github.skhokhlov.rewriterunner.apply.InMemoryChangeWriter
import io.kotest.core.spec.style.FunSpec
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RewriteRunnerChangeWriterTest :
    FunSpec({
        var projectDir: Path = Path.of("")
        var cacheDir: Path = Path.of("")

        beforeEach {
            projectDir = Files.createTempDirectory("rrcwt-project-")
            cacheDir = Files.createTempDirectory("rrcwt-cache-")
        }

        afterEach {
            projectDir.toFile().deleteRecursively()
            cacheDir.toFile().deleteRecursively()
        }

        for (mode in ExecutionMode.entries) {
            for (dryRun in listOf(false, true)) {
                test("rename recipe honors dryRun=$dryRun in $mode execution") {
                    projectDir.resolve("old.txt").writeText("old\n")
                    projectDir.resolve("rewrite.yaml").writeText(
                        """
                        ---
                        type: specs.openrewrite.org/v1beta/recipe
                        name: com.test.RenameText
                        recipeList:
                          - org.openrewrite.RenameFile:
                              fileMatcher: old.txt
                              fileName: new.txt
                          - org.openrewrite.text.FindAndReplace:
                              find: old
                              replace: new
                              plaintextOnly: true
                        """.trimIndent()
                    )

                    val result = RewriteRunner.builder()
                        .projectDir(projectDir)
                        .activeRecipe("com.test.RenameText")
                        .cacheDir(cacheDir)
                        .skipPluginRun(true)
                        .executionMode(mode)
                        .plainTextMasks(listOf("**/*.txt"))
                        .dryRun(dryRun)
                        .build()
                        .run()

                    assertTrue(result.hasChanges)
                    val outcome = result.executionDiagnostics.writeOutcome
                    assertTrue(outcome.failures.isEmpty())
                    if (dryRun) {
                        assertEquals("old\n", projectDir.resolve("old.txt").readText())
                        assertFalse(projectDir.resolve("new.txt").exists())
                        assertTrue(result.changedFiles.isEmpty())
                        assertTrue(outcome.successes.isEmpty())
                    } else {
                        assertFalse(projectDir.resolve("old.txt").exists())
                        assertEquals("new\n", projectDir.resolve("new.txt").readText())
                        assertEquals(listOf(projectDir.resolve("new.txt")), result.changedFiles)
                        assertEquals(listOf("new.txt"), outcome.successes.map { it.path })
                        assertEquals(ChangeKind.MODIFIED, outcome.successes.single().kind)
                    }
                }
            }
        }

        test("injected change writer reports partial failures and changedFiles only successes") {
            projectDir.resolve("rewrite.yaml").writeText(
                """
                ---
                type: specs.openrewrite.org/v1beta/recipe
                name: com.test.ModifyAndDelete
                recipeList:
                  - org.openrewrite.text.FindAndReplace:
                      find: old
                      replace: new
                      regex: false
                      filePattern: "**/*.txt"
                      plaintextOnly: true
                  - org.openrewrite.DeleteSourceFiles:
                      filePattern: "**/*.properties"
                """.trimIndent()
            )
            projectDir.resolve("ok.txt").writeText("old\n")
            projectDir.resolve("fail.txt").writeText("old\n")
            projectDir.resolve("config").createDirectories()
            projectDir.resolve("config/delete.properties").writeText("key=value\n")
            val writer = InMemoryChangeWriter(failPaths = setOf("fail.txt"))

            val result =
                RewriteRunner.builder()
                    .projectDir(projectDir)
                    .activeRecipe("com.test.ModifyAndDelete")
                    .cacheDir(cacheDir)
                    .skipPluginRun(true)
                    .executionMode(ExecutionMode.IN_PROCESS)
                    .plainTextMasks(listOf("**/*.txt"))
                    .changeWriter(writer)
                    .build()
                    .run()

            val outcome = result.executionDiagnostics.writeOutcome
            assertEquals(listOf(projectDir.resolve("ok.txt")), result.changedFiles)
            assertEquals(listOf("fail.txt"), outcome.failures.map { it.path })
            assertEquals(ChangeKind.MODIFIED, outcome.failures.single().kind)
            assertEquals(
                setOf(
                    "ok.txt" to ChangeKind.MODIFIED,
                    "config/delete.properties" to ChangeKind.DELETED
                ),
                outcome.successes.map { it.path to it.kind }.toSet()
            )
        }
    })
