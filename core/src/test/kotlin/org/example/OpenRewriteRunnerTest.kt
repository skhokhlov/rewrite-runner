package org.example

import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class OpenRewriteRunnerTest {

    @TempDir
    lateinit var projectDir: Path

    @TempDir
    lateinit var cacheDir: Path

    /**
     * Builds a rewrite.yaml that uses DeleteSourceFiles to remove all .properties files,
     * then runs OpenRewriteRunner against a project containing one such file.
     */
    private fun runDeletePropertiesRecipe(dryRun: Boolean = false) {
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

    @Test
    fun `recipe-deleted files are removed from disk`() {
        // Put the file in a subdirectory so "**/*.properties" glob matches unambiguously
        projectDir.resolve("config").toFile().mkdirs()
        val propsFile = projectDir.resolve("config/app.properties")
        propsFile.writeText("key=value\n")
        assertTrue(propsFile.exists(), "File should exist before run")

        runDeletePropertiesRecipe()

        assertFalse(propsFile.exists(), "Recipe-deleted file should be removed from disk")
    }

    @Test
    fun `dry-run does not delete recipe-deleted files`() {
        projectDir.resolve("config").toFile().mkdirs()
        val propsFile = projectDir.resolve("config/app.properties")
        propsFile.writeText("key=value\n")

        runDeletePropertiesRecipe(dryRun = true)

        assertTrue(propsFile.exists(), "Dry-run must not delete files from disk")
    }
}
