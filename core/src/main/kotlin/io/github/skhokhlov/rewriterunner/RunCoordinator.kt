package io.github.skhokhlov.rewriterunner

import io.github.skhokhlov.rewriterunner.config.DurationParser
import io.github.skhokhlov.rewriterunner.config.ExecutionConfig
import io.github.skhokhlov.rewriterunner.config.RepositoryConfig
import io.github.skhokhlov.rewriterunner.config.ToolConfig
import io.github.skhokhlov.rewriterunner.execution.AutomaticHeapSizer
import io.github.skhokhlov.rewriterunner.execution.EffectiveJvmArguments
import io.github.skhokhlov.rewriterunner.execution.ForkedPostPluginExecutor
import io.github.skhokhlov.rewriterunner.execution.ForkedWorkerException
import io.github.skhokhlov.rewriterunner.execution.InProcessLstExecutor
import io.github.skhokhlov.rewriterunner.execution.JvmArgumentPolicy
import io.github.skhokhlov.rewriterunner.execution.PostPluginExecutionOutcome
import io.github.skhokhlov.rewriterunner.execution.PostPluginExecutor
import io.github.skhokhlov.rewriterunner.execution.ResolvedExecutionRequest
import io.github.skhokhlov.rewriterunner.execution.WorkerScope
import io.github.skhokhlov.rewriterunner.lst.SpecializedOwnership
import io.github.skhokhlov.rewriterunner.lst.utils.FileCollector
import io.github.skhokhlov.rewriterunner.lst.utils.hasBuildGradle
import io.github.skhokhlov.rewriterunner.plugin.PluginProcessAttempt
import io.github.skhokhlov.rewriterunner.plugin.PluginRecipeRunner
import io.github.skhokhlov.rewriterunner.plugin.PluginRunResult
import java.nio.file.FileSystems
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.PathMatcher
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.time.Duration
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.io.path.exists

/**
 * Coordinator for one public RewriteRunner invocation. It holds the global fair gate while a
 * plugin attempt and any subsequent LST execution run, so runner-owned heavy sequences never
 * overlap inside one coordinator JVM.
 */
internal class RunCoordinator(private val config: RewriteRunner.Builder) {
    fun run(): RunResult = executionGate.withLock {
        require(config.projectDir.toFile().isDirectory) {
            "projectDir does not exist or is not a directory: ${config.projectDir}"
        }
        val logger = config.logger
        val effective = resolve()
        require(effective.mode == ExecutionMode.IN_PROCESS || config.changeWriter == null) {
            "Custom ChangeWriter implementations require executionMode(IN_PROCESS)"
        }
        logger.lifecycle(memoryPlan(effective))

        val attempts = mutableListOf<ExecutorAttempt>()
        if (config.skipPluginRun) {
            return@withLock runFullFallback(effective, null, attempts)
        }

        val pluginPolicy =
            JvmArgumentPolicy.forPlugin(
                projectDir = config.projectDir,
                isGradle = hasBuildGradle(config.projectDir),
                shared = effective.execution.executorJvmArgs,
                specific = effective.execution.plugin.jvmArgs
            )
        pluginPolicy.warning?.let(logger::warn)
        val pluginProcessAttempts = mutableListOf<PluginProcessAttempt>()
        val pluginResult =
            PluginRecipeRunner(
                logger = logger,
                timeout = effective.pluginTimeout,
                rewriteGradlePluginVersion = effective.toolConfig.rewriteGradlePluginVersion,
                rewriteMavenPluginVersion = effective.toolConfig.rewriteMavenPluginVersion,
                pluginJvmArgs = pluginPolicy.args,
                attemptCollector = pluginProcessAttempts::add
            ).run(
                projectDir = config.projectDir,
                activeRecipe = config.activeRecipe,
                recipeArtifacts = config.recipeArtifacts,
                rewriteConfig = config.rewriteConfig,
                rewriteConfigContent = config.rewriteConfigContent,
                dryRun = config.dryRun,
                includeMavenCentral = effective.includeMavenCentral,
                artifactRepositories = effective.repositories,
                excludePaths = effective.excludePaths + SpecializedOwnership.stage0ExcludeGlobs,
                plainTextMasks = effective.plainTextMasks
            )
        attempts += pluginProcessAttempts.map { pluginAttempt(it, pluginPolicy) }

        return@withLock when (pluginResult) {
            is PluginRunResult.Success,
            PluginRunResult.NoChanges -> runSpecializedOnly(effective, pluginResult, attempts)

            is PluginRunResult.Partial -> runFullFallback(effective, pluginResult, attempts)

            is PluginRunResult.Failed,
            is PluginRunResult.Skipped -> runFullFallback(effective, null, attempts)
        }
    }

