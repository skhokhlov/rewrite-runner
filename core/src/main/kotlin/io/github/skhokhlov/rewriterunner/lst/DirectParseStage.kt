package io.github.skhokhlov.rewriterunner.lst

import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import org.slf4j.LoggerFactory

/**
 * Stage 3: Assemble the best available classpath from local caches without
 * downloading anything. Uses entries already present in:
 * - `~/.m2/repository` and `[projectDir]/.m2/repository`
 * - `~/.gradle/caches` and `[projectDir]/.gradle/caches`
 *
 * Project-local roots support Gradle's `gradle.user.home` being set to a directory
 * inside the project (e.g. `gradle.properties: gradle.user.home=.gradle`), which places
 * the Gradle dependency cache under the project tree rather than the global home directory.
 */
class DirectParseStage(private val projectDir: Path) {
    private val log = LoggerFactory.getLogger(DirectParseStage::class.java.name)

    private val m2Roots: List<Path> = listOf(
        Paths.get(System.getProperty("user.home"), ".m2", "repository"),
        projectDir.resolve(".m2").resolve("repository")
    )

    private val gradleCacheRoots: List<Path> = listOf(
        Paths.get(System.getProperty("user.home"), ".gradle", "caches"),
        projectDir.resolve(".gradle").resolve("caches")
    )

    /**
     * Return any JARs we can locate in local caches that correspond to the project's
     * declared dependencies. Unresolved entries become JavaType.Unknown at parse time.
     */
    fun findAvailableJars(declaredCoordinates: List<String>): List<Path> {
        val found = mutableListOf<Path>()
        val notFound = mutableListOf<String>()

        for (coord in declaredCoordinates) {
            val jar = findInM2(coord) ?: findInGradleCache(coord)
            if (jar != null) {
                found.add(jar)
            } else {
                notFound.add(coord)
            }
        }

        if (notFound.isNotEmpty()) {
            log.warn(
                "Stage 3 — could not locate ${notFound.size} JAR(s) in local caches. " +
                    "Affected types will be JavaType.Unknown:\n  " + notFound.joinToString("\n  ")
            )
        }

        log.info("Stage 3 — using ${found.size} locally cached JAR(s)")
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
        return Coord(parts[0], parts[1], parts[2])
    }
}
