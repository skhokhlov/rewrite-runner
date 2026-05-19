package io.github.skhokhlov.rewriterunner.plugin

import io.github.skhokhlov.rewriterunner.NoOpRunnerLogger
import io.github.skhokhlov.rewriterunner.RunnerLogger
import io.github.skhokhlov.rewriterunner.config.RepositoryConfig
import io.github.skhokhlov.rewriterunner.config.ToolConfigDefaults
import io.github.skhokhlov.rewriterunner.lst.utils.hasBuildGradle
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.exists

internal class PluginRecipeRunner(
    private val logger: RunnerLogger = NoOpRunnerLogger,
    timeout: Duration = ToolConfigDefaults.PLUGIN_RUN_TIMEOUT,
    rewriteGradlePluginVersion: String = ToolConfigDefaults.REWRITE_GRADLE_PLUGIN_VERSION,
    rewriteMavenPluginVersion: String = ToolConfigDefaults.REWRITE_MAVEN_PLUGIN_VERSION,
    private val gradleStrategy: PluginBuildStrategy =
        GradlePluginStrategy(logger, timeout, rewriteGradlePluginVersion),
    private val mavenStrategy: PluginBuildStrategy =
        MavenPluginStrategy(logger, timeout, rewriteMavenPluginVersion)
) {
    /**
     * Tries Gradle before Maven when both root build files are present.
     *
     * @param excludePaths Glob patterns of files to exclude from parsing. Passed verbatim to
     *   whichever strategy ([gradleStrategy] / [mavenStrategy]) is dispatched; each strategy
     *   renders them in its own native plugin format.
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
        excludePaths: List<String> = emptyList()
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
                        artifactRepositories = artifactRepositories,
                        excludePaths = excludePaths
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
                        artifactRepositories = artifactRepositories,
                        excludePaths = excludePaths
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
}