    private fun runFullFallback(
        effective: EffectiveConfiguration,
        partial: PluginRunResult.Partial?,
        attempts: MutableList<ExecutorAttempt>
    ): RunResult {
        val request = effective.request(WorkerScope.FULL_FALLBACK, partial?.diffs?.keys.orEmpty())
        val execution = executePostPlugin(effective, request)
        attempts += execution.attempt
        val outcome = execution.outcome
        val partialDiffs = partial?.diffs.orEmpty()
        val partialChangedFiles = partial?.changedFiles.orEmpty()
        return RunResult(
            results = if (effective.mode ==
                ExecutionMode.IN_PROCESS
            ) {
                outcome.results
            } else {
                emptyList()
            },
            changedFiles = (partialChangedFiles + outcome.changedFiles).distinct(),
            projectDir = config.projectDir,
            rawDiffs =
                partialDiffs +
                    if (effective.mode == ExecutionMode.FORKED) outcome.rawDiffs else emptyMap(),
            executionDiagnostics =
                outcome.diagnostics.copy(
                    stageUsed = if (partial !=
                        null
                    ) {
                        UsedExecutionStage.PLUGIN
                    } else {
                        outcome.diagnostics.stageUsed
                    },
                    estimatedTimeSaved =
                        if (partial == null) {
                            outcome.diagnostics.estimatedTimeSaved
                        } else {
                            (partial.estimatedTimeSaved ?: Duration.ZERO).plus(
                                outcome.diagnostics.estimatedTimeSaved ?: Duration.ZERO
                            )
                        },
                    executorAttempts = attempts.toList()
                )
        )
    }

    private fun runSpecializedOnly(
        effective: EffectiveConfiguration,
        pluginResult: PluginRunResult,
        attempts: MutableList<ExecutorAttempt>
    ): RunResult {
        if (!hasSpecializedCandidate(effective.excludePaths)) {
            return pluginOnlyResult(pluginResult, attempts)
        }
        val request = effective.request(WorkerScope.SPECIALIZED_ONLY)
        val execution = try {
            executePostPlugin(effective, request)
        } catch (failure: ForkedWorkerException) {
            loggerSpecializedFailure(failure.message)
            attempts += failure.attempt
            return pluginOnlyResult(pluginResult, attempts)
        } catch (failure: Exception) {
            loggerSpecializedFailure(failure.message)
            attempts +=
                ExecutorAttempt(
                    executor = LogicalExecutor.LST_WORKER,
                    phase = ExecutorPhase.SPECIALIZED_ONLY,
                    jvmConfigurationSource =
                        if (effective.mode == ExecutionMode.FORKED) {
                            JvmArgumentPolicy.forWorker(
                                effective.execution.executorJvmArgs,
                                effective.execution.lstWorker.jvmArgs
                            ).source
                        } else {
                            io.github.skhokhlov.rewriterunner.execution
                                .JvmConfigurationSource.RUNNER
                        },
                    durationMillis = 0,
                    outcome = ExecutorOutcome.FAILED,
                    message = failure.message?.take(2_000)
                )
            return pluginOnlyResult(pluginResult, attempts)
        }
        attempts += execution.attempt
        val outcome = execution.outcome
        val pluginDiffs = pluginDiffs(pluginResult)
        val pluginChangedFiles = pluginChangedFiles(pluginResult)
        return RunResult(
            results = if (effective.mode ==
                ExecutionMode.IN_PROCESS
            ) {
                outcome.results
            } else {
                emptyList()
            },
            changedFiles = (pluginChangedFiles + outcome.changedFiles).distinct(),
            projectDir = config.projectDir,
            rawDiffs =
                pluginDiffs +
                    if (effective.mode == ExecutionMode.FORKED) outcome.rawDiffs else emptyMap(),
            executionDiagnostics =
                outcome.diagnostics.copy(
                    stageUsed = UsedExecutionStage.PLUGIN,
                    estimatedTimeSaved =
                        (pluginTimeSaved(pluginResult) ?: Duration.ZERO).plus(
                            outcome.diagnostics.estimatedTimeSaved ?: Duration.ZERO
                        ),
                    executorAttempts = attempts.toList()
                )
        )
    }

