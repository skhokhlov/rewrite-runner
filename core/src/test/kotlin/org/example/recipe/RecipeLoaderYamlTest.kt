package org.example.recipe

import io.kotest.core.spec.style.FunSpec
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Tests the rewrite.yaml loading path in [RecipeLoader], which was previously uncovered.
 */
class RecipeLoaderYamlTest :
    FunSpec({
        var tempDir: Path = Path.of("")

        beforeEach { tempDir = Files.createTempDirectory("rlyt-") }

        afterEach { tempDir.toFile().deleteRecursively() }

        test("load reads composite recipe from rewrite_yaml when present") {
            val yamlFile = tempDir.resolve("rewrite.yaml")
            yamlFile.writeText(
                """
                ---
                type: specs.openrewrite.org/v1beta/recipe
                name: com.example.test.FindTxtFiles
                recipeList:
                  - org.openrewrite.FindSourceFiles:
                      filePattern: "**/*.txt"
                """.trimIndent()
            )

            val recipe =
                RecipeLoader().load(
                    recipeJars = emptyList(),
                    activeRecipeName = "com.example.test.FindTxtFiles",
                    rewriteYaml = yamlFile
                )

            assertNotNull(recipe)
            assertEquals("com.example.test.FindTxtFiles", recipe.name)
        }

        test("load skips rewrite_yaml when path is null") {
            // Built-in recipe loaded with null yaml path — should work fine
            val recipe =
                RecipeLoader().load(
                    recipeJars = emptyList(),
                    activeRecipeName = "org.openrewrite.FindSourceFiles",
                    rewriteYaml = null
                )
            assertNotNull(recipe)
        }

        test("load skips rewrite_yaml when file does not exist") {
            val nonExistentYaml = tempDir.resolve("nonexistent-rewrite.yaml")

            // File does not exist; RecipeLoader should silently skip it and
            // fall back to scanning the classpath for the built-in recipe.
            val recipe =
                RecipeLoader().load(
                    recipeJars = emptyList(),
                    activeRecipeName = "org.openrewrite.FindSourceFiles",
                    rewriteYaml = nonExistentYaml
                )
            assertNotNull(recipe)
        }

        test("load composite recipe from yaml is executable") {
            val yamlFile = tempDir.resolve("rewrite.yaml")
            yamlFile.writeText(
                """
                ---
                type: specs.openrewrite.org/v1beta/recipe
                name: com.example.test.DeleteProperties
                recipeList:
                  - org.openrewrite.DeleteSourceFiles:
                      filePattern: "**/*.properties"
                """.trimIndent()
            )

            val recipe =
                RecipeLoader().load(
                    recipeJars = emptyList(),
                    activeRecipeName = "com.example.test.DeleteProperties",
                    rewriteYaml = yamlFile
                )

            // Recipe should be non-null and have at least one child in its list
            assertNotNull(recipe)
            assertEquals("com.example.test.DeleteProperties", recipe.name)
        }
    })
