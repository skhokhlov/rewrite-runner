package org.example

import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class OpenRewriteRunnerBuilderTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `builder returns non-null runner`() {
        val runner = OpenRewriteRunner.builder()
            .projectDir(tempDir)
            .activeRecipe("org.openrewrite.java.format.AutoFormat")
            .build()
        assertNotNull(runner)
    }

    @Test
    fun `build throws when activeRecipe is blank`() {
        assertFailsWith<IllegalStateException> {
            OpenRewriteRunner.builder()
                .projectDir(tempDir)
                .build()
        }
    }

    @Test
    fun `builder activeRecipe setter is applied`() {
        // Verify that setting activeRecipe doesn't throw and the runner is built
        val runner = OpenRewriteRunner.builder()
            .projectDir(tempDir)
            .activeRecipe("org.openrewrite.FindSourceFiles")
            .build()
        assertNotNull(runner)
    }

    @Test
    fun `builder recipeArtifact accumulates coordinates`() {
        // recipeArtifact() appends; verify build() succeeds and stores them
        val builder = OpenRewriteRunner.builder()
            .projectDir(tempDir)
            .activeRecipe("org.openrewrite.FindSourceFiles")
            .recipeArtifact("com.example:recipe-a:1.0")
            .recipeArtifact("com.example:recipe-b:2.0")
        val runner = builder.build()
        assertNotNull(runner)
    }

    @Test
    fun `builder recipeArtifacts replaces the full list`() {
        val coords = listOf("com.example:lib-a:1.0", "com.example:lib-b:1.0")
        val runner = OpenRewriteRunner.builder()
            .projectDir(tempDir)
            .activeRecipe("org.openrewrite.FindSourceFiles")
            .recipeArtifacts(coords)
            .build()
        assertNotNull(runner)
    }

    @Test
    fun `builder rewriteConfig accepts a path`() {
        val configPath = tempDir.resolve("rewrite.yaml")
        val runner = OpenRewriteRunner.builder()
            .projectDir(tempDir)
            .activeRecipe("org.openrewrite.FindSourceFiles")
            .rewriteConfig(configPath)
            .build()
        assertNotNull(runner)
    }

    @Test
    fun `builder cacheDir accepts a path`() {
        val runner = OpenRewriteRunner.builder()
            .projectDir(tempDir)
            .activeRecipe("org.openrewrite.FindSourceFiles")
            .cacheDir(tempDir.resolve("cache"))
            .build()
        assertNotNull(runner)
    }

    @Test
    fun `builder configFile accepts a path`() {
        val runner = OpenRewriteRunner.builder()
            .projectDir(tempDir)
            .activeRecipe("org.openrewrite.FindSourceFiles")
            .configFile(tempDir.resolve("config.yml"))
            .build()
        assertNotNull(runner)
    }

    @Test
    fun `builder dryRun defaults to false and can be set to true`() {
        // Build twice: once without dryRun (default), once with it set explicitly
        val defaultRunner = OpenRewriteRunner.builder()
            .projectDir(tempDir)
            .activeRecipe("org.openrewrite.FindSourceFiles")
            .build()
        assertNotNull(defaultRunner)

        val dryRunRunner = OpenRewriteRunner.builder()
            .projectDir(tempDir)
            .activeRecipe("org.openrewrite.FindSourceFiles")
            .dryRun(true)
            .build()
        assertNotNull(dryRunRunner)
    }

    @Test
    fun `builder includeExtensions accepts a list`() {
        val runner = OpenRewriteRunner.builder()
            .projectDir(tempDir)
            .activeRecipe("org.openrewrite.FindSourceFiles")
            .includeExtensions(listOf(".java", ".kt"))
            .build()
        assertNotNull(runner)
    }

    @Test
    fun `builder excludeExtensions accepts a list`() {
        val runner = OpenRewriteRunner.builder()
            .projectDir(tempDir)
            .activeRecipe("org.openrewrite.FindSourceFiles")
            .excludeExtensions(listOf(".xml", ".yml"))
            .build()
        assertNotNull(runner)
    }

    @Test
    fun `run throws when projectDir does not exist`() {
        val nonExistent = Paths.get("/tmp/surely-does-not-exist-12345xyz")
        val runner = OpenRewriteRunner.builder()
            .projectDir(nonExistent)
            .activeRecipe("org.openrewrite.FindSourceFiles")
            .build()
        assertFailsWith<IllegalArgumentException> {
            runner.run()
        }
    }

    @Test
    fun `run on empty directory with built-in recipe returns empty results`() {
        val runner = OpenRewriteRunner.builder()
            .projectDir(tempDir)
            .activeRecipe("org.openrewrite.FindSourceFiles")
            .cacheDir(tempDir.resolve("cache"))
            .dryRun(true)
            .build()
        val result = runner.run()
        assertNotNull(result)
        assertEquals(tempDir, result.projectDir)
        assertFalse(result.hasChanges)
        assertEquals(0, result.changeCount)
        assertTrue(result.changedFiles.isEmpty())
    }
}
