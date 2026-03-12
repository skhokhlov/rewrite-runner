package io.github.skhokhlov.rewriterunner

import io.github.skhokhlov.rewriterunner.config.RepositoryConfig
import java.nio.file.Path
import org.eclipse.aether.ConfigurationProperties
import org.eclipse.aether.RepositorySystem
import org.eclipse.aether.RepositorySystemSession
import org.eclipse.aether.repository.LocalRepository
import org.eclipse.aether.repository.RemoteRepository
import org.eclipse.aether.supplier.RepositorySystemSupplier
import org.eclipse.aether.util.graph.transformer.ClassicConflictResolver
import org.eclipse.aether.util.graph.transformer.JavaScopeDeriver
import org.eclipse.aether.util.graph.transformer.JavaScopeSelector
import org.eclipse.aether.util.graph.transformer.NearestVersionSelector
import org.eclipse.aether.util.graph.transformer.SimpleOptionalitySelector
import org.eclipse.aether.util.repository.AuthenticationBuilder

/**
 * Bundles the three Maven Resolver objects needed by [io.github.skhokhlov.rewriterunner.recipe.RecipeArtifactResolver]
 * and [io.github.skhokhlov.rewriterunner.lst.DependencyResolutionStage]: a [RepositorySystem], a
 * [RepositorySystemSession], and the list of configured [RemoteRepository] instances.
 *
 * **Two separate instances** are created per [io.github.skhokhlov.rewriterunner.RewriteRunner.run] invocation,
 * each with a distinct local Maven repository:
 * - **Recipe context** — local repo at `<cacheDir>/repository`, used by [io.github.skhokhlov.rewriterunner.recipe.RecipeArtifactResolver].
 *   Recipe JARs are kept in the tool's own cache and never written to the user's Maven local repo.
 * - **Project context** — local repo at `~/.m2/repository` (Maven default), used by
 *   [io.github.skhokhlov.rewriterunner.lst.DependencyResolutionStage]. Reuses artifacts already cached by
 *   the project's own build without re-downloading them.
 *
 * Create instances via the [build] factory, passing the desired [localRepoDir] explicitly.
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
         * @param localRepoDir Directory used as the local Maven repository. Created if absent.
         *   Use a subdirectory of the tool cache to isolate recipe JARs, or the Maven
         *   default (`~/.m2/repository`) to reuse the user's existing local repository.
         * @param extraRepositories Additional remote Maven repositories beyond Maven Central.
         *   Credentials from [RepositoryConfig] are applied when present.
         * @param connectTimeoutMs TCP connection timeout in milliseconds. Defaults to 30 000.
         * @param requestTimeoutMs Socket read / request timeout in milliseconds. Defaults to
         *   60 000. An explicit value prevents hanging when a remote server accepts the TCP
         *   connection but never sends an HTTP response.
         */
        fun build(
            localRepoDir: Path,
            extraRepositories: List<RepositoryConfig> = emptyList(),
            connectTimeoutMs: Int = 30_000,
            requestTimeoutMs: Int = 60_000,
            includeMavenCentral: Boolean = true
        ): AetherContext {
            val system = RepositorySystemSupplier().get()
            localRepoDir.toFile().mkdirs()
            val localRepo = LocalRepository(localRepoDir)
            val session = system
                .createSessionBuilder()
                .withLocalRepositories(localRepo)
                .setSystemProperties(System.getProperties())
                .setTransferListener(MavenTransferListener())
                // Enable Maven's standard conflict resolution so that when the dependency
                // graph contains the same artifact at different versions, only one version
                // is selected (nearest-wins, matching Maven's default behavior). Without
                // this, Maven Resolver 2.x returns ALL versions, causing duplicate JARs
                // on the classpath and unnecessary downloads.
                .setDependencyGraphTransformer(
                    @Suppress("DEPRECATION")
                    ClassicConflictResolver(
                        NearestVersionSelector(),
                        JavaScopeSelector(),
                        SimpleOptionalitySelector(),
                        JavaScopeDeriver()
                    )
                )
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

            val remoteRepos = mutableListOf<RemoteRepository>()
            if (includeMavenCentral) {
                remoteRepos.add(
                    RemoteRepository.Builder(
                        "central",
                        "default",
                        "https://repo.maven.apache.org/maven2"
                    ).build()
                )
            }
            extraRepositories.forEachIndexed { index, cfg ->
                // Use a stable, URL-safe ID: "extra-0", "extra-1", etc.
                // hashCode() was previously used but can produce negative integers which
                // may confuse Maven Resolver's repository identity tracking.
                val builder = RemoteRepository.Builder(
                    "extra-$index",
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
