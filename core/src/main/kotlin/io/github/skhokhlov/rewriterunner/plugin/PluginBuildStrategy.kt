package io.github.skhokhlov.rewriterunner.plugin

import io.github.skhokhlov.rewriterunner.config.RepositoryConfig
import java.nio.file.Path

internal interface PluginBuildStrategy {
    fun run(
        projectDir: Path,
        activeRecipe: String,
        recipeArtifacts: List<String>,
        rewriteConfig: Path?,
        rewriteConfigContent: String?,
        dryRun: Boolean,
        includeMavenCentral: Boolean,
        repositories: List<RepositoryConfig>
    ): PluginRunResult
}