    private fun executePostPlugin(
        effective: EffectiveConfiguration,
        request: ResolvedExecutionRequest
    ): PostPluginExecutionOutcome {
        val executor: PostPluginExecutor = if (effective.mode == ExecutionMode.IN_PROCESS) {
            InProcessLstExecutor(config.logger)
        } else {
            val policy =
                JvmArgumentPolicy.forWorker(
                    effective.execution.executorJvmArgs,
                    effective.execution.lstWorker.jvmArgs
                )
            policy.warning?.let(config.logger::warn)
            ForkedPostPluginExecutor(
                logger = config.logger,
                jvm = policy,
                timeout = effective.workerTimeout,
                commandFactory = config.workerCommandFactory
            )
        }
        return executor.execute(request)
    }

    private fun pluginOnlyResult(
        pluginResult: PluginRunResult,
        attempts: List<ExecutorAttempt>
    ): RunResult = RunResult(
        results = emptyList(),
        changedFiles = pluginChangedFiles(pluginResult),
        projectDir = config.projectDir,
        rawDiffs = pluginDiffs(pluginResult),
        executionDiagnostics =
            ExecutionDiagnostics.PLUGIN.copy(
                estimatedTimeSaved = pluginTimeSaved(pluginResult),
                executorAttempts = attempts.toList()
            )
    )

    private fun pluginAttempt(
        attempt: PluginProcessAttempt,
        jvm: EffectiveJvmArguments
    ): ExecutorAttempt = ExecutorAttempt(
        executor = attempt.executor,
        phase = attempt.phase,
        workingDirectory = attempt.workingDirectory,
        jvmConfigurationSource = jvm.source,
        requestedMaximumHeapBytes = jvm.maximumHeapBytes,
        durationMillis = attempt.durationMillis,
        outcome = attempt.outcome,
        exitCode = attempt.exitCode,
        message = attempt.message?.take(2_000)
    )

