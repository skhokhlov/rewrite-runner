package io.github.skhokhlov.rewriterunner.config

import io.github.skhokhlov.rewriterunner.ExecutionTimeouts
import io.github.skhokhlov.rewriterunner.NoOpRunnerLogger
import io.kotest.core.spec.style.FunSpec
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

            val config = ToolConfig.load(configFile, NoOpRunnerLogger)
            assertEquals(2, config.repositories.size)
            assertEquals("https://repo.example.com/maven", config.repositories[0].url)
            assertEquals("https://mirror.example.com/maven", config.repositories[1].url)
        }

        test("loads cacheDir from yaml") {
            val configFile = tempDir.resolve("runner.yml")
            configFile.writeText("cacheDir: /custom/cache")

            val config = ToolConfig.load(configFile, NoOpRunnerLogger)
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

            val config = ToolConfig.load(configFile, NoOpRunnerLogger)
            assertEquals(listOf(".java", ".kt"), config.parse.includeExtensions)
            assertEquals(listOf(".xml"), config.parse.excludeExtensions)
            assertEquals(listOf("**/generated/**"), config.parse.excludePaths)
        }

        test("returns default config when file does not exist") {
            val config = ToolConfig.load(tempDir.resolve("nonexistent.yml"), NoOpRunnerLogger)
            assertTrue(config.repositories.isEmpty())
            assertEquals("~/.rewriterunner/cache", config.cacheDir)
        }

        test("returns default config when null path passed") {
            val config = ToolConfig.load(null, NoOpRunnerLogger)
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
            val config = ToolConfig.load(configFile, NoOpRunnerLogger)
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
            val config = ToolConfig.load(configFile, NoOpRunnerLogger)
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
            val config = ToolConfig.load(configFile, NoOpRunnerLogger)
            val resolved = config.resolvedRepositories()
            // When env var is not set, the placeholder is preserved as-is
            assertEquals("\${DEFINITELY_NOT_SET_XYZ_12345}", resolved[0].password)
        }

        // ─── resolvedCacheDir ─────────────────────────────────────────────────────

        test("resolvedCacheDir expands tilde to user home") {
            val config = ToolConfig(cacheDir = "~/.rewriterunner/cache", logger = NoOpRunnerLogger)
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
            val config = ToolConfig(cacheDir = "/absolute/path/to/cache", logger = NoOpRunnerLogger)
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
            val config = ToolConfig.load(configFile, NoOpRunnerLogger)
            assertEquals("alice", config.repositories[0].username)
            assertEquals("secret123", config.repositories[0].password)
        }

        // ─── includeMavenCentral ──────────────────────────────────────────────────

        test("includeMavenCentral defaults to true when not specified") {
            val config = ToolConfig(logger = NoOpRunnerLogger)
            assertEquals(true, config.includeMavenCentral)
        }

        test("loads includeMavenCentral: false from yaml") {
            val configFile = tempDir.resolve("runner.yml")
            configFile.writeText("includeMavenCentral: false")
            val config = ToolConfig.load(configFile, NoOpRunnerLogger)
            assertEquals(false, config.includeMavenCentral)
        }

        // ─── downloadThreads ──────────────────────────────────────────────────────

        test("downloadThreads defaults to 5 when not specified") {
            val config = ToolConfig(logger = NoOpRunnerLogger)
            assertEquals(5, config.downloadThreads)
        }

        test("loads downloadThreads from yaml") {
            val configFile = tempDir.resolve("runner.yml")
            configFile.writeText("downloadThreads: 8")
            val config = ToolConfig.load(configFile, NoOpRunnerLogger)
            assertEquals(8, config.downloadThreads)
        }

        // ─── Timeouts ─────────────────────────────────────────────────────────────

        test("timeout settings default to central execution defaults") {
            val config = ToolConfig(logger = NoOpRunnerLogger)
            assertEquals(
                ExecutionTimeouts.DEFAULT_PROCESS_TIMEOUT,
                config.processTimeout
            )
            assertEquals(
                ExecutionTimeouts.DEFAULT_PLUGIN_TIMEOUT,
                config.pluginTimeout
            )
            assertEquals(
                ExecutionTimeouts.DEFAULT_RESOLVER_CONNECT_TIMEOUT,
                config.resolverConnectTimeout
            )
            assertEquals(
                ExecutionTimeouts.DEFAULT_RESOLVER_REQUEST_TIMEOUT,
                config.resolverRequestTimeout
            )
        }

        test("loads human unit timeout settings from yaml") {
            val configFile = tempDir.resolve("runner.yml")
            configFile.writeText(
                """
                processTimeout: 45s
                pluginTimeout: 15m
                resolverConnectTimeout: 10000ms
                resolverRequestTimeout: 20s
                """.trimIndent()
            )
            val config = ToolConfig.load(configFile, NoOpRunnerLogger)
            assertEquals(Duration.ofSeconds(45), config.processTimeout)
            assertEquals(Duration.ofMinutes(15), config.pluginTimeout)
            assertEquals(Duration.ofMillis(10_000), config.resolverConnectTimeout)
            assertEquals(Duration.ofSeconds(20), config.resolverRequestTimeout)
        }

        test("loads ISO-8601 timeout settings from yaml") {
            val configFile = tempDir.resolve("runner.yml")
            configFile.writeText(
                """
                processTimeout: PT2M
                pluginTimeout: PT10M
                resolverConnectTimeout: PT30S
                resolverRequestTimeout: PT1M
                """.trimIndent()
            )
            val config = ToolConfig.load(configFile, NoOpRunnerLogger)
            assertEquals(Duration.ofMinutes(2), config.processTimeout)
            assertEquals(Duration.ofMinutes(10), config.pluginTimeout)
            assertEquals(Duration.ofSeconds(30), config.resolverConnectTimeout)
            assertEquals(Duration.ofMinutes(1), config.resolverRequestTimeout)
        }

        test("bare numeric timeout values are rejected") {
            val configFile = tempDir.resolve("runner.yml")
            configFile.writeText("processTimeout: 120")

            val error = assertFailsWith<IllegalArgumentException> {
                ToolConfig.load(configFile, NoOpRunnerLogger)
            }
            assertTrue(error.message.orEmpty().contains("processTimeout"))
            assertTrue(error.message.orEmpty().contains("unit"))
        }

        test("legacy timeout field names are rejected with migration guidance") {
            val configFile = tempDir.resolve("runner.yml")
            configFile.writeText(
                """
                processTimeoutSeconds: 45
                pluginTimeoutSeconds: 900
                resolverConnectTimeoutMs: 10000
                resolverRequestTimeoutMs: 20000
                """.trimIndent()
            )

            val error = assertFailsWith<IllegalArgumentException> {
                ToolConfig.load(configFile, NoOpRunnerLogger)
            }
            assertTrue(error.message.orEmpty().contains("processTimeoutSeconds"))
            assertTrue(error.message.orEmpty().contains("use 'processTimeout'"))
        }

        test("legacy timeout names inside block scalar content are not rejected") {
            val configFile = tempDir.resolve("runner.yml")
            configFile.writeText(
                """
                processTimeout: 120s
                notes: |
                  Example legacy config:
                  processTimeoutSeconds: 45
                  pluginTimeoutSeconds: 900
                """.trimIndent()
            )

            val config = ToolConfig.load(configFile, NoOpRunnerLogger)

            assertEquals(Duration.ofSeconds(120), config.processTimeout)
        }

        // ─── OpenRewrite plugin versions ─────────────────────────────────────────

        test("rewrite plugin versions default to central constants") {
            val config = ToolConfig(logger = NoOpRunnerLogger)
            assertEquals(
                ToolConfig.REWRITE_GRADLE_PLUGIN_VERSION,
                config.rewriteGradlePluginVersion
            )
            assertEquals(
                ToolConfig.REWRITE_MAVEN_PLUGIN_VERSION,
                config.rewriteMavenPluginVersion
            )
        }

        test("loads rewrite plugin versions from yaml") {
            val configFile = tempDir.resolve("runner.yml")
            configFile.writeText(
                """
                rewriteGradlePluginVersion: 7.20.0
                rewriteMavenPluginVersion: 6.23.0
                """.trimIndent()
            )

            val config = ToolConfig.load(configFile, NoOpRunnerLogger)
            assertEquals("7.20.0", config.rewriteGradlePluginVersion)
            assertEquals("6.23.0", config.rewriteMavenPluginVersion)
        }

        test("repository without credentials has null username and password") {
            val configFile = tempDir.resolve("runner.yml")
            configFile.writeText(
                """
                repositories:
                  - url: https://public.repo.com/maven
                """.trimIndent()
            )
            val config = ToolConfig.load(configFile, NoOpRunnerLogger)
            assertEquals(null, config.repositories[0].username)
            assertEquals(null, config.repositories[0].password)
        }
    })
