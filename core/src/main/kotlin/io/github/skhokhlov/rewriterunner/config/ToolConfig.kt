package io.github.skhokhlov.rewriterunner.config

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.github.skhokhlov.rewriterunner.NoOpRunnerLogger
import io.github.skhokhlov.rewriterunner.RunnerLogger
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.io.path.readText
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
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ToolConfig(
    val repositories: List<RepositoryConfig> = emptyList(),
    val cacheDir: String = "~/.rewriterunner/cache",
    val parse: ParseConfig = ParseConfig(),
    val includeMavenCentral: Boolean = true,
    val downloadThreads: Int = 5
) {
    /** Returns [cacheDir] with `~` expanded to the user home directory and environment
     *  variable placeholders replaced. Recipe JARs are cached under the returned path's
     *  `repository/` subdirectory. */
    fun resolvedCacheDir(): Path {
        val dir = interpolateEnvVars(cacheDir)
        return if (dir.startsWith("~")) {
            Paths.get(System.getProperty("user.home"), dir.substring(1))
        } else {
            Paths.get(dir)
        }
    }

    /** Returns [repositories] with all environment variable placeholders expanded. */
    fun resolvedRepositories(): List<RepositoryConfig> = repositories.map { repo ->
        repo.copy(
            url = interpolateEnvVars(repo.url),
            username = repo.username?.let { interpolateEnvVars(it) },
            password = repo.password?.let { interpolateEnvVars(it) }
        )
    }

    companion object {
        private val yaml = YAMLMapper.builder()
            .addModule(KotlinModule.Builder().build())
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
        fun load(configFile: Path?, logger: RunnerLogger = NoOpRunnerLogger): ToolConfig {
            if (configFile != null && configFile.exists()) {
                val text = interpolateEnvVars(configFile.readText(), logger)
                return yaml.readValue(text, ToolConfig::class.java)
            }
            return ToolConfig()
        }

        private fun interpolateEnvVars(
            text: String,
            logger: RunnerLogger = NoOpRunnerLogger
        ): String = Regex("""\$\{([^}]+)}""").replace(text) { match ->
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
