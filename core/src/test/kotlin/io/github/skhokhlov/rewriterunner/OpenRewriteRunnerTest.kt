package io.github.skhokhlov.rewriterunner

import io.kotest.core.spec.style.FunSpec
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenRewriteRunnerTest :
    FunSpec({
        var projectDir: Path = Path.of("")
        var cacheDir: Path = Path.of("")

        beforeEach {
            projectDir = Files.createTempDirectory("orrt-project-")
            cacheDir = Files.createTempDirectory("orrt-cache-")
        }

        afterEach {
            projectDir.toFile().deleteRecursively()
            cacheDir.toFile().deleteRecursively()
        }

        /**
         * Builds a rewrite.yaml that uses DeleteSourceFiles to remove all .properties files,
         * then runs OpenRewriteRunner against a project containing one such file.
         */
        fun runDeletePropertiesRecipe(dryRun: Boolean = false) {
            projectDir.resolve("rewrite.yaml").writeText(
                """
                ---
                type: specs.openrewrite.org/v1beta/recipe
                name: com.test.DeleteProperties
                recipeList:
                  - org.openrewrite.DeleteSourceFiles:
                      filePattern: "**/*.properties"
                """.trimIndent()
            )

            OpenRewriteRunner.builder()
                .projectDir(projectDir)
                .activeRecipe("com.test.DeleteProperties")
                .cacheDir(cacheDir)
                .dryRun(dryRun)
                .build()
                .run()
        }

        // ─── File deletion ────────────────────────────────────────────────────────

        test("recipe-deleted files are removed from disk") {
            // Put the file in a subdirectory so "**/*.properties" glob matches unambiguously
            projectDir.resolve("config").toFile().mkdirs()
            val propsFile = projectDir.resolve("config/app.properties")
            propsFile.writeText("key=value\n")
            assertTrue(propsFile.exists(), "File should exist before run")

            runDeletePropertiesRecipe()

            assertFalse(propsFile.exists(), "Recipe-deleted file should be removed from disk")
        }

        test("dry-run does not delete recipe-deleted files") {
            projectDir.resolve("config").toFile().mkdirs()
            val propsFile = projectDir.resolve("config/app.properties")
            propsFile.writeText("key=value\n")

            runDeletePropertiesRecipe(dryRun = true)

            assertTrue(propsFile.exists(), "Dry-run must not delete files from disk")
        }
    })
