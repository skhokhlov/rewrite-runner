package io.github.skhokhlov.rewriterunner.plugin

import java.nio.file.Path
import java.time.Duration

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
