package io.github.skhokhlov.rewriterunner.plugin

import io.github.skhokhlov.rewriterunner.NoOpRunnerLogger
import io.github.skhokhlov.rewriterunner.RunnerLogger
import io.github.skhokhlov.rewriterunner.config.RepositoryConfig
import io.github.skhokhlov.rewriterunner.config.ToolConfigDefaults
import io.github.skhokhlov.rewriterunner.lst.utils.discoverBuildUnitResult
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
     * Runs the official OpenRewrite plugin for [projectDir].
     *
     * When [projectDir] itself has a build descriptor, the plugin runs there exactly as before
     * (Gradle before Maven when both are present). When the root has **no** descriptor, this
     * discovers orphan build units in subdirectories (root-less monorepo) and runs the plugin in
     * each, rebasing every diff to [projectDir]. The aggregate is:
     *
     *  - [PluginRunResult.Success] / [PluginRunResult.NoChanges] when every discovered unit was
     *    covered without failure,
     *  - [PluginRunResult.Partial] when some units produced diffs but others failed or discovery
     *    was truncated — the caller keeps the diffs and falls back to the LST pipeline for the
     *    remainder,
     *  - [PluginRunResult.Failed] / [PluginRunResult.Skipped] when nothing usable was produced.
     *
     * @param excludePaths Glob patterns of files to exclude from parsing. Passed verbatim to
     *   whichever strategy ([gradleStrategy] / [mavenStrategy]) is dispatched; each strategy
     *   renders them in its own native plugin format.
     * @param plainTextMasks Glob patterns of otherwise-unhandled files to parse as plain text.
     *   Passed verbatim to the selected strategy so Stage 0 and the LST fallback see the same
     *   resolved list.
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
    ): PluginRunResult {
        val runUnit: (Path) -> PluginRunResult = { unitDir ->
            runForDir(
                unitDir = unitDir,
                rootDir = projectDir,
                activeRecipe = activeRecipe,
                recipeArtifacts = recipeArtifacts,
                rewriteConfig = rewriteConfig,
                rewriteConfigContent = rewriteConfigContent,
                dryRun = dryRun,
                includeMavenCentral = includeMavenCentral,
                artifactRepositories = artifactRepositories,
                excludePaths = excludePaths,
                plainTextMasks = plainTextMasks
            )
        }

        if (hasBuildGradle(projectDir) || projectDir.resolve("pom.xml").exists()) {
            return runUnit(projectDir)
        }

        // Root-less monorepo: discover orphan build units in subdirectories.
        val discovery = discoverBuildUnitResult(projectDir, logger = logger)
        val unitDirs = discovery.units.map { it.dir }.distinct()
        if (unitDirs.isEmpty()) {
            return PluginRunResult.Skipped("no Gradle or Maven build tool found")
        }

        logger.info(
            "      Root has no build file; running plugin in ${unitDirs.size} orphan unit(s)"
        )
        val perUnit = unitDirs.map { it to runUnit(it) }
        return aggregate(perUnit, truncated = discovery.truncated)
    }

    /** Runs Gradle-then-Maven for a single directory, rebasing diffs to [rootDir]. */
    private fun runForDir(
        unitDir: Path,
        rootDir: Path,
        activeRecipe: String,
        recipeArtifacts: List<String>,
        rewriteConfig: Path?,
        rewriteConfigContent: String?,
        dryRun: Boolean,
        includeMavenCentral: Boolean,
        artifactRepositories: List<RepositoryConfig>,
        excludePaths: List<String>,
        plainTextMasks: List<String>
    ): PluginRunResult {
        val hasGradle = hasBuildGradle(unitDir)
        val hasPom = unitDir.resolve("pom.xml").exists()

        if (!hasGradle && !hasPom) {
            return PluginRunResult.Skipped("no Gradle or Maven build tool found")
        }

        val failures = mutableListOf<PluginRunResult.Failed>()

        if (hasGradle) {
            logger.info("      Trying Gradle OpenRewrite plugin")
            when (
                val result =
                    gradleStrategy.run(
                        projectDir = unitDir,
                        rootDir = rootDir,
                        activeRecipe = activeRecipe,
                        recipeArtifacts = recipeArtifacts,
                        rewriteConfig = rewriteConfig,
                        rewriteConfigContent = rewriteConfigContent,
                        dryRun = dryRun,
                        includeMavenCentral = includeMavenCentral,
                        artifactRepositories = artifactRepositories,
                        excludePaths = excludePaths,
                        plainTextMasks = plainTextMasks
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
                        projectDir = unitDir,
                        rootDir = rootDir,
                        activeRecipe = activeRecipe,
                        recipeArtifacts = recipeArtifacts,
                        rewriteConfig = rewriteConfig,
                        rewriteConfigContent = rewriteConfigContent,
                        dryRun = dryRun,
                        includeMavenCentral = includeMavenCentral,
                        artifactRepositories = artifactRepositories,
                        excludePaths = excludePaths,
                        plainTextMasks = plainTextMasks
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

    /** Folds per-unit results into one [PluginRunResult] for the root-less monorepo case. */
    private fun aggregate(
        perUnit: List<Pair<Path, PluginRunResult>>,
        truncated: Boolean
    ): PluginRunResult {
        val diffs = linkedMapOf<Path, String>()
        val changedFiles = mutableListOf<Path>()
        var estimatedTimeSaved: Duration? = null
        val failures = mutableListOf<String>()

        perUnit.forEach { (dir, result) ->
            when (result) {
                is PluginRunResult.Success -> {
                    diffs.putAll(result.diffs)
                    changedFiles += result.changedFiles
                    result.estimatedTimeSaved?.let { saved ->
                        estimatedTimeSaved = (estimatedTimeSaved ?: Duration.ZERO).plus(saved)
                    }
                }

                PluginRunResult.NoChanges -> Unit

                is PluginRunResult.Failed -> failures += "$dir: ${result.reason}"

                is PluginRunResult.Skipped -> failures += "$dir: ${result.reason}"

                is PluginRunResult.Partial ->
                    failures +=
                        "$dir: ${result.failures.joinToString("; ")}"
            }
        }

        if (truncated) {
            failures += "build-unit discovery was truncated; some subprojects were not run"
        }

        val fullyCovered = failures.isEmpty()
        return when {
            diffs.isNotEmpty() && fullyCovered ->
                PluginRunResult.Success(changedFiles, diffs, estimatedTimeSaved)

            diffs.isEmpty() && fullyCovered -> PluginRunResult.NoChanges

            diffs.isNotEmpty() ->
                PluginRunResult.Partial(changedFiles, diffs, estimatedTimeSaved, failures)

            failures.isNotEmpty() -> PluginRunResult.Failed(failures.joinToString(" ; "))

            else -> PluginRunResult.Skipped("no supported build tool found")
        }
    }
}
