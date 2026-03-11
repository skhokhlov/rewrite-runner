package io.github.skhokhlov.rewriterunner.config

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.io.path.readText
import org.slf4j.LoggerFactory
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
 * Top-level tool configuration, typically loaded from `rewrite-runner.yml`.
 *
 * Supports environment variable interpolation (`${VAR_NAME}`) and tilde expansion in all
 * string fields. Loaded via [ToolConfig.load]; programmatic library users may also
 * construct instances directly.
 *
 * @property repositories Additional remote Maven repositories for JAR resolution.
 * @property cacheDir Local cache directory for downloaded JARs. Supports `~` expansion.
 *   Defaults to `~/.rewriterunner/cache`.
 * @property parse File parsing configuration controlling which extensions and paths are
 *   included or excluded from the LST-building stage.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ToolConfig(
    val repositories: List<RepositoryConfig> = emptyList(),
    val cacheDir: String = "~/.rewriterunner/cache",
    val parse: ParseConfig = ParseConfig()
) {
    /** Returns [cacheDir] with `~` expanded to the user home directory and environment
     *  variable placeholders replaced. */
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
        private val log = LoggerFactory.getLogger(ToolConfig::class.java.name)
        private val yaml = YAMLMapper.builder()
            .addModule(KotlinModule.Builder().build())
            .build()

        /**
         * Load a [ToolConfig] from [configFile] if it exists, or return a default instance.
         *
         * The YAML text is pre-processed to expand `${VAR_NAME}` placeholders before parsing.
         * Unknown YAML keys are silently ignored.
         *
         * @param configFile Path to `rewrite-runner.yml`. May be `null` or point to a
         *   non-existent file; in either case a default [ToolConfig] is returned.
         */
        fun load(configFile: Path?): ToolConfig {
            if (configFile != null && configFile.exists()) {
                val text = interpolateEnvVars(configFile.readText())
                return yaml.readValue(text, ToolConfig::class.java)
            }
            return ToolConfig()
        }

        private fun interpolateEnvVars(text: String): String =
            Regex("""\$\{([^}]+)}""").replace(text) { match ->
                val varName = match.groupValues[1]
                val value = System.getenv(varName)
                if (value == null) {
                    log.warn(
                        "Environment variable '$varName' is not set; " +
                            "placeholder '${match.value}' left as-is in config"
                    )
                }
                value ?: match.value
            }
    }
}
