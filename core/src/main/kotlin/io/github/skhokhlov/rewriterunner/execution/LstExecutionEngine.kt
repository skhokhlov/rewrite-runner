package io.github.skhokhlov.rewriterunner.execution

import io.github.skhokhlov.rewriterunner.AetherContext
import io.github.skhokhlov.rewriterunner.ExecutionDiagnostics
import io.github.skhokhlov.rewriterunner.NoOpRunnerLogger
import io.github.skhokhlov.rewriterunner.RunnerLogger
import io.github.skhokhlov.rewriterunner.UsedExecutionStage
import io.github.skhokhlov.rewriterunner.apply.ChangeKind
import io.github.skhokhlov.rewriterunner.apply.ChangeWriter
import io.github.skhokhlov.rewriterunner.apply.DiskChangeWriter
import io.github.skhokhlov.rewriterunner.apply.WriteOutcome
import io.github.skhokhlov.rewriterunner.config.RepositoryConfig
import io.github.skhokhlov.rewriterunner.config.ToolConfig
import io.github.skhokhlov.rewriterunner.lst.LstBuilder
import io.github.skhokhlov.rewriterunner.lst.SpecializedOwnership
import io.github.skhokhlov.rewriterunner.recipe.RecipeArtifactResolver
import io.github.skhokhlov.rewriterunner.recipe.RecipeLoader
import io.github.skhokhlov.rewriterunner.recipe.RecipeRunner
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import kotlin.io.path.exists
import org.openrewrite.Result

/** The two domain scopes that can be delegated after the official plugin attempt. */
internal enum class WorkerScope { FULL_FALLBACK, SPECIALIZED_ONLY }

/** Fully resolved work handed to either the in-process engine or the forked worker. */
internal data class ResolvedExecutionRequest(
    val requestId: String,
    val scope: WorkerScope,
    val projectDir: Path,
    val activeRecipe: String,
    val recipeArtifacts: List<String>,
    val rewriteConfig: Path?,
    val rewriteConfigContent: String?,
    val dryRun: Boolean,
    val excludePaths: List<String>,
    val plainTextMasks: List<String>,
    val protectedPaths: Set<String>,
    val toolConfig: ToolConfig,
    val cacheDir: Path,
    val repositories: List<RepositoryConfig>,
    val includeMavenCentral: Boolean,
    val downloadThreads: Int,
    val resolverConnectTimeout: Duration,
    val resolverRequestTimeout: Duration,
    val changeWriter: ChangeWriter? = null
)

/** Internal outcome retains Results only while it is safe to do so in the current JVM. */
internal data class LstExecutionOutcome(
    val results: List<Result>,
    val rawDiffs: Map<Path, String>,
    val changedFiles: List<Path>,
    val diagnostics: ExecutionDiagnostics
)

/**
 * Shared LST implementation. The coordinator never invokes this in default mode; it is executed
 * either directly for explicit in-process compatibility or by WorkerMain in a separate JVM.
 */
internal class LstExecutionEngine(private val logger: RunnerLogger = NoOpRunnerLogger) {
    fun execute(request: ResolvedExecutionRequest): LstExecutionOutcome = when (request.scope) {
        WorkerScope.FULL_FALLBACK -> executeFullFallback(request)
        WorkerScope.SPECIALIZED_ONLY -> executeSpecializedOnly(request)
    }

    private fun executeFullFallback(request: ResolvedExecutionRequest): LstExecutionOutcome {
        logger.lifecycle("[1/7] Loading configuration")
        logger.info("      Cache dir: ${request.cacheDir}")
        val recipeAetherContext =
            AetherContext.build(
                localRepoDir = request.cacheDir.resolve("repository"),
                extraRepositories = request.repositories,
                connectTimeout = request.resolverConnectTimeout,
                requestTimeout = request.resolverRequestTimeout,
                downloadThreads = request.downloadThreads,
                excludeScopesFromGraph = listOf("test", "provided", "system"),
                includeMavenCentral = request.includeMavenCentral,
                logger = logger
            )
        val projectAetherContext = projectAetherContext(request)
        val recipeJars = resolveRecipeJars(request, recipeAetherContext)

        return RecipeLoader(logger).use { recipeLoader ->
            val recipe = loadRecipe(recipeLoader, request, recipeJars)
            logger.info("      Recipe ready: ${recipe.name}")
            logger.lifecycle("[4/7] Building LST for ${request.projectDir}")
            val lstBuildResult =
                LstBuilder(
                    logger = logger,
                    cacheDir = request.cacheDir,
                    toolConfig = request.toolConfig,
                    aetherContext = projectAetherContext
                ).build(
                    projectDir = request.projectDir,
                    excludePaths = request.excludePaths,
                    plainTextMasks = request.plainTextMasks
                )
            val sourceFiles = lstBuildResult.sourceFiles
            logger.lifecycle("      LST built: ${sourceFiles.size} file(s)")
            logger.lifecycle(
                "[5/7] Running recipe '${recipe.name}' against ${sourceFiles.size} file(s)"
            )
            val results = RecipeRunner(logger).run(recipe, sourceFiles)
            val effectiveResults =
                results.filterNot { result -> normalizedPath(result) in request.protectedPaths }
            val writeOutcome = applyResults(request, effectiveResults)
            val diagnostics =
                lstBuildResult.executionDiagnostics.copy(
                    estimatedTimeSaved = effectiveResults.fold(Duration.ZERO) { total, result ->
                        total.plus(result.timeSavings)
                    },
                    writeOutcome = writeOutcome
                )
            LstExecutionOutcome(
                results = effectiveResults,
                rawDiffs = rawDiffs(effectiveResults),
                changedFiles = changedFiles(request.projectDir, writeOutcome),
                diagnostics = diagnostics
            )
        }
    }

