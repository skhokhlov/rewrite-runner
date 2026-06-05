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
}
