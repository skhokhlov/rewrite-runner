package io.github.skhokhlov.rewriterunner.recipe

import io.github.skhokhlov.rewriterunner.AetherContext
import io.github.skhokhlov.rewriterunner.MavenCoordinates
import io.github.skhokhlov.rewriterunner.NoOpRunnerLogger
import io.github.skhokhlov.rewriterunner.RunnerLogger
import java.nio.file.Path
import org.eclipse.aether.artifact.DefaultArtifact
import org.eclipse.aether.collection.CollectRequest
import org.eclipse.aether.graph.Dependency
import org.eclipse.aether.resolution.DependencyRequest
import org.eclipse.aether.resolution.DependencyResolutionException
import org.eclipse.aether.resolution.VersionRangeRequest
import org.eclipse.aether.util.filter.ScopeDependencyFilter

/**
 * Resolves Maven coordinates to local JAR file paths using Maven Resolver.
 *
 * Downloads the requested artifact and its transitive runtime dependencies, caching them
 * in the local Maven repository configured on [context]. Maven Central and any extra
 * repositories are supplied via [AetherContext.build].
 *
 * Scope handling: recipe artifact resolution typically prunes `test`, `provided`, and
 * `system` scopes at the Aether session level (see `AetherContext.build`'s
 * `excludeScopesFromGraph` parameter). This resolver also applies a
 * `ScopeDependencyFilter` (excluding `test` and `provided`) on dependency requests so
 * only runtime/compile JARs are returned for recipe loading.
 *
 * @param context Shared Maven Resolver context (system, session, remote repositories).
 *   Use [AetherContext.build] to create one.
 */
open class RecipeArtifactResolver(private val context: AetherContext, val logger: RunnerLogger) {
    private val runtimeScopeFilter = ScopeDependencyFilter(null, listOf("test", "provided"))

    /**
     * Resolve multiple Maven coordinates together in a single dependency graph,
     * so Maven's conflict resolution (highest version wins) applies across all
     * transitive dependencies. Use this when resolving several recipe artifacts
     * that may share common transitive dependencies at different versions.
     *
     * LATEST version is resolved per-coordinate before the combined resolution.
     */
    fun resolveAll(coordinates: List<String>): List<Path> {
        if (coordinates.isEmpty()) return emptyList()

        // Fail fast on malformed Maven coordinates BEFORE any Aether work. LATEST
        // resolution inside [resolveCoordinate] performs a version-range request that
        // can hit the network, so the syntax check must run on the raw user input.
        validateRawCoordinates(coordinates)

        val resolvedCoords = coordinates.map(::resolveCoordinate)
        val deps = resolvedCoords.map { Dependency(DefaultArtifact(it), "runtime") }
        val collectRequest = CollectRequest(deps, emptyList(), context.remoteRepos)
        val depRequest = DependencyRequest(collectRequest, runtimeScopeFilter)

        val paths =
            try {
                context.system
                    .resolveDependencies(context.session, depRequest)
                    .artifactResults
                    .mapNotNull { it.artifact?.path }
            } catch (e: DependencyResolutionException) {
                val partial =
                    e.result?.artifactResults?.mapNotNull { it.artifact?.path }.orEmpty()
                val firstError = e.message?.lineSequence()?.firstOrNull { it.isNotBlank() }
                if (partial.isNotEmpty()) {
                    logger.warn(
                        "Partial resolution " +
                            "(${partial.size} JAR(s) resolved; some transitive deps missing): $firstError"
                    )
                    partial
                } else {
                    logger.error("Cannot resolve recipe artifacts: $firstError")
                    throw e
                }
            }

        logger.info("Resolved ${paths.size} JAR(s) total")
        return paths
    }

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
        validateRawCoordinates(listOf(coordinate))
        val resolvedCoord = resolveCoordinate(coordinate)

        val dep = Dependency(DefaultArtifact(resolvedCoord), "runtime")
        val collectRequest = CollectRequest(dep, context.remoteRepos)
        val depRequest = DependencyRequest(collectRequest, runtimeScopeFilter)

        val paths =
            try {
                context.system
                    .resolveDependencies(context.session, depRequest)
                    .artifactResults
                    .mapNotNull { it.artifact?.path }
            } catch (e: DependencyResolutionException) {
                val partial = e.result?.artifactResults?.mapNotNull { it.artifact?.path }.orEmpty()
                val firstError = e.message?.lineSequence()?.firstOrNull { it.isNotBlank() }
                if (partial.isNotEmpty()) {
                    logger.warn(
                        "Partial resolution for $resolvedCoord " +
                            "(${partial.size} JAR(s) resolved; some transitive deps missing): $firstError"
                    )
                    partial
                } else {
                    logger.error("Cannot resolve $resolvedCoord: $firstError")
                    throw e
                }
            }

        logger.info("      $resolvedCoord → ${paths.size} JAR(s)")
        return paths
    }

    /**
     * Throw a single [IllegalArgumentException] listing every entry in [rawCoords] that
     * is not a well-formed Maven coordinate (e.g. illegal URI characters, fewer than two
     * colon-separated segments). Runs on the *raw* user input so it short-circuits before
     * [resolveCoordinate] can trigger a `LATEST` version-range request via Aether — recipe
     * loading happens before [io.github.skhokhlov.rewriterunner.RunResult] exists, so we
     * cannot surface failures via [io.github.skhokhlov.rewriterunner.ExecutionDiagnostics];
     * fail-fast with a clean message is the user-facing contract.
     */
    private fun validateRawCoordinates(rawCoords: List<String>) {
        val bad = rawCoords.filter { !isWellFormedCoordinate(it) }
        if (bad.isEmpty()) return
        val list = bad.joinToString(", ") { "'$it'" }
        throw IllegalArgumentException(
            "Invalid Maven coordinate(s) $list: contains characters illegal in a URI " +
                "(see DefaultArtifact)"
        )
    }

    /**
     * Versionless coords (`groupId:artifactId`) are valid input — [resolveCoordinate]
     * expands them to LATEST. Probe with a synthetic version so we can validate syntax
     * without performing the actual version-range lookup.
     */
    private fun isWellFormedCoordinate(raw: String): Boolean {
        val parts = raw.split(":")
        if (parts.size < 2) return false
        val probe = if (parts.size >= 3) raw else "$raw:1"
        return MavenCoordinates.tryParse(probe) != null
    }

    private fun resolveCoordinate(coordinate: String): String {
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
        logger.info("Resolving $groupId:$artifactId:$resolvedVersion")
        return "$groupId:$artifactId:$resolvedVersion"
    }

    private fun resolveLatestVersion(groupId: String, artifactId: String): String {
        val artifact = DefaultArtifact("$groupId:$artifactId:[0,)")
        val request = VersionRangeRequest(artifact, context.remoteRepos, null)
        val range = context.system.resolveVersionRange(context.session, request)
        val highest =
            range.highestVersion
                ?: throw IllegalStateException("No versions found for $groupId:$artifactId")
        return highest.toString()
    }
}
