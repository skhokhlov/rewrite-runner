package io.github.skhokhlov.rewriterunner

import org.eclipse.aether.artifact.DefaultArtifact

/**
 * Safe construction of [DefaultArtifact] from a `groupId:artifactId[:version]` coordinate.
 *
 * `DefaultArtifact` throws an unchecked [IllegalArgumentException] (cause:
 * [java.net.URISyntaxException]) when the coordinate string contains a character that
 * is illegal in a URI — Aether builds a URI internally from the coordinate, and any
 * malformed input there aborts an entire classpath resolution or recipe load.
 *
 * This helper catches that failure so callers can skip bad coordinates and record them
 * via [ParseFailure] instead of crashing. It does not validate semantic correctness
 * beyond what `DefaultArtifact` already enforces.
 */
object MavenCoordinates {
    /**
     * @return a [DefaultArtifact] for [coordinate], or `null` when the coordinate
     *   contains a character illegal in a URI or is otherwise rejected by
     *   `DefaultArtifact`'s constructor.
     */
    fun tryParse(coordinate: String): DefaultArtifact? = try {
        DefaultArtifact(coordinate)
    } catch (_: IllegalArgumentException) {
        null
    }
}
