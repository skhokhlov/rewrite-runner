package io.github.skhokhlov.rewriterunner

import io.github.skhokhlov.rewriterunner.config.RepositoryConfig
import java.nio.file.Path
import org.eclipse.aether.ConfigurationProperties
import org.eclipse.aether.RepositorySystem
import org.eclipse.aether.RepositorySystemSession
import org.eclipse.aether.repository.LocalRepository
import org.eclipse.aether.repository.RemoteRepository
import org.eclipse.aether.supplier.RepositorySystemSupplier
import org.eclipse.aether.util.repository.AuthenticationBuilder

/**
 * Bundles the three Maven Resolver objects needed by both [recipe.RecipeArtifactResolver]
 * and [lst.DependencyResolutionStage]: a [RepositorySystem], a [RepositorySystemSession],
 * and the list of configured [RemoteRepository] instances.
 *
 * Create an instance via the [build] factory so both consumers share a single session
 * within a given [io.github.skhokhlov.rewriterunner.OpenRewriteRunner.run] invocation.
 *
 * @param system  The Maven Resolver [RepositorySystem].
 * @param session The Maven Resolver session (includes local repository, timeout config, etc.).
 * @param remoteRepos The remote repositories to query during artifact resolution
 *   (Maven Central plus any extras supplied at build time).
 */
class AetherContext(
    val system: RepositorySystem,
    val session: RepositorySystemSession,
    val remoteRepos: List<RemoteRepository>
) {
    companion object {
        /**
         * Build a shared [AetherContext] from the given configuration.
         *
         * @param cacheDir Root directory for the local Maven repository cache. Created if absent.
         * @param extraRepositories Additional remote Maven repositories beyond Maven Central.
         *   Credentials from [RepositoryConfig] are applied when present.
         * @param connectTimeoutMs TCP connection timeout in milliseconds. Defaults to 30 000.
         * @param requestTimeoutMs Socket read / request timeout in milliseconds. Defaults to
         *   60 000. An explicit value prevents hanging when a remote server accepts the TCP
         *   connection but never sends an HTTP response.
         */
        fun build(
            cacheDir: Path,
            extraRepositories: List<RepositoryConfig> = emptyList(),
            connectTimeoutMs: Int = 30_000,
            requestTimeoutMs: Int = 60_000
        ): AetherContext {
            val system = RepositorySystemSupplier().get()
            val repoDir = cacheDir.resolve("repository").also { it.toFile().mkdirs() }
            val localRepo = LocalRepository(repoDir)
            val session =
                system
                    .createSessionBuilder()
                    .withLocalRepositories(localRepo)
                    .setSystemProperties(System.getProperties())
                    .setConfigProperty(ConfigurationProperties.CONNECT_TIMEOUT, connectTimeoutMs)
                    .setConfigProperty(ConfigurationProperties.REQUEST_TIMEOUT, requestTimeoutMs)
                    // Disable downloading remote prefix-filter index files (Maven Resolver 2.x).
                    // Without this, Maven Resolver downloads large /.index/prefixes.txt files from
                    // each remote repository before resolving any artifacts, causing delays/hangs.
                    .setConfigProperty(
                        "aether.remoteRepositoryFilter.prefixes.resolvePrefixFiles",
                        false
                    )
                    // Ignore <repositories> sections declared in dependency POMs.
                    // Without this, Maven Resolver contacts every third-party repo declared by
                    // transitive dependencies, causing slow resolution or hangs.
                    .setIgnoreArtifactDescriptorRepositories(true)
                    .build()

            val remoteRepos =
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
                        AuthenticationBuilder()
                            .addUsername(cfg.username)
                            .addPassword(cfg.password)
                            .build()
                    )
                }
                remoteRepos.add(builder.build())
            }

            return AetherContext(system, session, remoteRepos)
        }
    }
}
