package io.github.skhokhlov.rewriterunner.recipe

import io.github.skhokhlov.rewriterunner.AetherContext
import java.nio.file.Path
import org.eclipse.aether.artifact.DefaultArtifact
import org.eclipse.aether.collection.CollectRequest
import org.eclipse.aether.graph.Dependency
import org.eclipse.aether.resolution.DependencyRequest
import org.eclipse.aether.resolution.DependencyResolutionException
import org.eclipse.aether.resolution.VersionRangeRequest
import org.eclipse.aether.util.artifact.JavaScopes
import org.eclipse.aether.util.filter.DependencyFilterUtils
import org.slf4j.LoggerFactory

/**
 * Resolves Maven coordinates to local JAR file paths using Maven Resolver.
 *
 * Downloads the requested artifact and its transitive runtime dependencies, caching them
 * in the local Maven repository configured on [context]. Maven Central and any extra
 * repositories are supplied via [AetherContext.build].
 *
 * @param context Shared Maven Resolver context (system, session, remote repositories).
 *   Use [AetherContext.build] to create one.
 */
open class RecipeArtifactResolver(private val context: AetherContext) {
    private val log = LoggerFactory.getLogger(RecipeArtifactResolver::class.java.name)

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
        val dep = Dependency(artifact, JavaScopes.RUNTIME)
        val collectRequest = CollectRequest(dep, context.remoteRepos)
        val classpathFilter = DependencyFilterUtils.classpathFilter(
            JavaScopes.COMPILE,
            JavaScopes.RUNTIME
        )
        val depRequest = DependencyRequest(collectRequest, classpathFilter)

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
                    log.warn(
                        "Partial resolution for $groupId:$artifactId:$resolvedVersion " +
                            "(${partial.size} JAR(s) resolved; some transitive deps missing): $firstError"
                    )
                    partial
                } else {
                    log.error(
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
        val request = VersionRangeRequest(artifact, context.remoteRepos, null)
        val range = context.system.resolveVersionRange(context.session, request)
        val highest =
            range.highestVersion
                ?: throw IllegalStateException("No versions found for $groupId:$artifactId")
        return highest.toString()
    }
}
