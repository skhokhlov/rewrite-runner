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

    // ── Build-time validation ────────────────────────────────────────────────

    @Test
    fun `build throws when activeRecipe is blank`() {
        assertFailsWith<IllegalStateException> {
            OpenRewriteRunner.builder()
                .projectDir(tempDir)
                .build()
        }
    }

    @Test
    fun `build succeeds and returns non-null runner`() {
        val runner = OpenRewriteRunner.builder()
            .projectDir(tempDir)
            .activeRecipe("org.openrewrite.FindSourceFiles")
            .build()
        assertNotNull(runner)
    }

    // ── Property storage: each setter persists its value ────────────────────

    @Test
    fun `builder stores projectDir`() {
        val builder = OpenRewriteRunner.builder().projectDir(tempDir)
        assertEquals(tempDir, builder.projectDir)
    }

    @Test
    fun `builder stores activeRecipe`() {
        val builder = OpenRewriteRunner.builder()
            .activeRecipe("org.openrewrite.FindSourceFiles")
        assertEquals("org.openrewrite.FindSourceFiles", builder.activeRecipe)
    }

    @Test
    fun `recipeArtifact accumulates coordinates in order`() {
        val builder = OpenRewriteRunner.builder()
            .projectDir(tempDir)
            .activeRecipe("org.openrewrite.FindSourceFiles")
            .recipeArtifact("com.example:recipe-a:1.0")
            .recipeArtifact("com.example:recipe-b:2.0")
        assertEquals(
            listOf("com.example:recipe-a:1.0", "com.example:recipe-b:2.0"),
            builder.recipeArtifacts
        )
    }

    @Test
    fun `recipeArtifacts replaces the full list including previously accumulated entries`() {
        val replacement = listOf("com.example:lib-a:1.0", "com.example:lib-b:1.0")
        val builder = OpenRewriteRunner.builder()
            .projectDir(tempDir)
            .activeRecipe("org.openrewrite.FindSourceFiles")
            .recipeArtifact("com.example:old:9.9")
            .recipeArtifacts(replacement)
        assertEquals(replacement, builder.recipeArtifacts)
    }

    @Test
    fun `builder stores rewriteConfig path`() {
        val configPath = tempDir.resolve("rewrite.yaml")
        val builder = OpenRewriteRunner.builder()
            .projectDir(tempDir)
            .activeRecipe("org.openrewrite.FindSourceFiles")
            .rewriteConfig(configPath)
        assertEquals(configPath, builder.rewriteConfig)
    }

    @Test
    fun `builder stores cacheDir path`() {
        val cache = tempDir.resolve("cache")
        val builder = OpenRewriteRunner.builder()
            .projectDir(tempDir)
            .activeRecipe("org.openrewrite.FindSourceFiles")
            .cacheDir(cache)
        assertEquals(cache, builder.cacheDir)
    }

    @Test
    fun `builder stores configFile path`() {
        val configFile = tempDir.resolve("config.yml")
        val builder = OpenRewriteRunner.builder()
            .projectDir(tempDir)
            .activeRecipe("org.openrewrite.FindSourceFiles")
            .configFile(configFile)
        assertEquals(configFile, builder.configFile)
    }

    @Test
    fun `builder stores dryRun true`() {
        val builder = OpenRewriteRunner.builder()
            .projectDir(tempDir)
            .activeRecipe("org.openrewrite.FindSourceFiles")
            .dryRun(true)
        assertTrue(builder.dryRun)
    }

    @Test
    fun `builder stores dryRun false when explicitly set`() {
        val builder = OpenRewriteRunner.builder()
            .projectDir(tempDir)
            .activeRecipe("org.openrewrite.FindSourceFiles")
            .dryRun(false)
        assertFalse(builder.dryRun)
    }

    @Test
    fun `builder stores includeExtensions`() {
        val extensions = listOf(".java", ".kt")
        val builder = OpenRewriteRunner.builder()
            .projectDir(tempDir)
            .activeRecipe("org.openrewrite.FindSourceFiles")
            .includeExtensions(extensions)
        assertEquals(extensions, builder.includeExtensions)
    }

    @Test
    fun `builder stores excludeExtensions`() {
        val extensions = listOf(".xml", ".yml")
        val builder = OpenRewriteRunner.builder()
            .projectDir(tempDir)
            .activeRecipe("org.openrewrite.FindSourceFiles")
            .excludeExtensions(extensions)
        assertEquals(extensions, builder.excludeExtensions)
    }

    // ── Default values ───────────────────────────────────────────────────────

    @Test
    fun `dryRun defaults to false`() {
        val builder = OpenRewriteRunner.builder()
        assertFalse(builder.dryRun)
    }

    @Test
    fun `rewriteConfig defaults to null`() {
        val builder = OpenRewriteRunner.builder()
        assertNull(builder.rewriteConfig)
    }

    @Test
    fun `cacheDir defaults to null`() {
        val builder = OpenRewriteRunner.builder()
        assertNull(builder.cacheDir)
    }

    @Test
    fun `configFile defaults to null`() {
        val builder = OpenRewriteRunner.builder()
        assertNull(builder.configFile)
    }

    @Test
    fun `recipeArtifacts defaults to empty`() {
        val builder = OpenRewriteRunner.builder()
        assertTrue(builder.recipeArtifacts.isEmpty())
    }

    @Test
    fun `includeExtensions defaults to empty`() {
        val builder = OpenRewriteRunner.builder()
        assertTrue(builder.includeExtensions.isEmpty())
    }

    @Test
    fun `excludeExtensions defaults to empty`() {
        val builder = OpenRewriteRunner.builder()
        assertTrue(builder.excludeExtensions.isEmpty())
    }

    // ── Run-time behaviour ───────────────────────────────────────────────────

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
