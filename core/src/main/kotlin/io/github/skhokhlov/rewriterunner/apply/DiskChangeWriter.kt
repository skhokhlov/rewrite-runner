package io.github.skhokhlov.rewriterunner.apply

import io.github.skhokhlov.rewriterunner.RunnerLogger
import java.nio.file.Files
import java.nio.file.Path
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
                val target = projectDir.resolve(after.sourcePath)
                target.parent?.let { Files.createDirectories(it) }
                target.writeText(after.printAll(), Charsets.UTF_8)
                logger.info("      Wrote ${after.sourcePath}")
            }
        }
    }
}
