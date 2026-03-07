package org.example.recipe

import java.nio.file.Path
import java.util.logging.Logger
import org.apache.maven.repository.internal.MavenRepositorySystemUtils
import org.eclipse.aether.RepositorySystem
import org.eclipse.aether.RepositorySystemSession
import org.eclipse.aether.artifact.DefaultArtifact
import org.eclipse.aether.collection.CollectRequest
import org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory
import org.eclipse.aether.graph.Dependency
import org.eclipse.aether.repository.LocalRepository
import org.eclipse.aether.repository.RemoteRepository
import org.eclipse.aether.resolution.DependencyRequest
import org.eclipse.aether.resolution.VersionRangeRequest
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory
import org.eclipse.aether.spi.connector.transport.TransporterFactory
import org.eclipse.aether.transport.http.HttpTransporterFactory
import org.example.config.RepositoryConfig

/**
 * Resolves Maven coordinates to local JAR file paths using Eclipse Aether (Maven Resolver).
 *
 * Downloads the requested artifact and its transitive runtime dependencies, caching them
 * in a local Maven repository under [cacheDir]. Maven Central is always included as a
 * remote repository; additional repositories can be supplied via [extraRepositories].
 *
 * @param cacheDir Root directory for the local Maven repository cache. Created if absent.
 * @param extraRepositories Additional remote Maven repositories to query after Maven Central.
 *   Credentials (username/password) from [RepositoryConfig] are applied when present.
 */
class RecipeArtifactResolver(
    private val cacheDir: Path,
    private val extraRepositories: List<RepositoryConfig> = emptyList()
) {
    private val log = Logger.getLogger(RecipeArtifactResolver::class.java.name)

    private val system: RepositorySystem by lazy { newRepositorySystem() }
    private val session: RepositorySystemSession by lazy { newSession(system) }
    private val remoteRepos: List<RemoteRepository> by lazy { buildRemoteRepos() }

    /**
     * Resolve a Maven coordinate (groupId:artifactId:version) to a list of JAR paths
     * (the artifact itself plus its transitive runtime dependencies).
     * Version may be "LATEST" to resolve to the highest available release.
     */
    fun resolve(coordinate: String): List<Path> {
        val parts = coordinate.split(":")
        require(parts.size >= 2) { "Invalid coordinate: $coordinate" }

        val groupId = parts[0]
        val artifactId = parts[1]
        val version = if (parts.size >= 3) parts[2] else "LATEST"

        val resolvedVersion = if (version.equals("LATEST", ignoreCase = true)) {
            resolveLatestVersion(groupId, artifactId)
        } else {
            version
        }

        log.info("Resolving $groupId:$artifactId:$resolvedVersion")

        val artifact = DefaultArtifact("$groupId:$artifactId:$resolvedVersion")
        val dep = Dependency(artifact, "runtime")
        val collectRequest = CollectRequest(dep, remoteRepos)
        val depRequest = DependencyRequest(collectRequest, null)

        val result = system.resolveDependencies(session, depRequest)
        return result.artifactResults.mapNotNull { it.artifact?.file?.toPath() }
    }

    private fun resolveLatestVersion(groupId: String, artifactId: String): String {
        val artifact = DefaultArtifact("$groupId:$artifactId:[0,)")
        val request = VersionRangeRequest(artifact, remoteRepos, null)
        val range = system.resolveVersionRange(session, request)
        val highest = range.highestVersion
            ?: throw IllegalStateException("No versions found for $groupId:$artifactId")
        return highest.toString()
    }

    private fun buildRemoteRepos(): List<RemoteRepository> {
        val repos = mutableListOf(
            RemoteRepository.Builder(
                "central",
                "default",
                "https://repo.maven.apache.org/maven2"
            ).build()
        )
        extraRepositories.forEach { cfg ->
            val builder = RemoteRepository.Builder(
                cfg.url.hashCode().toString(),
                "default",
                cfg.url
            )
            if (cfg.username != null && cfg.password != null) {
                builder.setAuthentication(
                    org.eclipse.aether.util.repository.AuthenticationBuilder()
                        .addUsername(cfg.username)
                        .addPassword(cfg.password)
                        .build()
                )
            }
            repos.add(builder.build())
        }
        return repos
    }

    @Suppress("DEPRECATION")
    private fun newRepositorySystem(): RepositorySystem {
        val locator = MavenRepositorySystemUtils.newServiceLocator()
        locator.addService(
            RepositoryConnectorFactory::class.java,
            BasicRepositoryConnectorFactory::class.java
        )
        locator.addService(TransporterFactory::class.java, HttpTransporterFactory::class.java)
        return locator.getService(RepositorySystem::class.java)
            ?: throw IllegalStateException("Could not create RepositorySystem")
    }

    private fun newSession(system: RepositorySystem): RepositorySystemSession {
        val session = MavenRepositorySystemUtils.newSession()
        val repoDir = cacheDir.resolve("repository").toFile().also { it.mkdirs() }
        val localRepo = LocalRepository(repoDir)
        session.localRepositoryManager = system.newLocalRepositoryManager(session, localRepo)
        return session
    }
}
