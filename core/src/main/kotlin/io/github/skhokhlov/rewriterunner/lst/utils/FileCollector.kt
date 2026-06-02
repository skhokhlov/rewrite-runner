package io.github.skhokhlov.rewriterunner.lst.utils

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.PathMatcher
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/**
 * Walks a project tree and buckets collected files by extension key.
 *
 * Applies excluded-directory filtering, glob-pattern exclusions, and Dockerfile
 * name-based detection on top of the regular extension matching.
 */
internal class FileCollector(
    private val defaultExtensions: Set<String> = DEFAULT_EXTENSIONS,
    private val excludedDirNames: Set<String> = DEFAULT_EXCLUDED_DIRS
) {
    companion object {
        const val PLAIN_TEXT: String = ".plaintext"
        const val PLAIN_TEXT_SIZE_THRESHOLD_MB: Int = 10

        private const val BYTES_PER_MB: Long = 1024L * 1024L

        val DEFAULT_EXTENSIONS: Set<String> = setOf(
            ".java",
            ".kt",
            ".kts",
            ".groovy",
            ".gradle",
            ".yaml",
            ".yml",
            ".json",
            ".xml",
            ".properties",
            ".toml",
            ".hcl",
            ".tf",
            ".tfvars",
            ".proto",
            ".dockerfile",
            ".containerfile"
        )

        val DEFAULT_EXCLUDED_DIRS: Set<String> = setOf(
            ".git",
            "build",
            "target",
            "node_modules",
            ".gradle",
            ".idea",
            "out",
            "dist"
        )
    }

    /**
     * Collects all files under [projectDir] that match [effectiveExtensions], grouped by
     * extension key. Dockerfile-like filenames (e.g. `Dockerfile`, `Dockerfile.dev`) are
     * bucketed under `.dockerfile` when that extension is active.
     */
    fun collectFiles(
        projectDir: Path,
        effectiveExtensions: Set<String>,
        excludeGlobs: List<String>,
        plainTextMasks: List<String> = emptyList()
    ): Map<String, List<Path>> {
        val matchers = globMatchers(excludeGlobs)
        val plainTextMatchers = globMatchers(plainTextMasks)
        val matchDockerByName = ".dockerfile" in effectiveExtensions

        val result = mutableMapOf<String, MutableList<Path>>()

        Files.walk(projectDir).use { stream ->
            stream.forEach { path ->
                val relative = projectDir.relativize(path)

                val inExcludedDir = relative.any { part -> part.name in excludedDirNames }
                if (inExcludedDir) return@forEach

                val matchedGlob = matchesAny(matchers, relative)
                if (matchedGlob) return@forEach

                if (!path.isRegularFile()) return@forEach

                val ext = ".${path.extension}".lowercase()
                val key = when {
                    ext in effectiveExtensions -> ext

                    matchDockerByName && isDockerfileName(path) -> ".dockerfile"

                    matchesAny(plainTextMatchers, relative) && isWithinPlainTextSize(path) ->
                        PLAIN_TEXT

                    else -> null
                }
                if (key == null) return@forEach
                result.getOrPut(key) { mutableListOf() }.add(path)
            }
        }

        return result
    }

    /**
     * Returns true for files that the DockerParser accepts by name convention:
     * filenames starting with `dockerfile` or `containerfile` (case-insensitive),
     * e.g. `Dockerfile`, `Dockerfile.dev`, `Containerfile`.
     */
    private fun isDockerfileName(path: Path): Boolean {
        val name = path.name.lowercase()
        return name.startsWith("dockerfile") || name.startsWith("containerfile")
    }

    private fun globMatchers(globs: List<String>): List<PathMatcher> = globs.flatMap { glob ->
        buildList {
            add(FileSystems.getDefault().getPathMatcher("glob:$glob"))
            if (glob.startsWith("**/")) {
                add(FileSystems.getDefault().getPathMatcher("glob:${glob.removePrefix("**/")}"))
            }
        }
    }

    private fun matchesAny(matchers: List<PathMatcher>, relative: Path): Boolean =
        matchers.any { it.matches(relative) }

    private fun isWithinPlainTextSize(path: Path): Boolean = try {
        Files.size(path) <= PLAIN_TEXT_SIZE_THRESHOLD_MB * BYTES_PER_MB
    } catch (_: Exception) {
        false
    }
}
