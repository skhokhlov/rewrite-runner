package io.github.skhokhlov.rewriterunner.plugin

import io.github.skhokhlov.rewriterunner.config.RepositoryConfig
import java.nio.file.Path

internal interface PluginBuildStrategy {
    /**
     * Invoke the project's native OpenRewrite plugin (Maven or Gradle) with the given recipe
     * configuration.
     *
     * @param projectDir Directory the build command runs in. For a root-level invocation this is
     *   the repository root; for an orphan (root-less monorepo) build unit it is the unit's
     *   subdirectory.
     * @param rootDir Repository root used to rebase diff paths and to resolve a fallback build
     *   wrapper when [projectDir] (a subdir unit) carries none. Defaults to [projectDir] so
     *   root-level callers are unaffected.
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
        rootDir: Path = projectDir,
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
