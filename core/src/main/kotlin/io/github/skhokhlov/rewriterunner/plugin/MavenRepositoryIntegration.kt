package io.github.skhokhlov.rewriterunner.plugin

import io.github.skhokhlov.rewriterunner.config.RepositoryConfig
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.UUID

internal class MavenRepositoryIntegrationException(message: String) :
    IllegalArgumentException(message)

/** Private transport for a Maven core extension; never replaces either Maven settings file. */
internal class MavenRepositoryIntegration(
    private val directory: Path,
    includeMavenCentral: Boolean,
    repositories: List<RepositoryConfig>
) {
    val arguments: List<String>
    val ready: Boolean get() = Files.exists(directory.resolve("repositories-ready"))

    init {
        val jar = createPrivateTempFile(directory, "repositories-", ".jar")
        requireNotNull(javaClass.getResourceAsStream("/maven-repositories-extension.jar")) {
            "Maven repository extension resource is missing"
        }.use { input -> Files.newOutputStream(jar).use { input.copyTo(it) } }
        val config = createPrivateTempFile(directory, "repositories-", ".properties")
        val sources = repositories + if (includeMavenCentral) {
            listOf(RepositoryConfig("https://repo.maven.apache.org/maven2"))
        } else {
            emptyList()
        }
        val properties = Properties()
        properties.setProperty("prefix", "rewrite-runner-${UUID.randomUUID()}")
        properties.setProperty("count", sources.size.toString())
        sources.forEachIndexed { index, repo ->
            val uri = URI(repo.url)
            if (!uri.isAbsolute || uri.rawUserInfo != null || uri.rawQuery != null ||
                uri.rawFragment != null
            ) {
                throw MavenRepositoryIntegrationException(
                    "Repository URLs must be absolute and must not contain " +
                        "credentials, query strings or fragments"
                )
            }
            properties.setProperty("repo.$index.url", repo.url)
            repo.username?.let { properties.setProperty("repo.$index.username", it) }
            repo.password?.let { properties.setProperty("repo.$index.password", it) }
        }
        Files.newOutputStream(config).use { properties.store(it, null) }
        arguments = listOf(
            "-Dmaven.ext.class.path=${jar.toAbsolutePath()}",
            "-Drewrite.runner.repositories=${config.toAbsolutePath()}"
        )
    }
}
