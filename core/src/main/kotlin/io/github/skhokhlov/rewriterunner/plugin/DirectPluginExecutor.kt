package io.github.skhokhlov.rewriterunner.plugin

import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

internal data class DirectPluginPatchFile(val file: Path, val baseDir: Path)

internal data class DirectPluginInvocation(
    val dryRunCommand: List<String>,
    val applyCommand: List<String>,
    val patchFiles: () -> List<DirectPluginPatchFile>,
    val dryRunFailureMessage: (Int?) -> String,
    val applyFailureMessage: (Int?) -> String
)

/**
 * Runs an official OpenRewrite build plugin through the target project's build tool.
 *
 * The build-tool-specific strategies only construct commands and patch locations; this
 * class owns the shared execution contract:
 * 1. run dry-run to generate a patch,
 * 2. parse generated patch files for formatted output,
 * 3. run apply when changes exist and the caller is not in dry-run mode.
 */
internal class DirectPluginExecutor(
    private val projectDir: Path,
    private val dryRun: Boolean,
    private val execute: (Path, List<String>) -> Int?
) {
    fun run(invocation: DirectPluginInvocation): PluginRunResult {
        val dryRunExit = execute(projectDir, invocation.dryRunCommand)
        if (dryRunExit != 0) {
            return PluginRunResult.Failed(invocation.dryRunFailureMessage(dryRunExit))
        }

        val diffs = parseDiffs(invocation.patchFiles())
        if (diffs.isEmpty()) return PluginRunResult.NoChanges

        if (!dryRun) {
            val applyExit = execute(projectDir, invocation.applyCommand)
            if (applyExit != 0) {
                return PluginRunResult.Failed(invocation.applyFailureMessage(applyExit))
            }
        }

        return PluginRunResult.Success(
            changedFiles = if (dryRun) emptyList() else diffs.keys.map(projectDir::resolve),
            diffs = diffs
        )
    }

    private fun parseDiffs(patchFiles: List<DirectPluginPatchFile>): Map<Path, String> = patchFiles
        .filter { it.file.exists() }
        .flatMap { patchFile ->
            val baseRelative = relativeToProject(patchFile.baseDir)
            PatchParser.parse(patchFile.file.readText()).map { (path, diff) ->
                val rebasedPath = relativeToProject(patchFile.baseDir.resolve(path))
                rebasedPath to rebaseDiff(diff, baseRelative)
            }
        }.toMap()

    private fun relativeToProject(path: Path): Path {
        val normalizedProject = projectDir.toAbsolutePath().normalize()
        val normalizedPath = path.toAbsolutePath().normalize()
        return normalizedProject.relativize(normalizedPath)
    }

    private fun rebaseDiff(diff: String, baseRelative: Path): String {
        if (baseRelative.toString().isEmpty()) return diff

        val prefix = baseRelative.toString().replace('\\', '/')
        return diff.lineSequence()
            .joinToString("\n") { line ->
                when {
                    line.startsWith("diff --git ") -> rebaseGitHeader(line, prefix)
                    line.startsWith("--- ") -> rebasePatchHeader(line, prefix)
                    line.startsWith("+++ ") -> rebasePatchHeader(line, prefix)
                    else -> line
                }
            } + if (diff.endsWith("\n")) "\n" else ""
    }

    private fun rebaseGitHeader(line: String, prefix: String): String {
        val parts = line.split(" ")
        if (parts.size != 4) return line
        return listOf(
            parts[0],
            parts[1],
            parts[2].prefixDiffPath(prefix),
            parts[3].prefixDiffPath(prefix)
        )
            .joinToString(" ")
    }

    private fun rebasePatchHeader(line: String, prefix: String): String {
        val marker = line.take(4)
        val rest = line.drop(4)
        val path = rest.substringBefore('\t')
        val suffix = rest.removePrefix(path)
        return marker + path.prefixDiffPath(prefix) + suffix
    }

    private fun String.prefixDiffPath(prefix: String): String = when {
        this == "/dev/null" -> this
        startsWith("a/") -> "a/$prefix/${removePrefix("a/")}"
        startsWith("b/") -> "b/$prefix/${removePrefix("b/")}"
        else -> "$prefix/$this"
    }
}