    private fun executeSpecializedOnly(request: ResolvedExecutionRequest): LstExecutionOutcome {
        logger.lifecycle("[0/7] Running specialized non-JVM parser pass (Docker/HCL/Proto)")
        val lstBuildResult =
            LstBuilder(
                logger = logger,
                cacheDir = request.cacheDir,
                toolConfig = request.toolConfig,
                aetherContext = projectAetherContext(request)
            ).build(
                projectDir = request.projectDir,
                excludePaths = request.excludePaths,
                plainTextMasks = emptyList(),
                restrictToExtensions = SpecializedOwnership.extensions
            )
        if (lstBuildResult.sourceFiles.isEmpty()) {
            return LstExecutionOutcome(
                results = emptyList(),
                rawDiffs = emptyMap(),
                changedFiles = emptyList(),
                diagnostics = lstBuildResult.executionDiagnostics
            )
        }

        val recipeAetherContext =
            AetherContext.build(
                localRepoDir = request.cacheDir.resolve("repository"),
                extraRepositories = request.repositories,
                connectTimeout = request.resolverConnectTimeout,
                requestTimeout = request.resolverRequestTimeout,
                downloadThreads = request.downloadThreads,
                includeMavenCentral = request.includeMavenCentral,
                logger = logger
            )
        val recipeJars = resolveRecipeJars(request, recipeAetherContext)
        return RecipeLoader(logger).use { recipeLoader ->
            val recipe = loadRecipe(recipeLoader, request, recipeJars)
            val results = RecipeRunner(logger).run(recipe, lstBuildResult.sourceFiles)
            val writeOutcome = applyResults(request, results)
            LstExecutionOutcome(
                results = results,
                rawDiffs = rawDiffs(results),
                changedFiles = changedFiles(request.projectDir, writeOutcome),
                diagnostics =
                    lstBuildResult.executionDiagnostics.copy(
                        stageUsed = UsedExecutionStage.PLUGIN,
                        estimatedTimeSaved = results.fold(Duration.ZERO) { total, result ->
                            total.plus(result.timeSavings)
                        },
                        writeOutcome = writeOutcome
                    )
            )
        }
    }

    private fun projectAetherContext(request: ResolvedExecutionRequest): AetherContext =
        AetherContext.build(
            localRepoDir = Paths.get(System.getProperty("user.home"), ".m2", "repository"),
            extraRepositories = request.repositories,
            connectTimeout = request.resolverConnectTimeout,
            requestTimeout = request.resolverRequestTimeout,
            downloadThreads = request.downloadThreads,
            includeMavenCentral = request.includeMavenCentral,
            logger = logger
        )

    private fun resolveRecipeJars(
        request: ResolvedExecutionRequest,
        recipeAetherContext: AetherContext
    ): List<Path> = if (request.recipeArtifacts.isEmpty()) {
        logger.lifecycle("[2/7] No recipe artifacts specified — using classpath recipes only")
        emptyList()
    } else {
        logger.lifecycle("[2/7] Resolving ${request.recipeArtifacts.size} recipe artifact(s)")
        RecipeArtifactResolver(recipeAetherContext, logger).resolveAll(request.recipeArtifacts)
    }

    private fun loadRecipe(
        recipeLoader: RecipeLoader,
        request: ResolvedExecutionRequest,
        recipeJars: List<Path>
    ) = if (request.rewriteConfigContent != null) {
        recipeLoader.load(
            recipeJars = recipeJars,
            activeRecipeName = request.activeRecipe,
            rewriteYamlContent = request.rewriteConfigContent
        )
    } else {
        val rewriteConfig =
            request.rewriteConfig
                ?: request.projectDir.resolve("rewrite.yaml").takeIf { it.exists() }
                ?: request.projectDir.resolve("rewrite.yml")
        recipeLoader.load(
            recipeJars = recipeJars,
            activeRecipeName = request.activeRecipe,
            rewriteYaml = rewriteConfig
        )
    }

    private fun applyResults(
        request: ResolvedExecutionRequest,
        results: List<Result>
    ): WriteOutcome {
        if (request.dryRun) {
            logger.lifecycle(
                "[6/7] Dry-run mode: skipping disk writes (${results.size} file(s) would change)"
            )
            return WriteOutcome.EMPTY
        }
        return (request.changeWriter ?: DiskChangeWriter(request.projectDir, logger)).apply(results)
    }

    private fun rawDiffs(results: List<Result>): Map<Path, String> = results.mapNotNull { result ->
        val path = (result.after ?: result.before)?.sourcePath ?: return@mapNotNull null
        path to result.diff()
    }.toMap()

    private fun changedFiles(projectDir: Path, outcome: WriteOutcome): List<Path> =
        outcome.successes
            .filter { it.kind != ChangeKind.DELETED }
            .map { projectDir.resolve(it.path) }

    private fun normalizedPath(result: Result): String? =
        (result.after ?: result.before)?.sourcePath?.toString()?.replace('\\', '/')
}
