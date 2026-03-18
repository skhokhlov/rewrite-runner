package io.github.skhokhlov.rewriterunner.lst

import io.github.skhokhlov.rewriterunner.RunnerLogger
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists

/**
 * Stage 4 of the LST classpath-resolution pipeline: assemble the best available
 * classpath from local caches, **without downloading anything**.
 *
 * **Why Stage 4?**
 * Stage 1 ([ProjectBuildStage]) and Stage 2 ([DependencyResolutionStage]) can both fail —
 * for example, in completely offline environments, in repositories with no network
 * access, or when the build tool is not installed and the declared dependencies cannot
 * be downloaded by Maven Resolver. Stage 3 is the last-resort fallback: it reuses
 * JARs that are already cached locally from previous builds or downloads, requiring
 * no subprocess invocation and no network access.
 *
 * **How it works:**
 * Given a list of `groupId:artifactId:version` coordinates (extracted from the build
 * descriptor by [DependencyResolutionStage.parseMavenDependencies] /
 * [DependencyResolutionStage.parseGradleDependencies]), Stage 3 looks up each
 * coordinate in local repository caches using the standard Maven path layout
 * (`<group>/<artifact>/<version>/<artifact>-<version>.jar`):
 *
 * 1. `~/.m2/repository` — the global Maven local repository.
 * 2. `<projectDir>/.m2/repository` — a project-local Maven repository.
 * 3. `~/.gradle/caches` — the Gradle dependency cache (module files store).
 * 4. `<projectDir>/.gradle/caches` — a project-local Gradle cache, used when
 *    `gradle.user.home` is set to a directory inside the project tree.
 *
 * **Impact of missing JARs:**
 * When a coordinate is not found in any cache, the corresponding types in source
 * files will resolve to `JavaType.Unknown` during OpenRewrite's type-attribution
 * phase. This does **not** prevent parsing or recipe execution — recipes that do
 * not rely on type information for those specific types will still work correctly.
 * Missing types are reported as warnings in the log so users can identify which
 * dependencies were unresolvable.
 *
 * **Note:** Stage 4 never downloads JARs. If dependencies are missing from all
 * local caches, run the project's normal build (`mvn package`, `gradle build`)
 * first to populate the caches, then re-run the tool.
 *
 * @param projectDir Root directory of the project, used to locate project-local
 *   cache roots (`.m2/repository`, `.gradle/caches`).
 */
class LocalRepositoryStage(private val projectDir: Path, val logger: RunnerLogger) {
    private val m2Roots: List<Path> = listOf(
        Paths.get(System.getProperty("user.home"), ".m2", "repository"),
        projectDir.resolve(".m2").resolve("repository")
    )

    private val gradleCacheRoots: List<Path> = listOf(
        Paths.get(System.getProperty("user.home"), ".gradle", "caches"),
        projectDir.resolve(".gradle").resolve("caches")
    )

    /**
     * Locates JARs for the given Maven coordinates in local caches, returning all that
     * can be found without any network access.
     *
     * Each coordinate is looked up first in `~/.m2/repository` (and
     * `<projectDir>/.m2/repository`), then in `~/.gradle/caches` (and
     * `<projectDir>/.gradle/caches`). Coordinates not found in any cache are logged
     * as a single warning listing all missing artifacts.
     *
     * @param declaredCoordinates List of `groupId:artifactId:version` strings to look
     *   up (typically the output of [DependencyResolutionStage.parseMavenDependencies]
     *   or [DependencyResolutionStage.parseGradleDependencies]).
     * @return Paths of locally cached JARs, one per successfully located coordinate.
     *   Coordinates with no local cache hit are omitted; their types will appear as
     *   `JavaType.Unknown` in the LST.
     */
    fun findAvailableJars(declaredCoordinates: List<String>): List<Path> {
        val found = mutableListOf<Path>()
        val notFound = mutableListOf<String>()

        for (coord in declaredCoordinates) {
            if (parseCoord(coord) == null) continue // malformed — skip silently
            val jar = findInM2(coord) ?: findInGradleCache(coord)
            if (jar != null) {
                found.add(jar)
            } else {
                notFound.add(coord)
            }
        }

        if (notFound.isNotEmpty()) {
            logger.warn(
                "Stage 3 — could not locate ${notFound.size} JAR(s) in local caches. " +
                    "Affected types will be JavaType.Unknown:\n  " + notFound.joinToString("\n  ")
            )
        }

        logger.info("Stage 3 — using ${found.size} locally cached JAR(s)")
        return found
    }

    // ─── ~/.m2 + projectDir/.m2 ───────────────────────────────────────────────

    private fun findInM2(coordinate: String): Path? {
        val (groupId, artifactId, version) = parseCoord(coordinate) ?: return null
        for (root in m2Roots) {
            val jar = root
                .resolve(groupId.replace('.', '/'))
                .resolve(artifactId)
                .resolve(version)
                .resolve("$artifactId-$version.jar")
            if (jar.exists()) return jar
        }
        return null
    }

    // ─── ~/.gradle/caches + projectDir/.gradle/caches ────────────────────────

    private fun findInGradleCache(coordinate: String): Path? {
        val (groupId, artifactId, version) = parseCoord(coordinate) ?: return null
        for (root in gradleCacheRoots) {
            if (!root.exists()) continue
            // Walk modules-*/files-*/<groupId>/<artifactId>/<version>/**/*.jar
            val jar = root.toFile()
                .walkTopDown()
                .maxDepth(8)
                .filter { file ->
                    file.isFile &&
                        file.extension == "jar" &&
                        file.name.startsWith("$artifactId-$version") &&
                        file.path.contains("/$groupId/") &&
                        file.path.contains("/$artifactId/")
                }
                .map { it.toPath() }
                .firstOrNull()
            if (jar != null) return jar
        }
        return null
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private data class Coord(val groupId: String, val artifactId: String, val version: String)

    private fun parseCoord(coordinate: String): Coord? {
        val parts = coordinate.split(":")
        if (parts.size < 3) return null
        if (parts.any { it.isBlank() }) return null
        return Coord(parts[0], parts[1], parts[2])
    }
}
