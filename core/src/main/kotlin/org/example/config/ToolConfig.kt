package org.example.config

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.io.path.readText

@JsonIgnoreProperties(ignoreUnknown = true)
data class RepositoryConfig(
    val url: String = "",
    val username: String? = null,
    val password: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ParseConfig(
    val includeExtensions: List<String> = emptyList(),
    val excludeExtensions: List<String> = emptyList(),
    val excludePaths: List<String> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ToolConfig(
    val repositories: List<RepositoryConfig> = emptyList(),
    val cacheDir: String = "~/.openscript/cache",
    val parse: ParseConfig = ParseConfig(),
) {
    fun resolvedCacheDir(): Path {
        val dir = interpolateEnvVars(cacheDir)
        return if (dir.startsWith("~")) {
            Paths.get(System.getProperty("user.home"), dir.substring(1))
        } else {
            Paths.get(dir)
        }
    }

    fun resolvedRepositories(): List<RepositoryConfig> = repositories.map { repo ->
        repo.copy(
            url = interpolateEnvVars(repo.url),
            username = repo.username?.let { interpolateEnvVars(it) },
            password = repo.password?.let { interpolateEnvVars(it) },
        )
    }

    companion object {
        private val yaml = ObjectMapper(YAMLFactory()).registerKotlinModule()

        fun load(configFile: Path?): ToolConfig {
            if (configFile != null && configFile.exists()) {
                val text = interpolateEnvVars(configFile.readText())
                return yaml.readValue(text, ToolConfig::class.java)
            }
            return ToolConfig()
        }

        private fun interpolateEnvVars(text: String): String =
            Regex("""\$\{([^}]+)}""").replace(text) { match ->
                System.getenv(match.groupValues[1]) ?: match.value
            }
    }
}

fun interpolateEnvVars(text: String): String =
    Regex("""\$\{([^}]+)}""").replace(text) { match ->
        System.getenv(match.groupValues[1]) ?: match.value
    }
