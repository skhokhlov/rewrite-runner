package io.github.skhokhlov.rewriterunner.plugin

import io.github.skhokhlov.rewriterunner.RunnerLogger
import io.github.skhokhlov.rewriterunner.config.RepositoryConfig
import io.github.skhokhlov.rewriterunner.lst.utils.resolveMavenCommand
import io.github.skhokhlov.rewriterunner.lst.utils.runProcess
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.exists

/**
 * Maven-side [PluginBuildStrategy] implementation.
 *
 * Pins the plugin's patch output to a private temp directory via
 * `-Drewrite.reportOutputDirectory=<dir>`, sidestepping per-version default-path drift
 * (older docs say `target/site/rewrite/`, current plugin defaults to `target/rewrite/`).
 * Also pins `-Drewrite.runPerSubmodule=false` so user pom configuration cannot redirect
 * the plugin into per-submodule mode that would race/overwrite the shared output file.
 *
 * Forwards `excludePaths` to the upstream `rewrite-maven-plugin` via the
 * `-Drewrite.exclusions=<csv>` system property, joined into a comma-separated list of globs.
 */
internal open class MavenPluginStrategy(
    private val logger: RunnerLogger,
    private val timeout: Duration,
    private val rewritePluginVersion: String
) : PluginBuildStrategy {
    override fun run(
        projectDir: Path,
        activeRecipe: String,
        recipeArtifacts: List<String>,
        rewriteConfig: Path?,
        rewriteConfigContent: String?,
        dryRun: Boolean,
        includeMavenCentral: Boolean,
        artifactRepositories: List<RepositoryConfig>,
        excludePaths: List<String>
    ): PluginRunResult {
        val effectiveRewriteConfig =
            createRewriteConfigFile(rewriteConfigContent)
                ?: rewriteConfig
        val reportDir = createPrivateTempDirectory("rewrite-runner-report-")
        return try {
            DirectPluginExecutor(projectDir, dryRun, ::execute).run(
                DirectPluginInvocation(
                    dryRunCommand = buildCommand(
                        projectDir = projectDir,
                        goal = "dryRun",
                        activeRecipe = activeRecipe,
                        recipeArtifacts = recipeArtifacts,
                        rewriteConfig = effectiveRewriteConfig,
                        reportOutputDirectory = reportDir,
                        excludePaths = excludePaths
                    ),
                    applyCommand = buildCommand(
                        projectDir = projectDir,
                        goal = "run",
                        activeRecipe = activeRecipe,
                        recipeArtifacts = recipeArtifacts,
                        rewriteConfig = effectiveRewriteConfig,
                        reportOutputDirectory = reportDir,
                        excludePaths = excludePaths
                    ),
                    patchFiles = { findPatchFiles(projectDir, reportDir) },
                    dryRunFailureMessage = { pluginFailureMessage("Maven rewrite:dryRun", it) },
                    applyFailureMessage = { pluginFailureMessage("Maven rewrite:run", it) }
                )
            )
        } finally {
            try {
                deleteRecursively(reportDir)
            } catch (e: Exception) {
                logger.warn("Maven plugin: failed to clean up report dir $reportDir: ${e.message}")
            }
            if (rewriteConfigContent != null && effectiveRewriteConfig != null) {
                Files.deleteIfExists(effectiveRewriteConfig)
            }
        }
    }

    fun buildCommand(
        projectDir: Path,
        goal: String,
        activeRecipe: String,
        recipeArtifacts: List<String>,
        rewriteConfig: Path?,
        reportOutputDirectory: Path,
        excludePaths: List<String> = emptyList()
    ): List<String> = buildList {
        add(resolveMavenCommand(projectDir))
        add("-U")
        add("--no-transfer-progress")
        add("--batch-mode")
        add(
            "org.openrewrite.maven:rewrite-maven-plugin:" +
                "$rewritePluginVersion:$goal"
        )
        add("-Drewrite.activeRecipes=$activeRecipe")
        // The rewrite-maven-plugin's documented user property for reportOutputDirectory is
        // unprefixed (see https://openrewrite.github.io/rewrite-maven-plugin/dryRun-mojo.html);
        // -Drewrite.reportOutputDirectory is silently ignored. runPerSubmodule, by contrast, is
        // exposed as `rewrite.runPerSubmodule`.
        add("-DreportOutputDirectory=${reportOutputDirectory.toAbsolutePath()}")
        add("-Drewrite.runPerSubmodule=false")
        if (recipeArtifacts.isNotEmpty()) {
            add("-Drewrite.recipeArtifactCoordinates=${recipeArtifacts.joinToString(",")}")
        }
        if (excludePaths.isNotEmpty()) {
            add("-Drewrite.exclusions=${excludePaths.joinToString(",")}")
        }
        rewriteConfig?.let {
            add("-Drewrite.configLocation=${it.toAbsolutePath()}")
        }
    }

    open fun execute(projectDir: Path, command: List<String>): Int? = runProcess(
        workDir = projectDir,
        command = command,
        timeout = timeout,
        timeoutName = "pluginTimeout",
        logger = logger
    )

    private fun findPatchFiles(projectDir: Path, reportDir: Path): List<DirectPluginPatchFile> {
        val patch = reportDir.resolve("rewrite.patch")
        if (!patch.exists()) return emptyList()
        return listOf(DirectPluginPatchFile(file = patch, baseDir = projectDir))
    }
}
