package io.github.skhokhlov.rewriterunner.config

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.github.skhokhlov.rewriterunner.NoOpRunnerLogger
import io.github.skhokhlov.rewriterunner.RunnerLogger
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import kotlin.io.path.exists
import kotlin.io.path.readText
import tools.jackson.databind.module.SimpleModule
import tools.jackson.dataformat.yaml.YAMLMapper
import tools.jackson.module.kotlin.KotlinModule

/**
 * Configuration for a single Maven-compatible remote repository.
 *
 * Used by [io.github.skhokhlov.rewriterunner.recipe.RecipeArtifactResolver] and
 * [io.github.skhokhlov.rewriterunner.lst.DependencyResolutionStage] when resolving JARs.
 *
 * Environment variable placeholders (`${VAR_NAME}`) in [url], [username], and [password]
 * are expanded at load time by [ToolConfig.load].
 *
 * @property url Full URL of the repository (e.g. `https://nexus.example.com/repository/maven-public`).
 * @property username Optional HTTP basic-auth username.
 * @property password Optional HTTP basic-auth password.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class RepositoryConfig(
    val url: String = "",
    val username: String? = null,
    val password: String? = null
)

/**
 * File parsing configuration for the LST-building stage.
 *
 * Controls which file paths are skipped via glob patterns and which otherwise-unhandled
 * files are parsed as plain text. Matches the upstream OpenRewrite Gradle/Maven plugins
 * so Stage 0 plugin runs and the LST fallback apply identical filtering.
 *
 * @property excludePaths Glob patterns (relative to project root) for paths to skip entirely.
 * @property plainTextMasks Glob patterns (relative to project root) for files to parse with
 *   `PlainTextParser` when no specialized parser claims them. Non-empty values replace the
 *   built-in upstream defaults.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ParseConfig(
    val excludePaths: List<String> = emptyList(),
    val plainTextMasks: List<String> = emptyList()
)

/**
 * Top-level tool configuration, typically loaded from `rewriterunner.yml`.
 *
 * Supports environment variable interpolation (`${VAR_NAME}`) and tilde expansion in all
 * string fields. Loaded via [ToolConfig.load]; programmatic library users may also
 * construct instances directly.
 *
 * @property artifactRepositories Additional remote Maven repositories for JAR resolution.
 * @property cacheDir Cache root for downloaded recipe JARs. Supports `~` and `${ENV_VAR}`
 *   expansion. Recipe artifacts are stored under `<cacheDir>/repository`, isolated from the
 *   user's Maven local repository. Project dependencies always resolve from `~/.m2/repository`.
 *   Defaults to `~/.rewriterunner/cache`.
 * @property parse File parsing configuration controlling which extensions and paths are
 *   included or excluded from the LST-building stage.
 * @property subprocessRunTimeout Timeout for build-tool subprocesses in the fallback LST pipeline.
 * @property pluginRunTimeout Timeout for official Gradle/Maven plugin invocations in Stage 0.
 * @property rewriteGradlePluginVersion Version of the official OpenRewrite Gradle plugin
 *   used by Stage 0 plugin-first execution.
 * @property rewriteMavenPluginVersion Version of the official OpenRewrite Maven plugin
 *   used by Stage 0 plugin-first execution.
 * @property pluginJvmArgs JVM arguments forwarded to the Stage 0 plugin build-tool subprocess
 *   (e.g. `-Xmx4g`). For Gradle they are injected as `-Dorg.gradle.jvmargs=…` on the command
 *   line (highest precedence; replaces, not merges, the project's `org.gradle.jvmargs`). For
 *   Maven they are appended to `MAVEN_OPTS`; a project `.mvn/jvm.config` still wins on conflict.
 *   Empty by default — nothing is injected.
 * @property artifactResolverConnectTimeout TCP connection timeout for Maven Resolver downloads.
 * @property artifactResolverRequestTimeout Socket read/request timeout for Maven Resolver downloads.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ToolConfig(
    val artifactRepositories: List<RepositoryConfig> = emptyList(),
    val cacheDir: String = ToolConfigDefaults.CACHE_DIR,
    val parse: ParseConfig = ParseConfig(),
    val includeMavenCentral: Boolean = ToolConfigDefaults.INCLUDE_MAVEN_CENTRAL,
    val artifactDownloadThreads: Int = ToolConfigDefaults.ARTIFACT_DOWNLOAD_THREADS,
    val subprocessRunTimeout: Duration = ToolConfigDefaults.SUBPROCESS_RUN_TIMEOUT,
    val pluginRunTimeout: Duration = ToolConfigDefaults.PLUGIN_RUN_TIMEOUT,
    val rewriteGradlePluginVersion: String = ToolConfigDefaults.REWRITE_GRADLE_PLUGIN_VERSION,
    val rewriteMavenPluginVersion: String = ToolConfigDefaults.REWRITE_MAVEN_PLUGIN_VERSION,
    val pluginJvmArgs: List<String> = ToolConfigDefaults.PLUGIN_JVM_ARGS,
    val artifactResolverConnectTimeout: Duration =
        ToolConfigDefaults.ARTIFACT_RESOLVER_CONNECT_TIMEOUT,
    val artifactResolverRequestTimeout: Duration =
        ToolConfigDefaults.ARTIFACT_RESOLVER_REQUEST_TIMEOUT,
    val logger: RunnerLogger = NoOpRunnerLogger
) {
    /** Returns [cacheDir] with `~` expanded to the user home directory and environment
     *  variable placeholders replaced. Recipe JARs are cached under the returned path's
     *  `repository/` subdirectory. */
    fun resolvedCacheDir(): Path {
        val dir = interpolateEnvVars(cacheDir, logger)
        return if (dir.startsWith("~")) {
            Paths.get(System.getProperty("user.home"), dir.substring(1))
        } else {
            Paths.get(dir)
        }
    }

    /** Returns [artifactRepositories] with all environment variable placeholders expanded. */
    fun resolvedArtifactRepositories(): List<RepositoryConfig> = artifactRepositories.map { repo ->
        repo.copy(
            url = interpolateEnvVars(repo.url, logger),
            username = repo.username?.let { interpolateEnvVars(it, logger) },
            password = repo.password?.let { interpolateEnvVars(it, logger) }
        )
    }

    /**
     * Resolve plain-text masks with the same override shape as path exclusions:
     * explicit CLI/builder values beat YAML, and both empty falls back to the upstream defaults.
     */
    fun resolvedPlainTextMasks(overridePlainTextMasks: List<String> = emptyList()): List<String> =
        overridePlainTextMasks
            .ifEmpty { parse.plainTextMasks }
            .ifEmpty { ToolConfigDefaults.DEFAULT_PLAIN_TEXT_MASKS }

    companion object {
        private val yaml = YAMLMapper.builder()
            .addModule(KotlinModule.Builder().build())
            .addModule(
                SimpleModule()
                    .addDeserializer(Duration::class.java, DurationConfigDeserializer())
            )
            .build()

        /**
         * Load a [ToolConfig] from [configFile] if it exists, or return a default instance.
         *
         * The YAML text is pre-processed to expand `${VAR_NAME}` placeholders before parsing.
         * Unknown YAML keys are silently ignored.
         *
         * @param configFile Path to `rewriterunner.yml`. May be `null` or point to a
         *   non-existent file; in either case a default [ToolConfig] is returned.
         * @param logger Optional logger for warnings during config loading.
         */
        fun load(configFile: Path?, logger: RunnerLogger): ToolConfig {
            if (configFile != null && configFile.exists()) {
                val rawText = configFile.readText()
                val text = interpolateEnvVars(rawText, logger)
                return try {
                    yaml.readValue(text, ToolConfig::class.java).copy(logger = logger)
                } catch (e: Exception) {
                    throw unwrapConfigException(e)
                }
            }
            return ToolConfig(
                logger = logger
            )
        }

        private fun unwrapConfigException(e: Exception): Exception {
            var cause: Throwable? = e
            while (cause != null) {
                if (cause is IllegalArgumentException) {
                    return IllegalArgumentException(cause.message, cause)
                }
                cause = cause.cause
            }
            return e
        }

        private fun interpolateEnvVars(text: String, logger: RunnerLogger): String =
            Regex("""\$\{([^}]+)}""").replace(text) { match ->
                val varName = match.groupValues[1]
                val value = System.getenv(varName)
                if (value == null) {
                    logger.warn(
                        "Environment variable '$varName' is not set; " +
                            "placeholder '${match.value}' left as-is in config"
                    )
                }
                value ?: match.value
            }
    }
}
