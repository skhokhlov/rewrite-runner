package io.github.skhokhlov.rewriterunner.plugin

import io.github.skhokhlov.rewriterunner.config.RepositoryConfig
import java.nio.file.Path

internal interface PluginBuildStrategy {
    /**
     * Invoke the project's native OpenRewrite plugin (Maven or Gradle) with the given recipe
     * configuration.
     *
     * @param excludePaths Glob patterns of files to exclude from parsing. Forwarded to the
     *   upstream plugin in its native format (Maven: `-Drewrite.exclusions=…`; Gradle:
     *   `exclusion(...)` DSL calls in the init script). Empty list means no exclusion.
     * @param plainTextMasks Glob patterns of otherwise-unhandled files to parse as plain text.
     *   Forwarded to the upstream plugin in its native format. Empty list means no explicit
     *   override at the strategy layer; [io.github.skhokhlov.rewriterunner.RewriteRunner]
     *   resolves this to the upstream defaults before Stage 0.
     */
    fun run(
        projectDir: Path,
        activeRecipe: String,
        recipeArtifacts: List<String>,
        rewriteConfig: Path?,
        rewriteConfigContent: String?,
        dryRun: Boolean,
        includeMavenCentral: Boolean,
        artifactRepositories: List<RepositoryConfig>,
        excludePaths: List<String> = emptyList(),
        plainTextMasks: List<String> = emptyList()
    ): PluginRunResult
}
