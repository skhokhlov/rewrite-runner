package io.github.skhokhlov.rewriterunner

import java.nio.file.Path
import org.openrewrite.Result

/**
 * The result of a single [OpenRewriteRunner] invocation.
 *
 * @property results The raw OpenRewrite [Result] list produced by the recipe run.
 *   Each entry holds the before/after [org.openrewrite.SourceFile] pair and a precomputed
 *   unified diff. An empty list means the recipe made no changes.
 * @property changedFiles Paths of files that were written to disk during this run.
 *   Empty when [OpenRewriteRunner.Builder.dryRun] is `true` or when [results] is empty.
 * @property projectDir The project directory that was analysed, for reference.
 */
data class RunResult(
    val results: List<Result>,
    val changedFiles: List<Path>,
    val projectDir: Path
) {
    /** `true` when the recipe produced at least one change, regardless of whether
     *  changes were written to disk. */
    val hasChanges: Boolean get() = results.isNotEmpty()

    /** Number of source files changed by the recipe. */
    val changeCount: Int get() = results.size
}
