package io.github.skhokhlov.rewriterunner.config

import io.kotest.core.spec.style.FunSpec
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ToolConfigTest :
    FunSpec({
        var tempDir: Path = Path.of("")

        beforeEach { tempDir = Files.createTempDirectory("toolconfig-") }

        afterEach { tempDir.toFile().deleteRecursively() }

        // ─── Load from file ────────────────────────────────────────────────────────

        test("loads repository list from yaml") {
            val configFile = tempDir.resolve("runner.yml")
            configFile.writeText(
                """
                repositories:
                  - url: https://repo.example.com/maven
                  - url: https://mirror.example.com/maven
                """.trimIndent()
            )

            val config = ToolConfig.load(configFile)
            assertEquals(2, config.repositories.size)
            assertEquals("https://repo.example.com/maven", config.repositories[0].url)
            assertEquals("https://mirror.example.com/maven", config.repositories[1].url)
        }

        test("loads cacheDir from yaml") {
            val configFile = tempDir.resolve("runner.yml")
            configFile.writeText("cacheDir: /custom/cache")

            val config = ToolConfig.load(configFile)
            assertEquals("/custom/cache", config.cacheDir)
        }

        test("loads parse config from yaml") {
            val configFile = tempDir.resolve("runner.yml")
            configFile.writeText(
                """
                parse:
                  includeExtensions: [".java", ".kt"]
                  excludeExtensions: [".xml"]
                  excludePaths: ["**/generated/**"]
                """.trimIndent()
            )

            val config = ToolConfig.load(configFile)
            assertEquals(listOf(".java", ".kt"), config.parse.includeExtensions)
            assertEquals(listOf(".xml"), config.parse.excludeExtensions)
            assertEquals(listOf("**/generated/**"), config.parse.excludePaths)
        }

        test("returns default config when file does not exist") {
            val config = ToolConfig.load(tempDir.resolve("nonexistent.yml"))
            assertTrue(config.repositories.isEmpty())
            assertEquals("~/.rewriterunner/cache", config.cacheDir)
        }

        test("returns default config when null path passed") {
            val config = ToolConfig.load(null)
            assertTrue(config.repositories.isEmpty())
        }

        test("ignores unknown yaml fields without error") {
            val configFile = tempDir.resolve("runner.yml")
            configFile.writeText(
                """
                unknownField: someValue
                cacheDir: /my/cache
                """.trimIndent()
            )
            val config = ToolConfig.load(configFile)
            assertEquals("/my/cache", config.cacheDir)
        }

        // ─── Env var interpolation ────────────────────────────────────────────────

        test("interpolates env var in repository username") {
            if (System.getenv("USER").isNullOrEmpty()) return@test
            val configFile = tempDir.resolve("runner.yml")
            configFile.writeText(
                """
                repositories:
                  - url: https://repo.example.com
                    username: ${"$"}{USER}
                    password: secret
                """.trimIndent()
            )
            val config = ToolConfig.load(configFile)
            val resolved = config.resolvedRepositories()
            // USER env var is usually set on Unix; check it was substituted (not kept as literal)
            assertNotNull(resolved[0].username)
            assertTrue(
                !resolved[0].username!!.contains("\${"),
                "Env var placeholder should be replaced"
            )
        }

        test("leaves unresolved env var placeholder when env var not set") {
            val configFile = tempDir.resolve("runner.yml")
            configFile.writeText(
                """
                repositories:
                  - url: https://repo.example.com
                    password: ${"$"}{DEFINITELY_NOT_SET_XYZ_12345}
                """.trimIndent()
            )
            val config = ToolConfig.load(configFile)
            val resolved = config.resolvedRepositories()
            // When env var is not set, the placeholder is preserved as-is
            assertEquals("\${DEFINITELY_NOT_SET_XYZ_12345}", resolved[0].password)
        }

        // ─── resolvedCacheDir ─────────────────────────────────────────────────────

        test("resolvedCacheDir expands tilde to user home") {
            val config = ToolConfig(cacheDir = "~/.rewriterunner/cache")
            val resolved = config.resolvedCacheDir()
            val home = System.getProperty("user.home")
            assertTrue(
                resolved.toString().startsWith(home),
                "Tilde should be expanded to user home dir. Got: $resolved"
            )
            assertTrue(
                resolved.toString().contains(".rewriterunner"),
                "Should contain .rewriterunner path component"
            )
        }

        test("resolvedCacheDir returns absolute path unchanged") {
            val config = ToolConfig(cacheDir = "/absolute/path/to/cache")
            assertEquals(Path.of("/absolute/path/to/cache"), config.resolvedCacheDir())
        }

        // ─── Repository auth ──────────────────────────────────────────────────────

        test("repository with username and password is loaded") {
            val configFile = tempDir.resolve("runner.yml")
            configFile.writeText(
                """
                repositories:
                  - url: https://private.repo.com/maven
                    username: alice
                    password: secret123
                """.trimIndent()
            )
            val config = ToolConfig.load(configFile)
            assertEquals("alice", config.repositories[0].username)
            assertEquals("secret123", config.repositories[0].password)
        }

        test("repository without credentials has null username and password") {
            val configFile = tempDir.resolve("runner.yml")
            configFile.writeText(
                """
                repositories:
                  - url: https://public.repo.com/maven
                """.trimIndent()
            )
            val config = ToolConfig.load(configFile)
            assertEquals(null, config.repositories[0].username)
            assertEquals(null, config.repositories[0].password)
        }
    })
