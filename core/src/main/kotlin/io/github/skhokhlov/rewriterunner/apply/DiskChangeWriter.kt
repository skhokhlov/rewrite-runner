package io.github.skhokhlov.rewriterunner.apply

import io.github.skhokhlov.rewriterunner.RunnerLogger
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.COPY_ATTRIBUTES
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlin.io.path.writeText
import org.openrewrite.Result

internal class DiskChangeWriter(private val projectDir: Path, private val logger: RunnerLogger) :
    ChangeWriter {
    override fun apply(results: List<Result>): WriteOutcome {
        logger.lifecycle("[6/7] Writing changes to disk")
        val successes = mutableListOf<AppliedChange>()
        val failures = mutableListOf<ApplyFailure>()

        for (result in results) {
            val kind = ChangeKind.from(result)
            val path = result.changePath()
            try {
                applyOne(result, kind, path)
                successes += AppliedChange(kind, path)
            } catch (e: Exception) {
                val cause = e.message ?: e::class.simpleName ?: "unknown error"
                failures += ApplyFailure(kind, path, cause)
                logger.warn("Failed to apply change to $path: $cause")
            }
        }

        when {
            results.isEmpty() -> logger.lifecycle("      No changes — nothing to write")

            failures.isEmpty() -> logger.lifecycle(
                "      Done: ${successes.size} change(s) applied"
            )

            else ->
                logger.lifecycle(
                    "      Done: ${successes.size} change(s) applied, " +
                        "${failures.size} failed"
                )
        }

        return WriteOutcome(successes = successes, failures = failures)
    }

    private fun applyOne(result: Result, kind: ChangeKind, path: String) {
        when (kind) {
            ChangeKind.DELETED -> {
                Files.deleteIfExists(projectDir.resolve(path))
                logger.info("      Deleted $path")
            }

            ChangeKind.CREATED,
            ChangeKind.MODIFIED -> {
                val after = checkNotNull(result.after) {
                    "Result after source is missing for $kind change"
                }
                val target = projectDir.resolve(after.sourcePath).toAbsolutePath().normalize()
                val original = result.before?.sourcePath
                    ?.let { projectDir.resolve(it).toAbsolutePath().normalize() }
                // Windows Path.equals ignores case; compare spelling to detect case-only moves.
                if (original != null && original.toString() != target.toString()) {
                    applyRename(original, target, after.printAll())
                } else {
                    target.parent?.let { Files.createDirectories(it) }
                    target.writeText(after.printAll(), Charsets.UTF_8)
                }
                logger.info("      Wrote ${after.sourcePath}")
            }
        }
    }

    private fun applyRename(original: Path, target: Path, content: String) {
        require(!original.toString().equals(target.toString(), ignoreCase = true)) {
            "Case-only renames are not supported: $original -> $target"
        }
        if (Files.exists(target, NOFOLLOW_LINKS)) {
            throw FileAlreadyExistsException(target.toString())
        }
        Files.createDirectories(target.parent)
        val staged = Files.createTempFile(target.parent, ".rewrite-runner-", ".tmp")
        try {
            if (Files.isRegularFile(original)) {
                Files.copy(original, staged, REPLACE_EXISTING, COPY_ATTRIBUTES)
            }
            staged.writeText(content, Charsets.UTF_8)
            // No REPLACE_EXISTING: a collision must preserve both files, including symlinks.
            Files.move(staged, target)
        } finally {
            Files.deleteIfExists(staged)
        }
        // A removal failure leaves the published destination in place and is an ApplyFailure.
        Files.delete(original)
    }
}
