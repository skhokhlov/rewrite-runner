package io.github.skhokhlov.rewriterunner.plugin

import io.github.skhokhlov.rewriterunner.ExecutionTimeouts
import io.github.skhokhlov.rewriterunner.NoOpRunnerLogger
import io.github.skhokhlov.rewriterunner.RunnerLogger
import io.github.skhokhlov.rewriterunner.config.RepositoryConfig
import io.github.skhokhlov.rewriterunner.config.ToolConfig
import io.github.skhokhlov.rewriterunner.lst.utils.hasBuildGradle
import java.nio.file.Path
import kotlin.io.path.exists

internal class PluginRecipeRunner(
    private val logger: RunnerLogger = NoOpRunnerLogger,
    timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
    rewriteGradlePluginVersion: String = ToolConfig.REWRITE_GRADLE_PLUGIN_VERSION,
    rewriteMavenPluginVersion: String = ToolConfig.REWRITE_MAVEN_PLUGIN_VERSION,
    private val gradleStrategy: PluginBuildStrategy =
        GradlePluginStrategy(logger, timeoutSeconds, rewriteGradlePluginVersion),
    private val mavenStrategy: PluginBuildStrategy =
        MavenPluginStrategy(logger, timeoutSeconds, rewriteMavenPluginVersion)
) {
    /**
     * Tries Gradle before Maven when both root build files are present.
     */
    fun run(
        projectDir: Path,
        activeRecipe: String,
        recipeArtifacts: List<String>,
        rewriteConfig: Path?,
        rewriteConfigContent: String?,
        dryRun: Boolean,
        includeMavenCentral: Boolean,
        repositories: List<RepositoryConfig>
    ): PluginRunResult {
        val hasGradle = hasBuildGradle(projectDir)
        val hasPom = projectDir.resolve("pom.xml").exists()

        if (!hasGradle && !hasPom) {
            return PluginRunResult.Skipped("no Gradle or Maven build tool found")
        }

        val failures = mutableListOf<PluginRunResult.Failed>()

        if (hasGradle) {
            logger.info("      Trying Gradle OpenRewrite plugin")
            when (
                val result =
                    gradleStrategy.run(
                        projectDir = projectDir,
                        activeRecipe = activeRecipe,
                        recipeArtifacts = recipeArtifacts,
                        rewriteConfig = rewriteConfig,
                        rewriteConfigContent = rewriteConfigContent,
                        dryRun = dryRun,
                        includeMavenCentral = includeMavenCentral,
                        repositories = repositories
                    )
            ) {
                is PluginRunResult.Failed -> failures.add(result)
                else -> return result
            }
        }

        if (hasPom) {
            logger.info("      Trying Maven OpenRewrite plugin")
            when (
                val result =
                    mavenStrategy.run(
                        projectDir = projectDir,
                        activeRecipe = activeRecipe,
                        recipeArtifacts = recipeArtifacts,
                        rewriteConfig = rewriteConfig,
                        rewriteConfigContent = rewriteConfigContent,
                        dryRun = dryRun,
                        includeMavenCentral = includeMavenCentral,
                        repositories = repositories
                    )
            ) {
                is PluginRunResult.Failed -> failures.add(result)
                else -> return result
            }
        }

        return failures
            .takeIf { it.isNotEmpty() }
            ?.let { PluginRunResult.Failed(it.joinToString(" ; ") { failure -> failure.reason }) }
            ?: PluginRunResult.Skipped("no supported build tool found")
    }

    companion object {
        const val DEFAULT_TIMEOUT_SECONDS = ExecutionTimeouts.DEFAULT_PLUGIN_TIMEOUT_SECONDS
    }
}
