package org.example.recipe

import java.nio.file.Path
import java.util.logging.Logger
import org.eclipse.aether.ConfigurationProperties
import org.eclipse.aether.RepositorySystem
import org.eclipse.aether.RepositorySystemSession
import org.eclipse.aether.artifact.DefaultArtifact
import org.eclipse.aether.collection.CollectRequest
import org.eclipse.aether.graph.Dependency
import org.eclipse.aether.repository.LocalRepository
import org.eclipse.aether.repository.RemoteRepository
import org.eclipse.aether.resolution.DependencyRequest
import org.eclipse.aether.resolution.DependencyResolutionException
import org.eclipse.aether.resolution.VersionRangeRequest
import org.eclipse.aether.supplier.RepositorySystemSupplier
import org.example.config.RepositoryConfig

/**
 * Resolves Maven coordinates to local JAR file paths using Maven Resolver.
 *
 * Downloads the requested artifact and its transitive runtime dependencies, caching them
 * in a local Maven repository under [cacheDir]. Maven Central is always included as a
 * remote repository; additional repositories can be supplied via [extraRepositories].
 *
 * @param cacheDir Root directory for the local Maven repository cache. Created if absent.
 * @param extraRepositories Additional remote Maven repositories to query after Maven Central.
 *   Credentials (username/password) from [RepositoryConfig] are applied when present.
 * @param connectTimeoutMs TCP connection timeout in milliseconds. Defaults to 30 000 (30 s).
 * @param requestTimeoutMs Socket read / request timeout in milliseconds. Defaults to 60 000
 *   (60 s). Maven Resolver's built-in default is 30 minutes; an explicit value is required to
 *   prevent the process from hanging indefinitely when a remote server accepts the TCP
 *   connection but never sends an HTTP response.
 */
open class RecipeArtifactResolver(
    private val cacheDir: Path,
    private val extraRepositories: List<RepositoryConfig> = emptyList(),
    private val connectTimeoutMs: Int = 30_000,
    private val requestTimeoutMs: Int = 60_000
) {
    private val log = Logger.getLogger(RecipeArtifactResolver::class.java.name)

    private val system: RepositorySystem by lazy { newRepositorySystem() }
    private val session: RepositorySystemSession by lazy { buildSession(system) }
    private val remoteRepos: List<RemoteRepository> by lazy { buildRemoteRepos() }

    /**
     * Resolve a Maven coordinate (groupId:artifactId:version) to a list of JAR paths
     * (the artifact itself plus its transitive runtime dependencies).
     * Version may be "LATEST" to resolve to the highest available release.
     *
     * When dependency collection encounters errors (e.g. an optional transitive dep points
     * to a private-repo artifact unavailable on Maven Central), the method logs a warning
     * and returns whichever JARs were successfully resolved, rather than throwing.
     */
    fun resolve(coordinate: String): List<Path> {
        val parts = coordinate.split(":")
        require(parts.size >= 2) { "Invalid coordinate: $coordinate" }

        val groupId = parts[0]
        val artifactId = parts[1]
        val version = if (parts.size >= 3) parts[2] else "LATEST"

        val resolvedVersion =
            if (version.equals("LATEST", ignoreCase = true)) {
                resolveLatestVersion(groupId, artifactId)
            } else {
                version
            }

        log.info("Resolving $groupId:$artifactId:$resolvedVersion")

        val artifact = DefaultArtifact("$groupId:$artifactId:$resolvedVersion")
        val dep = Dependency(artifact, "runtime")
        val collectRequest = CollectRequest(dep, remoteRepos)
        val depRequest = DependencyRequest(collectRequest, null)

        val paths =
            try {
                system
                    .resolveDependencies(session, depRequest)
                    .artifactResults
                    .mapNotNull { it.artifact?.path }
            } catch (e: DependencyResolutionException) {
                val partial = e.result?.artifactResults?.mapNotNull { it.artifact?.path }.orEmpty()
                val firstError = e.message?.lineSequence()?.firstOrNull { it.isNotBlank() }
                if (partial.isNotEmpty()) {
                    log.warning(
                        "Partial resolution for $groupId:$artifactId:$resolvedVersion " +
                            "(${partial.size} JAR(s) resolved; some transitive deps missing): $firstError"
                    )
                    partial
                } else {
                    log.severe(
                        "Cannot resolve $groupId:$artifactId:$resolvedVersion: $firstError"
                    )
                    throw e
                }
            }

        log.info("      $groupId:$artifactId:$resolvedVersion → ${paths.size} JAR(s)")
        return paths
    }

    private fun resolveLatestVersion(groupId: String, artifactId: String): String {
        val artifact = DefaultArtifact("$groupId:$artifactId:[0,)")
        val request = VersionRangeRequest(artifact, remoteRepos, null)
        val range = system.resolveVersionRange(session, request)
        val highest =
            range.highestVersion
                ?: throw IllegalStateException("No versions found for $groupId:$artifactId")
        return highest.toString()
    }

    protected open fun buildRemoteRepos(): List<RemoteRepository> {
        val repos =
            mutableListOf(
                RemoteRepository.Builder(
                    "central",
                    "default",
                    "https://repo.maven.apache.org/maven2"
                )
                    .build()
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

    protected open fun buildSession(system: RepositorySystem): RepositorySystemSession {
        val repoDir = cacheDir.resolve("repository").also { it.toFile().mkdirs() }
        val localRepo = LocalRepository(repoDir)
        return system
            .createSessionBuilder()
            .withLocalRepositories(localRepo)
            .setSystemProperties(System.getProperties())
            .setConfigProperty(ConfigurationProperties.CONNECT_TIMEOUT, connectTimeoutMs)
            .setConfigProperty(ConfigurationProperties.REQUEST_TIMEOUT, requestTimeoutMs)
            .build()
    }

    private fun newRepositorySystem(): RepositorySystem = RepositorySystemSupplier().get()
}