    private fun resolve(): EffectiveConfiguration {
        val configFile =
            config.configFile
                ?: RewriteRunner.findConfigCaseInsensitive(config.projectDir)
                ?: RewriteRunner.findConfigCaseInsensitive(
                    Paths.get(System.getProperty("user.home"), ".rewriterunner")
                )
        val toolConfig = ToolConfig.load(configFile, config.logger)
        val processTimeout = DurationParser.requirePositive(
            config.subprocessRunTimeout ?: toolConfig.subprocessRunTimeout,
            "processTimeout"
        )
        val pluginTimeout = DurationParser.requirePositive(
            config.pluginRunTimeout ?: toolConfig.pluginRunTimeout,
            "pluginTimeout"
        )
        val resolverConnectTimeout = DurationParser.requirePositive(
            config.artifactResolverConnectTimeout ?: toolConfig.artifactResolverConnectTimeout,
            "resolverConnectTimeout"
        )
        val resolverRequestTimeout = DurationParser.requirePositive(
            config.artifactResolverRequestTimeout ?: toolConfig.artifactResolverRequestTimeout,
            "resolverRequestTimeout"
        )
        val configuredExecution = toolConfig.execution
        val execution =
            ExecutionConfig(
                mode = config.executionMode ?: configuredExecution.mode,
                executorJvmArgs = config.executorJvmArgs ?: configuredExecution.executorJvmArgs,
                plugin =
                    configuredExecution.plugin.copy(
                        jvmArgs = config.pluginExecutorJvmArgs ?: configuredExecution.plugin.jvmArgs
                    ),
                lstWorker =
                    configuredExecution.lstWorker.copy(
                        jvmArgs = config.lstWorkerJvmArgs ?: configuredExecution.lstWorker.jvmArgs,
                        timeout = config.lstWorkerTimeout ?: configuredExecution.lstWorker.timeout
                    )
            )
        execution.lstWorker.timeout?.let { DurationParser.requirePositive(it, "lstWorkerTimeout") }
        return EffectiveConfiguration(
            mode = execution.mode,
            execution = execution,
            toolConfig =
                toolConfig.copy(
                    subprocessRunTimeout = processTimeout,
                    pluginRunTimeout = pluginTimeout,
                    artifactResolverConnectTimeout = resolverConnectTimeout,
                    artifactResolverRequestTimeout = resolverRequestTimeout
                ),
            cacheDir =
                (config.cacheDir ?: toolConfig.resolvedCacheDir()).toAbsolutePath().normalize(),
            excludePaths = config.excludePaths.ifEmpty { toolConfig.parse.excludePaths },
            plainTextMasks = toolConfig.resolvedPlainTextMasks(config.plainTextMasks),
            repositories = toolConfig.resolvedArtifactRepositories() + config.artifactRepositories,
            includeMavenCentral = config.includeMavenCentral ?: toolConfig.includeMavenCentral,
            downloadThreads = config.artifactDownloadThreads ?: toolConfig.artifactDownloadThreads,
            pluginTimeout = pluginTimeout,
            resolverConnectTimeout = resolverConnectTimeout,
            resolverRequestTimeout = resolverRequestTimeout,
            workerTimeout = execution.lstWorker.timeout
        )
    }

    private fun EffectiveConfiguration.request(
        scope: WorkerScope,
        protectedPaths: Set<Path> = emptySet()
    ): ResolvedExecutionRequest = ResolvedExecutionRequest(
        requestId = UUID.randomUUID().toString(),
        scope = scope,
        projectDir = config.projectDir.toAbsolutePath().normalize(),
        activeRecipe = config.activeRecipe,
        recipeArtifacts = config.recipeArtifacts,
        rewriteConfig = config.rewriteConfig?.toAbsolutePath()?.normalize(),
        rewriteConfigContent = config.rewriteConfigContent,
        dryRun = config.dryRun,
        excludePaths = excludePaths,
        plainTextMasks = plainTextMasks,
        protectedPaths = protectedPaths.map { it.toString().replace('\\', '/') }.toSet(),
        toolConfig = toolConfig,
        cacheDir = cacheDir,
        repositories = repositories,
        includeMavenCentral = includeMavenCentral,
        downloadThreads = downloadThreads,
        resolverConnectTimeout = resolverConnectTimeout,
        resolverRequestTimeout = resolverRequestTimeout,
        changeWriter = config.changeWriter
    )

    private fun memoryPlan(effective: EffectiveConfiguration): String {
        val plugin = JvmArgumentPolicy.forPlugin(
            config.projectDir,
            hasBuildGradle(config.projectDir),
            effective.execution.executorJvmArgs,
            effective.execution.plugin.jvmArgs
        )
        val worker = JvmArgumentPolicy.forWorker(
            effective.execution.executorJvmArgs,
            effective.execution.lstWorker.jvmArgs
        )
        return buildString {
            append("Memory plan: Coordinator: caller-managed; ")
            append("plugin: ${describeHeap(plugin)}; ")
            append("LST worker: ${describeHeap(worker)}; ")
            append("heavy execution: sequential")
        }
    }

    private fun describeHeap(jvm: EffectiveJvmArguments): String =
        jvm.maximumHeapBytes?.let(AutomaticHeapSizer::formatBytes)
            ?.plus(" (${jvm.source.name.lowercase()})")
            ?: jvm.source.name.lowercase()

