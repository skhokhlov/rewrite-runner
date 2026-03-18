package io.github.skhokhlov.rewriterunner.lst.utils

import io.github.skhokhlov.rewriterunner.config.ParseConfig
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
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
        excludeGlobs: List<String>
    ): Map<String, List<Path>> {
        val matchers = excludeGlobs.map {
            FileSystems.getDefault().getPathMatcher("glob:$it")
        }
        val matchDockerByName = ".dockerfile" in effectiveExtensions

        val result = mutableMapOf<String, MutableList<Path>>()

        Files.walk(projectDir).use { stream ->
            stream.filter { path ->
                val relative = projectDir.relativize(path)

                val inExcludedDir = relative.any { part -> part.name in excludedDirNames }
                if (inExcludedDir) return@filter false

                val matchedGlob = matchers.any { it.matches(relative) }
                if (matchedGlob) return@filter false

                if (!path.isRegularFile()) return@filter false

                val ext = ".${path.extension}".lowercase()
                ext in effectiveExtensions ||
                    (matchDockerByName && isDockerfileName(path))
            }.forEach { path ->
                val ext = ".${path.extension}".lowercase()
                val key = if (ext in effectiveExtensions) ext else ".dockerfile"
                result.getOrPut(key) { mutableListOf() }.add(path)
            }
        }

        return result
    }

    /**
     * Resolves the effective set of extensions to parse, applying CLI-flag overrides on
     * top of [parseConfig] and normalising each entry to a dot-prefixed lowercase string.
     * CLI flags take precedence over config file settings.
     */
    fun resolveExtensions(
        parseConfig: ParseConfig,
        includeExtensionsCli: List<String>,
        excludeExtensionsCli: List<String>
    ): Set<String> {
        val include = (
            includeExtensionsCli.takeIf { it.isNotEmpty() }
                ?: parseConfig.includeExtensions
            )
            .map { it.lowercase().let { e -> if (e.startsWith(".")) e else ".$e" } }

        val exclude = (
            excludeExtensionsCli.takeIf { it.isNotEmpty() }
                ?: parseConfig.excludeExtensions
            )
            .map { it.lowercase().let { e -> if (e.startsWith(".")) e else ".$e" } }
            .toSet()

        val base = include.takeIf { it.isNotEmpty() }?.toSet() ?: defaultExtensions
        return base - exclude
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
}
