package io.github.skhokhlov.rewriterunner.config

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.github.skhokhlov.rewriterunner.ExecutionTimeouts
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
 * Controls which file types are included or excluded from parsing, and which file paths
 * are skipped via glob patterns.
 *
 * @property includeExtensions Explicit set of file extensions to parse (e.g. `[".java", ".kt"]`).
 *   When empty, all [io.github.skhokhlov.rewriterunner.lst.LstBuilder] default extensions are used.
 * @property excludeExtensions Extensions to exclude from the default set.
 * @property excludePaths Glob patterns (relative to project root) for paths to skip entirely
 *   (e.g. `["generated/", "vendor/"]`).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ParseConfig(
    val includeExtensions: List<String> = emptyList(),
    val excludeExtensions: List<String> = emptyList(),
    val excludePaths: List<String> = emptyList()
)

/**
 * Top-level tool configuration, typically loaded from `rewriterunner.yml`.
 *
 * Supports environment variable interpolation (`${VAR_NAME}`) and tilde expansion in all
 * string fields. Loaded via [ToolConfig.load]; programmatic library users may also
 * construct instances directly.
 *
 * @property repositories Additional remote Maven repositories for JAR resolution.
 * @property cacheDir Cache root for downloaded recipe JARs. Supports `~` and `${ENV_VAR}`
 *   expansion. Recipe artifacts are stored under `<cacheDir>/repository`, isolated from the
 *   user's Maven local repository. Project dependencies always resolve from `~/.m2/repository`.
 *   Defaults to `~/.rewriterunner/cache`.
 * @property parse File parsing configuration controlling which extensions and paths are
 *   included or excluded from the LST-building stage.
 * @property processTimeout Timeout for build-tool subprocesses in the fallback LST pipeline.
 * @property pluginTimeout Timeout for official Gradle/Maven plugin invocations in Stage 0.
 * @property rewriteGradlePluginVersion Version of the official OpenRewrite Gradle plugin
 *   used by Stage 0 plugin-first execution.
 * @property rewriteMavenPluginVersion Version of the official OpenRewrite Maven plugin
 *   used by Stage 0 plugin-first execution.
 * @property resolverConnectTimeout TCP connection timeout for Maven Resolver downloads.
 * @property resolverRequestTimeout Socket read/request timeout for Maven Resolver downloads.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ToolConfig(
    val repositories: List<RepositoryConfig> = emptyList(),
    val cacheDir: String = "~/.rewriterunner/cache",
    val parse: ParseConfig = ParseConfig(),
    val includeMavenCentral: Boolean = true,
    val downloadThreads: Int = DOWNLOAD_THREADS,
    val processTimeout: Duration = ExecutionTimeouts.DEFAULT_PROCESS_TIMEOUT,
    val pluginTimeout: Duration = ExecutionTimeouts.DEFAULT_PLUGIN_TIMEOUT,
    val rewriteGradlePluginVersion: String = REWRITE_GRADLE_PLUGIN_VERSION,
    val rewriteMavenPluginVersion: String = REWRITE_MAVEN_PLUGIN_VERSION,
    val resolverConnectTimeout: Duration = ExecutionTimeouts.DEFAULT_RESOLVER_CONNECT_TIMEOUT,
    val resolverRequestTimeout: Duration = ExecutionTimeouts.DEFAULT_RESOLVER_REQUEST_TIMEOUT,
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

    /** Returns [repositories] with all environment variable placeholders expanded. */
    fun resolvedRepositories(): List<RepositoryConfig> = repositories.map { repo ->
        repo.copy(
            url = interpolateEnvVars(repo.url, logger),
            username = repo.username?.let { interpolateEnvVars(it, logger) },
            password = repo.password?.let { interpolateEnvVars(it, logger) }
        )
    }

    companion object {
        const val REWRITE_GRADLE_PLUGIN_VERSION = "7.32.1"
        const val REWRITE_MAVEN_PLUGIN_VERSION = "6.38.0"
        const val DOWNLOAD_THREADS = 5

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
                    rejectLegacyTimeoutFields(text)
                    yaml.readValue(text, ToolConfig::class.java).copy(logger = logger)
                } catch (e: Exception) {
                    throw unwrapConfigException(e)
                }
            }
            return ToolConfig(
                logger = logger
            )
        }

        private fun rejectLegacyTimeoutFields(text: String) {
            val parsed = yaml.readValue(text, Map::class.java) ?: return
            val keys = parsed.keys.mapNotNull { it?.toString() }
            val legacyName = keys.firstOrNull { it in LEGACY_TIMEOUT_FIELDS } ?: return
            val replacement = LEGACY_TIMEOUT_FIELDS.getValue(legacyName)
            throw IllegalArgumentException(
                "Legacy timeout field '$legacyName' is no longer supported; " +
                    "use '$replacement' with a Duration value such as 120s, 10m, or 30000ms"
            )
        }

        private val LEGACY_TIMEOUT_FIELDS =
            mapOf(
                "processTimeoutSeconds" to "processTimeout",
                "pluginTimeoutSeconds" to "pluginTimeout",
                "resolverConnectTimeoutMs" to "resolverConnectTimeout",
                "resolverRequestTimeoutMs" to "resolverRequestTimeout"
            )

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