    private fun loggerSpecializedFailure(message: String?) {
        config.logger.warn(
            "Specialized non-JVM parser pass failed after a successful plugin run; " +
                "preserving plugin results. Cause: ${message ?: "unknown failure"}"
        )
    }

    /**
     * Avoid starting a worker after a plugin-only run when no owned file can possibly be in scope.
     * The preflight is deliberately bounded and conservative: an excluded or conventional output
     * directory is pruned, while reaching the depth limit returns true so an uncertain scan never
     * suppresses a potentially required specialized pass.
     */
    private fun hasSpecializedCandidate(excludePaths: List<String>): Boolean = try {
        val projectDir = config.projectDir.toAbsolutePath().normalize()
        val excludeMatchers = excludePaths.map {
            FileSystems.getDefault().getPathMatcher("glob:$it")
        }
        var found = false
        var depthLimitReached = false
        Files.walkFileTree(
            projectDir,
            emptySet(),
            SPECIALIZED_CANDIDATE_WALK_MAX_DEPTH,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(
                    directory: Path,
                    attrs: BasicFileAttributes
                ): FileVisitResult {
                    if (directory == projectDir) return FileVisitResult.CONTINUE
                    val relative = projectDir.relativize(directory)
                    if (relative.any { it.toString() in FileCollector.DEFAULT_EXCLUDED_DIRS } ||
                        matchesExcluded(relative, excludeMatchers)
                    ) {
                        return FileVisitResult.SKIP_SUBTREE
                    }
                    if (relative.nameCount >= SPECIALIZED_CANDIDATE_WALK_MAX_DEPTH) {
                        depthLimitReached = true
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val relative = projectDir.relativize(file)
                    if (!attrs.isRegularFile || matchesExcluded(relative, excludeMatchers)) {
                        return FileVisitResult.CONTINUE
                    }
                    val name = file.fileName.toString()
                    if (name.startsWith("Dockerfile") ||
                        name.startsWith("Containerfile") ||
                        SpecializedOwnership.extensions.any { extension ->
                            name.endsWith(extension)
                        }
                    ) {
                        found = true
                        return FileVisitResult.TERMINATE
                    }
                    return FileVisitResult.CONTINUE
                }
            }
        )
        found || depthLimitReached
    } catch (_: Exception) {
        // A failed preflight must not suppress the owned-set execution path.
        true
    }

    private data class EffectiveConfiguration(
        val mode: ExecutionMode,
        val execution: ExecutionConfig,
        val toolConfig: ToolConfig,
        val cacheDir: Path,
        val excludePaths: List<String>,
        val plainTextMasks: List<String>,
        val repositories: List<RepositoryConfig>,
        val includeMavenCentral: Boolean,
        val downloadThreads: Int,
        val pluginTimeout: Duration,
        val resolverConnectTimeout: Duration,
        val resolverRequestTimeout: Duration,
        val workerTimeout: Duration?
    )

    private companion object {
        val executionGate = ReentrantLock(true)
        const val SPECIALIZED_CANDIDATE_WALK_MAX_DEPTH = 12

        fun matchesExcluded(relative: Path, matchers: List<PathMatcher>): Boolean =
            matchers.any { it.matches(relative) }

        fun pluginDiffs(result: PluginRunResult): Map<Path, String> = when (result) {
            is PluginRunResult.Success -> result.diffs
            is PluginRunResult.Partial -> result.diffs
            else -> emptyMap()
        }

        fun pluginChangedFiles(result: PluginRunResult): List<Path> = when (result) {
            is PluginRunResult.Success -> result.changedFiles
            is PluginRunResult.Partial -> result.changedFiles
            else -> emptyList()
        }

        fun pluginTimeSaved(result: PluginRunResult): Duration? = when (result) {
            is PluginRunResult.Success -> result.estimatedTimeSaved
            is PluginRunResult.Partial -> result.estimatedTimeSaved
            PluginRunResult.NoChanges -> Duration.ZERO
            else -> null
        }
    }
}
