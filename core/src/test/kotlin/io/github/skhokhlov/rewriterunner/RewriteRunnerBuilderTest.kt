package io.github.skhokhlov.rewriterunner

import io.kotest.core.spec.style.FunSpec
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RewriteRunnerBuilderTest :
    FunSpec({
        var tempDir: Path = Path.of("")

        beforeEach { tempDir = Files.createTempDirectory("orrbt-") }

        afterEach { tempDir.toFile().deleteRecursively() }

        // ── Build-time validation ────────────────────────────────────────────────

        test("build throws when activeRecipe is blank") {
            assertFailsWith<IllegalStateException> {
                RewriteRunner.builder().projectDir(tempDir).build()
            }
        }

        test("build succeeds and returns non-null runner") {
            val runner =
                RewriteRunner.builder()
                    .projectDir(tempDir)
                    .activeRecipe("org.openrewrite.FindSourceFiles")
                    .build()
            assertNotNull(runner)
        }

        // ── Property storage: each setter persists its value ────────────────────

        test("builder stores projectDir") {
            val builder = RewriteRunner.builder().projectDir(tempDir)
            assertEquals(tempDir, builder.projectDir)
        }

        test("builder stores activeRecipe") {
            val builder =
                RewriteRunner.builder().activeRecipe("org.openrewrite.FindSourceFiles")
            assertEquals("org.openrewrite.FindSourceFiles", builder.activeRecipe)
        }

        test("recipeArtifact accumulates coordinates in order") {
            val builder =
                RewriteRunner.builder()
                    .projectDir(tempDir)
                    .activeRecipe("org.openrewrite.FindSourceFiles")
                    .recipeArtifact("com.example:recipe-a:1.0")
                    .recipeArtifact("com.example:recipe-b:2.0")
            assertEquals(
                listOf("com.example:recipe-a:1.0", "com.example:recipe-b:2.0"),
                builder.recipeArtifacts
            )
        }

        test("recipeArtifacts replaces the full list including previously accumulated entries") {
            val replacement = listOf("com.example:lib-a:1.0", "com.example:lib-b:1.0")
            val builder =
                RewriteRunner.builder()
                    .projectDir(tempDir)
                    .activeRecipe("org.openrewrite.FindSourceFiles")
                    .recipeArtifact("com.example:old:9.9")
                    .recipeArtifacts(replacement)
            assertEquals(replacement, builder.recipeArtifacts)
        }

        test("builder stores rewriteConfig path") {
            val configPath = tempDir.resolve("rewrite.yaml")
            val builder =
                RewriteRunner.builder()
                    .projectDir(tempDir)
                    .activeRecipe("org.openrewrite.FindSourceFiles")
                    .rewriteConfig(configPath)
            assertEquals(configPath, builder.rewriteConfig)
        }

        test("builder stores cacheDir path") {
            val cache = tempDir.resolve("cache")
            val builder =
                RewriteRunner.builder()
                    .projectDir(tempDir)
                    .activeRecipe("org.openrewrite.FindSourceFiles")
                    .cacheDir(cache)
            assertEquals(cache, builder.cacheDir)
        }

        test("builder stores configFile path") {
            val configFile = tempDir.resolve("config.yml")
            val builder =
                RewriteRunner.builder()
                    .projectDir(tempDir)
                    .activeRecipe("org.openrewrite.FindSourceFiles")
                    .configFile(configFile)
            assertEquals(configFile, builder.configFile)
        }

        test("builder stores dryRun true") {
            val builder =
                RewriteRunner.builder()
                    .projectDir(tempDir)
                    .activeRecipe("org.openrewrite.FindSourceFiles")
                    .dryRun(true)
            assertTrue(builder.dryRun)
        }

        test("builder stores dryRun false when explicitly set") {
            val builder =
                RewriteRunner.builder()
                    .projectDir(tempDir)
                    .activeRecipe("org.openrewrite.FindSourceFiles")
                    .dryRun(false)
            assertFalse(builder.dryRun)
        }

        test("builder stores skipPluginRun true") {
            val builder =
                RewriteRunner.builder()
                    .projectDir(tempDir)
                    .activeRecipe("org.openrewrite.FindSourceFiles")
                    .skipPluginRun(true)
            assertTrue(builder.skipPluginRun)
        }

        test("builder stores timeout overrides") {
            val builder =
                RewriteRunner.builder()
                    .projectDir(tempDir)
                    .activeRecipe("org.openrewrite.FindSourceFiles")
                    .subprocessRunTimeout(Duration.ofSeconds(45))
                    .pluginRunTimeout(Duration.ofMinutes(15))
                    .artifactResolverConnectTimeout(Duration.ofSeconds(10))
                    .artifactResolverRequestTimeout(Duration.ofSeconds(20))
            assertEquals(Duration.ofSeconds(45), builder.subprocessRunTimeout)
            assertEquals(Duration.ofMinutes(15), builder.pluginRunTimeout)
            assertEquals(Duration.ofSeconds(10), builder.artifactResolverConnectTimeout)
            assertEquals(Duration.ofSeconds(20), builder.artifactResolverRequestTimeout)
        }

        test("builder stores excludePaths") {
            val paths = listOf("**/generated/**", "**/*.md")
            val builder =
                RewriteRunner.builder()
                    .projectDir(tempDir)
                    .activeRecipe("org.openrewrite.FindSourceFiles")
                    .excludePaths(paths)
            assertEquals(paths, builder.excludePaths)
        }

        test("builder stores plainTextMasks") {
            val masks = listOf("**/CODEOWNERS", "**/*.txt")
            val builder =
                RewriteRunner.builder()
                    .projectDir(tempDir)
                    .activeRecipe("org.openrewrite.FindSourceFiles")
                    .plainTextMasks(masks)
            assertEquals(masks, builder.plainTextMasks)
        }

        // ── Default values ───────────────────────────────────────────────────────

        test("dryRun defaults to false") {
            val builder = RewriteRunner.builder()
            assertFalse(builder.dryRun)
        }

        test("skipPluginRun defaults to false") {
            val builder = RewriteRunner.builder()
            assertFalse(builder.skipPluginRun)
        }

        test("timeout overrides default to null") {
            val builder = RewriteRunner.builder()
            assertNull(builder.subprocessRunTimeout)
            assertNull(builder.pluginRunTimeout)
            assertNull(builder.artifactResolverConnectTimeout)
            assertNull(builder.artifactResolverRequestTimeout)
        }

        test("rewriteConfig defaults to null") {
            val builder = RewriteRunner.builder()
            assertNull(builder.rewriteConfig)
        }

        test("cacheDir defaults to null") {
            val builder = RewriteRunner.builder()
            assertNull(builder.cacheDir)
        }

        test("configFile defaults to null") {
            val builder = RewriteRunner.builder()
            assertNull(builder.configFile)
        }

        test("recipeArtifacts defaults to empty") {
            val builder = RewriteRunner.builder()
            assertTrue(builder.recipeArtifacts.isEmpty())
        }

        test("excludePaths defaults to empty") {
            val builder = RewriteRunner.builder()
            assertTrue(builder.excludePaths.isEmpty())
        }

        test("plainTextMasks defaults to empty") {
            val builder = RewriteRunner.builder()
            assertTrue(builder.plainTextMasks.isEmpty())
        }

        // ── Run-time behaviour ───────────────────────────────────────────────────

        test("run throws when projectDir does not exist") {
            val nonExistent = Paths.get("/tmp/surely-does-not-exist-12345xyz")
            val runner =
                RewriteRunner.builder()
                    .projectDir(nonExistent)
                    .activeRecipe("org.openrewrite.FindSourceFiles")
                    .build()
            assertFailsWith<IllegalArgumentException> { runner.run() }
        }

        test("auto-discovers rewriterunner.yml from project dir when configFile not set") {
            val customCache = tempDir.resolve("my-custom-cache")
            tempDir.resolve("rewriterunner.yml").writeText("cacheDir: $customCache")

            RewriteRunner.builder()
                .projectDir(tempDir)
                .activeRecipe("org.openrewrite.FindSourceFiles")
                .dryRun(true)
                .build()
                .run()

            assertTrue(
                customCache.resolve("repository").toFile().exists(),
                "Custom cacheDir from rewriterunner.yml should have been auto-discovered"
            )
        }

        test("auto-discovers rewriterunner.yml case-insensitively from project dir") {
            val customCache = tempDir.resolve("my-custom-cache-ci")
            tempDir.resolve("RewriteRunner.yml").writeText("cacheDir: $customCache")

            RewriteRunner.builder()
                .projectDir(tempDir)
                .activeRecipe("org.openrewrite.FindSourceFiles")
                .dryRun(true)
                .build()
                .run()

            assertTrue(
                customCache.resolve("repository").toFile().exists(),
                "rewriterunner.yml should be found case-insensitively"
            )
        }

        test("auto-discovers rewrite.yml from project dir when rewrite.yaml absent") {
            tempDir.resolve("rewrite.yml").writeText(
                """
                ---
                type: specs.openrewrite.org/v1beta/recipe
                name: io.test.AutoDiscoveredYmlRecipe
                displayName: Auto-discovered yml recipe
                recipeList:
                  - org.openrewrite.FindSourceFiles
                """.trimIndent()
            )

            val result = RewriteRunner.builder()
                .projectDir(tempDir)
                .activeRecipe("io.test.AutoDiscoveredYmlRecipe")
                .cacheDir(tempDir.resolve("cache"))
                .dryRun(true)
                .build()
                .run()

            assertNotNull(result)
        }

        test("run on empty directory with built-in recipe returns empty results") {
            val runner =
                RewriteRunner.builder()
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
    })
