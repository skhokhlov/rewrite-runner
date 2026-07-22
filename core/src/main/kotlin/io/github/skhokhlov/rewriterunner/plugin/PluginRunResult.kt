package io.github.skhokhlov.rewriterunner.plugin

import io.github.skhokhlov.rewriterunner.ExecutorOutcome
import io.github.skhokhlov.rewriterunner.ExecutorPhase
import io.github.skhokhlov.rewriterunner.LogicalExecutor
import java.nio.file.Path
import java.time.Duration

/**
 * One actual build-tool process launched by Stage 0. Kept separate from [PluginRunResult] so a
 * Gradle-to-Maven fallback and an apply run retain every process rather than being collapsed into
 * one inferred diagnostic record.
 */
internal data class PluginProcessAttempt(
    val executor: LogicalExecutor,
    val phase: ExecutorPhase,
    val workingDirectory: String,
    val durationMillis: Long,
    val outcome: ExecutorOutcome,
    val exitCode: Int?,
    val message: String? = null
)

/** Result of attempting recipe execution through an official OpenRewrite build plugin. */
sealed class PluginRunResult {
    data class Success(
        val changedFiles: List<Path>,
        val diffs: Map<Path, String>,
        val estimatedTimeSaved: Duration?
    ) : PluginRunResult()

    data object NoChanges : PluginRunResult()

    data class Failed(val reason: String) : PluginRunResult()

    data class Skipped(val reason: String) : PluginRunResult()

    /**
     * Some discovered build units produced usable diffs while others failed, were skipped, or
     * could not be fully covered (e.g. truncated discovery). Only arises from the multi-unit
     * orphan (root-less monorepo) branch in [PluginRecipeRunner].
     *
     * The caller keeps [diffs] (already rebased to the repository root) and falls back to the
     * in-process LST pipeline for the remainder of the project, merging the two with Stage 0
     * winning on any path collision.
     *
     * @property diffs Successful units' diffs, keyed by repository-root-relative path.
     * @property estimatedTimeSaved Sum of the successful units' estimates, or `null` when none
     *   reported a positive value.
     * @property failures Human-readable reasons for the units that did not succeed.
     */
    data class Partial(
        val changedFiles: List<Path>,
        val diffs: Map<Path, String>,
        val estimatedTimeSaved: Duration?,
        val failures: List<String>
    ) : PluginRunResult()
}
