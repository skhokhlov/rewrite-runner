package io.github.skhokhlov.rewriterunner.plugin

import java.nio.file.Path

/** Parses OpenRewrite's unified git patch output into one diff string per changed file. */
internal object PatchParser {
    private val diffHeader = Regex("(?m)^diff --git ")
    private val oldPathHeader = Regex("""(?m)^---\s+(.+)$""")
    private val newPathHeader = Regex("""(?m)^\+\+\+\s+(.+)$""")

    fun parse(content: String): Map<Path, String> {
        if (content.isBlank()) return emptyMap()

        val starts = diffHeader.findAll(content).map { it.range.first }.toList()
        if (starts.isEmpty()) return emptyMap()

        return starts.mapIndexedNotNull { index, start ->
            val end = starts.getOrNull(index + 1) ?: content.length
            val diff = content.substring(start, end).trimEnd()
            val path = extractPath(diff) ?: return@mapIndexedNotNull null
            path to "$diff\n"
        }.toMap()
    }

    private fun extractPath(diff: String): Path? {
        val oldPath = oldPathHeader.find(diff)?.groupValues?.get(1)?.normalizeDiffPath()
        val newPath = newPathHeader.find(diff)?.groupValues?.get(1)?.normalizeDiffPath()
        return (newPath ?: oldPath)?.let(Path::of)
    }

    private fun String.normalizeDiffPath(): String? {
        val path = substringBefore('\t').trim()
        if (path == "/dev/null") return null
        return path.removePrefix("a/").removePrefix("b/")
    }
}
