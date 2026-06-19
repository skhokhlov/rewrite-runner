package io.github.skhokhlov.rewriterunner.config

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
                artifactRepositories:
                  - url: https://repo.example.com/maven
                  - url: https://mirror.example.com/maven
                """.trimIndent()
            )

            val config = ToolConfig.load(configFile, NoOpRunnerLogger)
            assertEquals(2, config.artifactRepositories.size)
            assertEquals("https://repo.example.com/maven", config.artifactRepositories[0].url)
            assertEquals("https://mirror.example.com/maven", config.artifactRepositories[1].url)
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
                  excludePaths: ["**/generated/**", "**/*.md"]
                  plainTextMasks: ["**/CODEOWNERS", "**/*.txt"]
                """.trimIndent()
            )

            val config = ToolConfig.load(configFile, NoOpRunnerLogger)
            assertEquals(listOf("**/generated/**", "**/*.md"), config.parse.excludePaths)
            assertEquals(listOf("**/CODEOWNERS", "**/*.txt"), config.parse.plainTextMasks)
        }

        test("plain text mask CLI override replaces YAML config") {
            val config = ToolConfig(
                parse = ParseConfig(plainTextMasks = listOf("**/CODEOWNERS")),
                logger = NoOpRunnerLogger
            )

            val resolved = config.resolvedPlainTextMasks(listOf("**/OWNERS"))

            assertEquals(listOf("**/OWNERS"), resolved)
        }

        test("plain text masks fall back to YAML config when CLI override empty") {
            val config = ToolConfig(
                parse = ParseConfig(plainTextMasks = listOf("**/CODEOWNERS")),
                logger = NoOpRunnerLogger
            )

            val resolved = config.resolvedPlainTextMasks(emptyList())

            assertEquals(listOf("**/CODEOWNERS"), resolved)
        }

        test("plain text masks fall back to upstream defaults when CLI and YAML are empty") {
            val config = ToolConfig(logger = NoOpRunnerLogger)

            val resolved = config.resolvedPlainTextMasks(emptyList())

            assertEquals(ToolConfigDefaults.DEFAULT_PLAIN_TEXT_MASKS, resolved)
        }

        test("default plain text masks match upstream plugin defaults") {
            assertEquals(
                listOf(
                    "**/.gitattributes",
                    "**/.gitignore",
                    "**/.java-version",
                    "**/.sdkmanrc",
                    "**/[mM]akefile",
                    "**/*.adoc",
                    "**/*.aj",
                    "**/*.bash",
                    "**/*.bat",
                    "**/*.config",
                    "**/*.css",
                    "**/*.env",
                    "**/*.htm*",
                    "**/*.jelly",
                    "**/*.jsp",
                    "**/*.ksh",
                    "**/*.lock",
                    "**/*.md",
                    "**/*.mf",
                    "**/*.qute.java",
                    "**/*.sh",
                    "**/*.sql",
                    "**/*.svg",
                    "**/*.tsx",
                    "**/*.txt",
                    "**/CODEOWNERS",
                    "**/Dockerfile*",
                    "**/gradlew",
                    "**/lombok.config",
                    "**/META-INF/services/**",
                    "**/META-INF/spring.factories",
                    "**/META-INF/spring/**",
                    "**/mvnw"
                ),
                ToolConfigDefaults.DEFAULT_PLAIN_TEXT_MASKS
            )
        }

        test("ignores unknown legacy parse fields without error") {
            val configFile = tempDir.resolve("runner.yml")
            configFile.writeText(
                """
                parse:
                  includeExtensions: [".java"]
                  excludeExtensions: [".xml"]
                  excludePaths: ["**/generated/**"]
                """.trimIndent()
            )

            val config = ToolConfig.load(configFile, NoOpRunnerLogger)
            assertEquals(listOf("**/generated/**"), config.parse.excludePaths)
        }

        test("returns default config when file does not exist") {
            val config = ToolConfig.load(tempDir.resolve("nonexistent.yml"), NoOpRunnerLogger)
            assertTrue(config.artifactRepositories.isEmpty())
            assertEquals("~/.rewriterunner/cache", config.cacheDir)
        }

        test("returns default config when null path passed") {
            val config = ToolConfig.load(null, NoOpRunnerLogger)
            assertTrue(config.artifactRepositories.isEmpty())
        }

        test("constructor defaults come from ToolConfigDefaults") {
            val config = ToolConfig()
            assertEquals(ToolConfigDefaults.CACHE_DIR, config.cacheDir)
            assertEquals(ToolConfigDefaults.INCLUDE_MAVEN_CENTRAL, config.includeMavenCentral)
            assertEquals(
                ToolConfigDefaults.ARTIFACT_DOWNLOAD_THREADS,
                config.artifactDownloadThreads
            )
            assertEquals(ToolConfigDefaults.SUBPROCESS_RUN_TIMEOUT, config.subprocessRunTimeout)
            assertEquals(ToolConfigDefaults.PLUGIN_RUN_TIMEOUT, config.pluginRunTimeout)
            assertEquals(
                ToolConfigDefaults.REWRITE_GRADLE_PLUGIN_VERSION,
                config.rewriteGradlePluginVersion
            )
            assertEquals(
                ToolConfigDefaults.REWRITE_MAVEN_PLUGIN_VERSION,
                config.rewriteMavenPluginVersion
            )
            assertEquals(
                ToolConfigDefaults.ARTIFACT_RESOLVER_CONNECT_TIMEOUT,
                config.artifactResolverConnectTimeout
            )
            assertEquals(
                ToolConfigDefaults.ARTIFACT_RESOLVER_REQUEST_TIMEOUT,
                config.artifactResolverRequestTimeout
            )
            assertEquals(ToolConfigDefaults.PLUGIN_JVM_ARGS, config.pluginJvmArgs)
            assertTrue(config.pluginJvmArgs.isEmpty())
        }

        test("loads pluginJvmArgs from yaml") {
            val configFile = tempDir.resolve("runner.yml")
            configFile.writeText(
                """
                pluginJvmArgs:
                  - "-Xmx4g"
                  - "-XX:+UseG1GC"
                """.trimIndent()
            )

            val config = ToolConfig.load(configFile, NoOpRunnerLogger)

            assertEquals(listOf("-Xmx4g", "-XX:+UseG1GC"), config.pluginJvmArgs)
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
                artifactRepositories:
                  - url: https://repo.example.com
                    username: ${"$"}{USER}
                    password: secret
                """.trimIndent()
            )
            val config = ToolConfig.load(configFile, NoOpRunnerLogger)
            val resolved = config.resolvedArtifactRepositories()
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
                artifactRepositories:
                  - url: https://repo.example.com
                    password: ${"$"}{DEFINITELY_NOT_SET_XYZ_12345}
                """.trimIndent()
            )
            val config = ToolConfig.load(configFile, NoOpRunnerLogger)
            val resolved = config.resolvedArtifactRepositories()
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
                artifactRepositories:
                  - url: https://private.repo.com/maven
                    username: alice
                    password: secret123
                """.trimIndent()
            )
            val config = ToolConfig.load(configFile, NoOpRunnerLogger)
            assertEquals("alice", config.artifactRepositories[0].username)
            assertEquals("secret123", config.artifactRepositories[0].password)
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
            assertEquals(5, config.artifactDownloadThreads)
        }

        test("loads downloadThreads from yaml") {
            val configFile = tempDir.resolve("runner.yml")
            configFile.writeText("artifactDownloadThreads: 8")
            val config = ToolConfig.load(configFile, NoOpRunnerLogger)
            assertEquals(8, config.artifactDownloadThreads)
        }

        // ─── Timeouts ─────────────────────────────────────────────────────────────

        test("timeout settings default to central execution defaults") {
            val config = ToolConfig(logger = NoOpRunnerLogger)
            assertEquals(
                ToolConfigDefaults.SUBPROCESS_RUN_TIMEOUT,
                config.subprocessRunTimeout
            )
            assertEquals(
                ToolConfigDefaults.PLUGIN_RUN_TIMEOUT,
                config.pluginRunTimeout
            )
            assertEquals(
                ToolConfigDefaults.ARTIFACT_RESOLVER_CONNECT_TIMEOUT,
                config.artifactResolverConnectTimeout
            )
            assertEquals(
                ToolConfigDefaults.ARTIFACT_RESOLVER_REQUEST_TIMEOUT,
                config.artifactResolverRequestTimeout
            )
        }

        test("loads human unit timeout settings from yaml") {
            val configFile = tempDir.resolve("runner.yml")
            configFile.writeText(
                """
                subprocessRunTimeout: 45s
                pluginRunTimeout: 15m
                artifactResolverConnectTimeout: 10000ms
                artifactResolverRequestTimeout: 20s
                """.trimIndent()
            )
            val config = ToolConfig.load(configFile, NoOpRunnerLogger)
            assertEquals(Duration.ofSeconds(45), config.subprocessRunTimeout)
            assertEquals(Duration.ofMinutes(15), config.pluginRunTimeout)
            assertEquals(Duration.ofMillis(10_000), config.artifactResolverConnectTimeout)
            assertEquals(Duration.ofSeconds(20), config.artifactResolverRequestTimeout)
        }

        test("loads ISO-8601 timeout settings from yaml") {
            val configFile = tempDir.resolve("runner.yml")
            configFile.writeText(
                """
                subprocessRunTimeout: PT2M
                pluginRunTimeout: PT10M
                artifactResolverConnectTimeout: PT30S
                artifactResolverRequestTimeout: PT1M
                """.trimIndent()
            )
            val config = ToolConfig.load(configFile, NoOpRunnerLogger)
            assertEquals(Duration.ofMinutes(2), config.subprocessRunTimeout)
            assertEquals(Duration.ofMinutes(10), config.pluginRunTimeout)
            assertEquals(Duration.ofSeconds(30), config.artifactResolverConnectTimeout)
            assertEquals(Duration.ofMinutes(1), config.artifactResolverRequestTimeout)
        }

        test("bare numeric timeout values are rejected") {
            val configFile = tempDir.resolve("runner.yml")
            configFile.writeText("subprocessRunTimeout: 120")

            val error = assertFailsWith<IllegalArgumentException> {
                ToolConfig.load(configFile, NoOpRunnerLogger)
            }
            assertTrue(error.message.orEmpty().contains("subprocessRunTimeout"))
            assertTrue(error.message.orEmpty().contains("unit"))
        }

        // ─── OpenRewrite plugin versions ─────────────────────────────────────────

        test("rewrite plugin versions default to central constants") {
            val config = ToolConfig(logger = NoOpRunnerLogger)
            assertEquals(
                ToolConfigDefaults.REWRITE_GRADLE_PLUGIN_VERSION,
                config.rewriteGradlePluginVersion
            )
            assertEquals(
                ToolConfigDefaults.REWRITE_MAVEN_PLUGIN_VERSION,
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
                artifactRepositories:
                  - url: https://public.repo.com/maven
                """.trimIndent()
            )
            val config = ToolConfig.load(configFile, NoOpRunnerLogger)
            assertEquals(null, config.artifactRepositories[0].username)
            assertEquals(null, config.artifactRepositories[0].password)
        }
    })
