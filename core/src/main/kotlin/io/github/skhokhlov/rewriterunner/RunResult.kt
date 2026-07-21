package io.github.skhokhlov.rewriterunner

import java.nio.file.Path
import org.openrewrite.Result

/**
 * The result of a single [RewriteRunner] invocation.
 *
 * @property results The raw OpenRewrite [Result] list produced by an explicit in-process LST run.
 *   Forked execution deliberately leaves this empty so LST graphs never return to the coordinator.
 * @property changedFiles Paths of files that were written to disk during this run.
 *   Empty when [RewriteRunner.Builder.dryRun] is `true` or when [results] is empty.
 * @property projectDir The project directory that was analysed, for reference.
 * @property rawDiffs Unified diffs keyed by relative file path. Populated by the official plugin
 *   and by the default forked LST worker.
 * @property executionDiagnostics Diagnostic info about which execution path produced
 *   the run. See [ExecutionDiagnostics] and [UsedExecutionStage] for details.
 */
data class RunResult(
    val results: List<Result>,
    val changedFiles: List<Path>,
    val projectDir: Path,
    val rawDiffs: Map<Path, String> = emptyMap(),
    val executionDiagnostics: ExecutionDiagnostics
) {
    /** `true` when the recipe produced at least one change, regardless of whether
     *  changes were written to disk. */
    val hasChanges: Boolean get() = results.isNotEmpty() || rawDiffs.isNotEmpty()

    /** Number of source files changed by the recipe. */
    val changeCount: Int get() = results.size + rawDiffs.size
}
