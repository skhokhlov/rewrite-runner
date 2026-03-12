package io.github.skhokhlov.rewriterunner

import io.github.skhokhlov.rewriterunner.config.RepositoryConfig
import io.kotest.core.spec.style.FunSpec
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RewriteRunnerTest :
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
         * then runs RewriteRunner against a project containing one such file.
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

            RewriteRunner.builder()
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

        // ─── excludePaths builder property ───────────────────────────────────────

        test("builder excludePaths skips matching files") {
            projectDir.resolve("excluded").toFile().mkdirs()
            projectDir.resolve("included").toFile().mkdirs()
            val excludedFile = projectDir.resolve("excluded/app.properties")
            val includedFile = projectDir.resolve("included/app.properties")
            excludedFile.writeText("key=value\n")
            includedFile.writeText("key=value\n")

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

            RewriteRunner.builder()
                .projectDir(projectDir)
                .activeRecipe("com.test.DeleteProperties")
                .cacheDir(cacheDir)
                .excludePaths(listOf("excluded/**"))
                .build()
                .run()

            assertTrue(excludedFile.exists(), "File in excluded path should not be deleted")
            assertFalse(includedFile.exists(), "File in included path should be deleted")
        }

        // ─── repositories builder property ───────────────────────────────────────

        test("builder repositories are accepted without error") {
            // Verify that supplying a RepositoryConfig programmatically does not throw.
            // Full resolution behaviour is covered by integration tests.
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

            RewriteRunner.builder()
                .projectDir(projectDir)
                .activeRecipe("com.test.DeleteProperties")
                .cacheDir(cacheDir)
                .repository(RepositoryConfig(url = "https://repo.example.com/maven"))
                .repositories(listOf(RepositoryConfig(url = "https://other.example.com/maven")))
                .build()
                .run()
            // No assertion needed — the test passes if no exception is thrown
        }
    })
