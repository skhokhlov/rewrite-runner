package io.github.skhokhlov.rewriterunner

import io.github.skhokhlov.rewriterunner.apply.ChangeKind
import io.github.skhokhlov.rewriterunner.apply.InMemoryChangeWriter
import io.kotest.core.spec.style.FunSpec
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals

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
