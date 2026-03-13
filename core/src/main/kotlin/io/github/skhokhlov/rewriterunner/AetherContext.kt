package io.github.skhokhlov.rewriterunner

import io.github.skhokhlov.rewriterunner.config.RepositoryConfig
import java.nio.file.Path
import org.eclipse.aether.ConfigurationProperties
import org.eclipse.aether.RepositorySystem
import org.eclipse.aether.RepositorySystemSession
import org.eclipse.aether.repository.LocalRepository
import org.eclipse.aether.repository.RemoteRepository
import org.eclipse.aether.repository.RepositoryPolicy
import org.eclipse.aether.supplier.RepositorySystemSupplier
import org.eclipse.aether.util.graph.selector.AndDependencySelector
import org.eclipse.aether.util.graph.selector.ExclusionDependencySelector
import org.eclipse.aether.util.graph.selector.OptionalDependencySelector
import org.eclipse.aether.util.graph.selector.ScopeDependencySelector
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
         * @param downloadThreads Number of parallel artifact download threads used by the
         *   connector. Defaults to 5. Increase for faster downloads on high-bandwidth networks;
         *   decrease in resource-constrained environments.
         * @param excludeScopesFromGraph Dependency scopes to prune from the collection graph
         *   during POM traversal. Pruned nodes are never collected, so their POM files are
         *   never downloaded. Use `listOf("test", "provided", "system")` for recipe artifact
         *   resolution where only compile/runtime JARs are needed. Leave empty (default) for
         *   project dependency resolution where test-scoped deps are needed for LST type
         *   resolution of test sources.
         */
        fun build(
            localRepoDir: Path,
            extraRepositories: List<RepositoryConfig> = emptyList(),
            connectTimeoutMs: Int = 30_000,
            requestTimeoutMs: Int = 60_000,
            downloadThreads: Int = 5,
            excludeScopesFromGraph: Collection<String> = emptyList(),
            includeMavenCentral: Boolean = true,
            logger: RunnerLogger = NoOpRunnerLogger
        ): AetherContext {
            val system = RepositorySystemSupplier().get()
            localRepoDir.toFile().mkdirs()
            val localRepo = LocalRepository(localRepoDir)
            val session = system
                .createSessionBuilder()
                .withLocalRepositories(localRepo)
                .setSystemProperties(System.getProperties())
                .setTransferListener(MavenTransferListener(logger))
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
                // Prune the dependency collection graph to skip downloading POMs for nodes
                // that will never be used:
                //  - OptionalDependencySelector: skips optional transitive deps.
                //  - ExclusionDependencySelector: respects <exclusions> declared in POMs.
                //  - ScopeDependencySelector (when excludeScopesFromGraph is non-empty):
                //    prevents collecting nodes whose scope is in the excluded set, so their
                //    POMs are never fetched. Used for recipe resolution where test/provided/
                //    system transitive deps are useless.
                .setDependencySelector(
                    if (excludeScopesFromGraph.isEmpty()) {
                        AndDependencySelector(
                            OptionalDependencySelector(),
                            ExclusionDependencySelector()
                        )
                    } else {
                        AndDependencySelector(
                            ScopeDependencySelector(null, excludeScopesFromGraph),
                            OptionalDependencySelector(),
                            ExclusionDependencySelector()
                        )
                    }
                )
                .setConfigProperty(ConfigurationProperties.CONNECT_TIMEOUT, connectTimeoutMs)
                .setConfigProperty(ConfigurationProperties.REQUEST_TIMEOUT, requestTimeoutMs)
                .setConfigProperty("aether.connector.basic.threads", downloadThreads)
                // Disable downloading remote prefix-filter index files (Maven Resolver 2.x).
                // Without this, Maven Resolver downloads large /.index/prefixes.txt files from
                // each remote repository before resolving any artifacts, causing delays/hangs.
                .setConfigProperty(
                    "aether.remoteRepositoryFilter.prefixes.resolvePrefixFiles",
                    false
                )
                // Bypass per-session update rechecks. Once an artifact's update status has been
                // checked in the current JVM session, skip re-checking it for subsequent requests.
                .setConfigProperty("aether.updateCheckManager.sessionState", "bypass")
                // Ignore <repositories> sections declared in dependency POMs.
                // Without this, Maven Resolver contacts every third-party repo declared by
                // transitive dependencies, causing slow resolution or hangs.
                .setIgnoreArtifactDescriptorRepositories(true)
                .build()

            // Trust policy applied to all repositories:
            // - UPDATE_POLICY_DAILY: once an artifact is in the local cache it is refetched from remote daily, eliminating redundant HEAD/GET checks on re-runs.
            // - CHECKSUM_POLICY_IGNORE: do not fail or re-download when a repository omits
            //   checksum files (common on corporate proxies and private registries).
            val trustPolicy = RepositoryPolicy(
                true,
                RepositoryPolicy.UPDATE_POLICY_DAILY,
                RepositoryPolicy.CHECKSUM_POLICY_IGNORE
            )
            val remoteRepos = mutableListOf<RemoteRepository>()
            if (includeMavenCentral) {
                remoteRepos.add(
                    RemoteRepository.Builder(
                        "central",
                        "default",
                        "https://repo.maven.apache.org/maven2"
                    ).setPolicy(trustPolicy).build()
                )
            }
            extraRepositories.forEachIndexed { index, cfg ->
                // Use a stable, URL-safe ID: "extra-0", "extra-1", etc.
                val builder = RemoteRepository.Builder(
                    "extra-$index",
                    "default",
                    cfg.url
                ).setPolicy(trustPolicy)
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
