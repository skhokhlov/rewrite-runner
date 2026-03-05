package org.example.lst

import java.nio.file.Path
import java.nio.file.Paths
import java.util.logging.Logger
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/**
 * Stage 3: Assemble the best available classpath from local caches without
 * downloading anything. Uses entries already present in ~/.m2 and ~/.gradle/caches.
 */
class DirectParseStage(
    private val projectDir: Path,
) {
    private val log = Logger.getLogger(DirectParseStage::class.java.name)

    private val m2Root: Path = Paths.get(System.getProperty("user.home"), ".m2", "repository")
    private val gradleCacheRoot: Path = Paths.get(System.getProperty("user.home"), ".gradle", "caches")

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
            log.warning(
                "Stage 3 — could not locate ${notFound.size} JAR(s) in local caches. " +
                    "Affected types will be JavaType.Unknown:\n  " + notFound.joinToString("\n  ")
            )
        }

        log.info("Stage 3 — using ${found.size} locally cached JAR(s)")
        return found
    }

    // ─── ~/.m2 ────────────────────────────────────────────────────────────────

    private fun findInM2(coordinate: String): Path? {
        val (groupId, artifactId, version) = parseCoord(coordinate) ?: return null
        val jar = m2Root
            .resolve(groupId.replace('.', '/'))
            .resolve(artifactId)
            .resolve(version)
            .resolve("$artifactId-$version.jar")
        return jar.takeIf { it.exists() }
    }

    // ─── ~/.gradle/caches ────────────────────────────────────────────────────

    private fun findInGradleCache(coordinate: String): Path? {
        val (groupId, artifactId, version) = parseCoord(coordinate) ?: return null
        if (!gradleCacheRoot.exists()) return null

        // Walk modules-*/files-*/<groupId>/<artifactId>/<version>/**/*.jar
        return gradleCacheRoot.toFile()
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
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private data class Coord(val groupId: String, val artifactId: String, val version: String)

    private fun parseCoord(coordinate: String): Coord? {
        val parts = coordinate.split(":")
        if (parts.size < 3) return null
        return Coord(parts[0], parts[1], parts[2])
    }
}
