package io.github.skhokhlov.rewriterunner.execution

import io.github.skhokhlov.rewriterunner.ExecutionDiagnostics
import io.github.skhokhlov.rewriterunner.ExecutorAttempt
import io.github.skhokhlov.rewriterunner.ExecutorOutcome
import io.github.skhokhlov.rewriterunner.ExecutorPhase
import io.github.skhokhlov.rewriterunner.LogicalExecutor
import io.github.skhokhlov.rewriterunner.ParseFailure
import io.github.skhokhlov.rewriterunner.RunnerLogger
import io.github.skhokhlov.rewriterunner.UsedExecutionStage
import io.github.skhokhlov.rewriterunner.WorkerCommand
import io.github.skhokhlov.rewriterunner.WorkerCommandFactory
import io.github.skhokhlov.rewriterunner.WorkerCommandRequest
import io.github.skhokhlov.rewriterunner.apply.AppliedChange
import io.github.skhokhlov.rewriterunner.apply.ApplyFailure
import io.github.skhokhlov.rewriterunner.apply.ChangeKind
import io.github.skhokhlov.rewriterunner.apply.WriteOutcome
import io.github.skhokhlov.rewriterunner.config.ParseConfig
import io.github.skhokhlov.rewriterunner.config.RepositoryConfig
import io.github.skhokhlov.rewriterunner.config.ToolConfig
import io.github.skhokhlov.rewriterunner.plugin.createPrivateTempDirectory
import io.github.skhokhlov.rewriterunner.plugin.createPrivateTempFile
import io.github.skhokhlov.rewriterunner.plugin.deleteRecursively
import java.io.BufferedReader
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

internal const val WORKER_PROTOCOL_VERSION = 1
internal const val WORKER_EVENT_PREFIX = "@@rewrite-runner-worker@@"

internal data class WorkerRequestEnvelope(
    val protocolVersion: Int = WORKER_PROTOCOL_VERSION,
    val request: WorkerRequestPayload
)

internal data class WorkerRequestPayload(
    val requestId: String,
    val scope: String,
    val projectDir: String,
    val activeRecipe: String,
    val recipeArtifacts: List<String>,
    val rewriteConfig: String?,
    val rewriteConfigContent: String?,
    val dryRun: Boolean,
    val excludePaths: List<String>,
    val plainTextMasks: List<String>,
    val protectedPaths: List<String>,
    val cacheDir: String,
    val repositories: List<RepositoryConfig>,
    val includeMavenCentral: Boolean,
    val downloadThreads: Int,
    val subprocessRunTimeoutMillis: Long,
    val pluginRunTimeoutMillis: Long,
    val resolverConnectTimeoutMillis: Long,
    val resolverRequestTimeoutMillis: Long
)

internal data class WorkerResponseEnvelope(
    val protocolVersion: Int = WORKER_PROTOCOL_VERSION,
    val requestId: String,
    val success: Boolean,
    val outcome: WorkerOutcomePayload? = null,
    val message: String? = null
)

internal data class WorkerOutcomePayload(
    val rawDiffs: Map<String, String>,
    val changedFiles: List<String>,
    val diagnostics: WorkerDiagnosticsPayload
)

internal data class WorkerDiagnosticsPayload(
    val stageUsed: String?,
    val resolvedJarCount: Int,
    val parseFailures: List<ParseFailure>,
    val parsedFileCount: Int?,
    val estimatedTimeSavedMillis: Long?,
    val writeSuccesses: List<WorkerAppliedChange>,
    val writeFailures: List<WorkerApplyFailure>
)

internal data class WorkerAppliedChange(val kind: String, val path: String)
internal data class WorkerApplyFailure(val kind: String, val path: String, val cause: String)

internal data class WorkerEvent(
    val type: String,
    val payload: WorkerHandshake? = null,
    val message: String? = null
)

internal data class WorkerHandshake(
    val protocolVersion: Int,
    val processId: Long,
    val javaVersion: String,
    val maximumHeapBytes: Long,
    val scopes: List<String>
)

internal object WorkerJson {
    val mapper: JsonMapper = JsonMapper.builder()
        .addModule(KotlinModule.Builder().build())
        .build()

    fun <T> read(text: String, type: Class<T>): T = mapper.readValue(text, type)
    fun write(value: Any): String = mapper.writeValueAsString(value)
}

/** Main class launched in a separate JVM. It must never call RewriteRunner.run(). */
object WorkerMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val requestFile = args.valueAfter("--request")?.let(Path::of)
        val responseFile = args.valueAfter("--response")?.let(Path::of)
        if (requestFile == null || responseFile == null) {
            System.err.println("Worker requires --request and --response")
            return
        }

        val envelope = try {
            WorkerJson.read(Files.readString(requestFile), WorkerRequestEnvelope::class.java)
        } catch (t: Throwable) {
            publishFailure(responseFile, "Could not read worker request: ${sanitize(t.message)}")
            return
        }
        if (envelope.protocolVersion != WORKER_PROTOCOL_VERSION) {
            publishFailure(
                responseFile,
                "Unsupported worker protocol ${envelope.protocolVersion}",
                envelope.request.requestId
            )
            return
        }

        emit(
            WorkerEvent(
                type = "handshake",
                payload =
                    WorkerHandshake(
                        protocolVersion = WORKER_PROTOCOL_VERSION,
                        processId = ProcessHandle.current().pid(),
                        javaVersion = System.getProperty("java.version"),
                        maximumHeapBytes = Runtime.getRuntime().maxMemory(),
                        scopes = WorkerScope.entries.map { it.name }
                    )
            )
        )

        try {
            val outcome = LstExecutionEngine(
                ProtocolLogger
            ).execute(envelope.request.toResolvedRequest())
            publishResponse(
                responseFile,
                WorkerResponseEnvelope(
                    requestId = envelope.request.requestId,
                    success = true,
                    outcome = outcome.toWire()
                )
            )
        } catch (t: Throwable) {
            publishFailure(responseFile, sanitize(t.message), envelope.request.requestId)
            System.exit(1)
        }
    }

    private fun emit(event: WorkerEvent) {
        println(WORKER_EVENT_PREFIX + WorkerJson.write(event))
        System.out.flush()
    }

    private object ProtocolLogger : RunnerLogger {
        override fun lifecycle(message: String) =
            emit(WorkerEvent("lifecycle", message = sanitize(message)))
        override fun info(message: String) = emit(WorkerEvent("info", message = sanitize(message)))
        override fun debug(message: String) =
            emit(WorkerEvent("debug", message = sanitize(message)))
        override fun warn(message: String) = emit(WorkerEvent("warn", message = sanitize(message)))
        override fun error(message: String, cause: Throwable?) =
            emit(WorkerEvent("error", message = sanitize(message)))
    }
}

private fun Array<String>.valueAfter(option: String): String? {
    val index = indexOf(option)
    return getOrNull(index + 1)
}

private fun publishFailure(responseFile: Path, message: String?, requestId: String = "unknown") {
    publishResponse(
        responseFile,
        WorkerResponseEnvelope(
            requestId = requestId,
            success = false,
            message =
                message ?: "worker failed"
        )
    )
}

private fun publishResponse(responseFile: Path, response: WorkerResponseEnvelope) {
    try {
        Files.createDirectories(responseFile.parent)
        val temporary = createPrivateTempFile(
            responseFile.parent,
            "${responseFile.fileName}.tmp-",
            ".json"
        )
        Files.writeString(temporary, WorkerJson.write(response))
        try {
            Files.move(
                temporary,
                responseFile,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, responseFile, StandardCopyOption.REPLACE_EXISTING)
        }
    } catch (t: Throwable) {
        System.err.println("Could not publish worker response: ${sanitize(t.message)}")
    }
}

private fun WorkerRequestPayload.toResolvedRequest(): ResolvedExecutionRequest {
    val subprocessTimeout = Duration.ofMillis(subprocessRunTimeoutMillis)
    val pluginTimeout = Duration.ofMillis(pluginRunTimeoutMillis)
    val resolverConnectTimeout = Duration.ofMillis(resolverConnectTimeoutMillis)
    val resolverRequestTimeout = Duration.ofMillis(resolverRequestTimeoutMillis)
    val config =
        ToolConfig(
            cacheDir = cacheDir,
            parse = ParseConfig(excludePaths = excludePaths, plainTextMasks = plainTextMasks),
            artifactDownloadThreads = downloadThreads,
            subprocessRunTimeout = subprocessTimeout,
            pluginRunTimeout = pluginTimeout,
            artifactResolverConnectTimeout = resolverConnectTimeout,
            artifactResolverRequestTimeout = resolverRequestTimeout,
            logger = ProtocolLoggerForRequest
        )
    return ResolvedExecutionRequest(
        requestId = requestId,
        scope = WorkerScope.valueOf(scope),
        projectDir = Path.of(projectDir),
        activeRecipe = activeRecipe,
        recipeArtifacts = recipeArtifacts,
        rewriteConfig = rewriteConfig?.let(Path::of),
        rewriteConfigContent = rewriteConfigContent,
        dryRun = dryRun,
        excludePaths = excludePaths,
        plainTextMasks = plainTextMasks,
        protectedPaths = protectedPaths.toSet(),
        toolConfig = config,
        cacheDir = Path.of(cacheDir),
        repositories = repositories,
        includeMavenCentral = includeMavenCentral,
        downloadThreads = downloadThreads,
        resolverConnectTimeout = resolverConnectTimeout,
        resolverRequestTimeout = resolverRequestTimeout
    )
}

private object ProtocolLoggerForRequest : RunnerLogger {
    override fun lifecycle(message: String) = Unit
    override fun info(message: String) = Unit
    override fun debug(message: String) = Unit
    override fun warn(message: String) = Unit
    override fun error(message: String, cause: Throwable?) = Unit
}

private fun LstExecutionOutcome.toWire(): WorkerOutcomePayload = WorkerOutcomePayload(
    rawDiffs = rawDiffs.mapKeys { (path, _) -> path.toString() },
    changedFiles = changedFiles.map(Path::toString),
    diagnostics = diagnostics.toWire()
)

private fun WorkerOutcomePayload.toOutcome(): LstExecutionOutcome = LstExecutionOutcome(
    results = emptyList(),
    rawDiffs = rawDiffs.mapKeys { (path, _) -> Path.of(path) },
    changedFiles = changedFiles.map(Path::of),
    diagnostics = diagnostics.toDiagnostics()
)

private fun ExecutionDiagnostics.toWire(): WorkerDiagnosticsPayload = WorkerDiagnosticsPayload(
    stageUsed = stageUsed?.name,
    resolvedJarCount = resolvedJarCount,
    parseFailures = parseFailures,
    parsedFileCount = parsedFileCount,
    estimatedTimeSavedMillis = estimatedTimeSaved?.toMillis(),
    writeSuccesses = writeOutcome.successes.map { WorkerAppliedChange(it.kind.name, it.path) },
    writeFailures = writeOutcome.failures.map {
        WorkerApplyFailure(it.kind.name, it.path, it.cause)
    }
)

private fun WorkerDiagnosticsPayload.toDiagnostics(): ExecutionDiagnostics = ExecutionDiagnostics(
    stageUsed = stageUsed?.let(UsedExecutionStage::valueOf),
    resolvedJarCount = resolvedJarCount,
    parseFailures = parseFailures,
    parsedFileCount = parsedFileCount,
    estimatedTimeSaved = estimatedTimeSavedMillis?.let(Duration::ofMillis),
    writeOutcome =
        WriteOutcome(
            successes = writeSuccesses.map {
                AppliedChange(ChangeKind.valueOf(it.kind), it.path)
            },
            failures = writeFailures.map {
                ApplyFailure(ChangeKind.valueOf(it.kind), it.path, it.cause)
            }
        )
)

internal data class ForkedExecutionResult(
    val outcome: LstExecutionOutcome,
    val attempt: ExecutorAttempt
)

/** A terminal worker error that carries a sanitized executor attempt for diagnostics. */
internal class ForkedWorkerException(val attempt: ExecutorAttempt) :
    IllegalStateException(attempt.message ?: "LST worker failed")

/** Owns the process boundary, framed events, bounded output tails, and private transport cleanup. */
internal class ForkedLstExecutor(
    private val logger: RunnerLogger,
    private val commandFactory: WorkerCommandFactory? = null
) {
    fun execute(
        request: ResolvedExecutionRequest,
        jvm: EffectiveJvmArguments,
        timeout: Duration?
    ): ForkedExecutionResult {
        val startedAt = System.nanoTime()
        val protocolDirectory = createPrivateTempDirectory("rewrite-runner-worker-")
        val requestFile = createPrivateTempFile(protocolDirectory, "request-", ".json")
        val responseFile = protocolDirectory.resolve("response.json")
        try {
            Files.writeString(
                requestFile,
                WorkerJson.write(WorkerRequestEnvelope(request = request.toWire()))
            )
            val command = buildCommand(protocolDirectory, requestFile, responseFile, jvm.args)
            val process = try {
                ProcessBuilder(command.command)
                    .directory(request.projectDir.toFile())
                    .apply { environment().putAll(command.environment) }
                    .start()
            } catch (t: Throwable) {
                throw failure(
                    request.scope,
                    jvm,
                    startedAt,
                    ExecutorOutcome.START_FAILURE,
                    "Could not start LST worker: ${sanitize(t.message)}"
                )
            }
            val handshake = AtomicReference<WorkerHandshake?>()
            val protocolFailure = AtomicReference<String?>()
            val stdoutTail = BoundedTail()
            val stderrTail = BoundedTail()
            val stdout = drain(process.inputStream, "stdout", stdoutTail) { line ->
                if (!line.startsWith(WORKER_EVENT_PREFIX)) return@drain
                try {
                    val event = WorkerJson.read(
                        line.removePrefix(WORKER_EVENT_PREFIX),
                        WorkerEvent::class.java
                    )
                    when (event.type) {
                        "handshake" -> {
                            val value = event.payload ?: error("handshake payload missing")
                            if (value.protocolVersion != WORKER_PROTOCOL_VERSION ||
                                request.scope.name !in value.scopes
                            ) {
                                protocolFailure.compareAndSet(
                                    null,
                                    "worker protocol version or supported scope mismatch"
                                )
                                terminateProcessTree(process)
                            } else {
                                handshake.compareAndSet(null, value)
                            }
                        }

                        "lifecycle" -> event.message?.let(logger::lifecycle)

                        "info" -> event.message?.let(logger::info)

                        "debug" -> event.message?.let(logger::debug)

                        "warn" -> event.message?.let(logger::warn)

                        "error" -> event.message?.let { logger.error(it) }

                        else -> {
                            protocolFailure.compareAndSet(null, "unknown worker event type")
                            terminateProcessTree(process)
                        }
                    }
                } catch (t: Throwable) {
                    protocolFailure.compareAndSet(null, "malformed worker protocol framing")
                    terminateProcessTree(process)
                }
            }
            val stderr = drain(process.errorStream, "stderr", stderrTail) { }

            val finished = try {
                if (timeout == null) {
                    process.waitFor()
                    true
                } else {
                    process.waitFor(timeout.toMillis().coerceAtLeast(1), TimeUnit.MILLISECONDS)
                }
            } catch (interrupted: InterruptedException) {
                terminateProcessTree(process)
                Thread.currentThread().interrupt()
                throw interrupted
            }
            if (!finished) {
                terminateProcessTree(process)
                stdout.join(1_000)
                stderr.join(1_000)
                throw failure(
                    request.scope,
                    jvm,
                    startedAt,
                    ExecutorOutcome.TIMEOUT,
                    "LST worker timed out after $timeout"
                )
            }
            stdout.join(1_000)
            stderr.join(1_000)
            val exitCode = process.exitValue()
            protocolFailure.get()?.let {
                throw failure(
                    request.scope,
                    jvm,
                    startedAt,
                    ExecutorOutcome.PROTOCOL_FAILURE,
                    it,
                    process,
                    handshake.get()
                )
            }
            val observed = handshake.get()
                ?: throw failure(
                    request.scope,
                    jvm,
                    startedAt,
                    ExecutorOutcome.PROTOCOL_FAILURE,
                    "LST worker did not emit a handshake",
                    process
                )
            if (!Files.exists(responseFile)) {
                val failureOutcome = classifyFailure(exitCode, stderrTail.value())
                throw failure(
                    request.scope,
                    jvm,
                    startedAt,
                    if (failureOutcome == ExecutorOutcome.FAILED) {
                        ExecutorOutcome.PROTOCOL_FAILURE
                    } else {
                        failureOutcome
                    },
                    "LST worker exited without a response: ${stderrTail.value()}",
                    process,
                    observed
                )
            }
            val response = try {
                WorkerJson.read(Files.readString(responseFile), WorkerResponseEnvelope::class.java)
            } catch (t: Throwable) {
                throw failure(
                    request.scope,
                    jvm,
                    startedAt,
                    ExecutorOutcome.PROTOCOL_FAILURE,
                    "Could not read worker response: ${sanitize(t.message)}",
                    process,
                    observed
                )
            }
            if (response.protocolVersion != WORKER_PROTOCOL_VERSION ||
                response.requestId != request.requestId
            ) {
                throw failure(
                    request.scope,
                    jvm,
                    startedAt,
                    ExecutorOutcome.PROTOCOL_FAILURE,
                    "Worker response did not match this request",
                    process,
                    observed
                )
            }
            if (!response.success || response.outcome == null || exitCode != 0) {
                throw failure(
                    request.scope,
                    jvm,
                    startedAt,
                    classifyFailure(exitCode, stderrTail.value()),
                    response.message ?: "LST worker failed",
                    process,
                    observed
                )
            }
            val outcome = response.outcome.toOutcome()
            return ForkedExecutionResult(
                outcome = outcome,
                attempt =
                    ExecutorAttempt(
                        executor = LogicalExecutor.LST_WORKER,
                        phase = phaseFor(request.scope),
                        processId = process.pid(),
                        jvmConfigurationSource = jvm.source,
                        requestedMaximumHeapBytes = jvm.maximumHeapBytes,
                        observedMaximumHeapBytes = observed.maximumHeapBytes,
                        durationMillis = elapsedMillis(startedAt),
                        outcome =
                            if (outcome.rawDiffs.isEmpty()) {
                                ExecutorOutcome.NO_CHANGES
                            } else {
                                ExecutorOutcome.SUCCESS
                            },
                        exitCode = exitCode
                    )
            )
        } finally {
            try {
                deleteRecursively(protocolDirectory)
            } catch (t: Throwable) {
                logger.warn(
                    "Could not clean up LST worker transport directory: ${sanitize(t.message)}"
                )
            }
        }
    }

    private fun buildCommand(
        directory: Path,
        requestFile: Path,
        responseFile: Path,
        jvmArgs: List<String>
    ): WorkerCommand {
        val javaExecutable =
            Path.of(System.getProperty("java.home"), "bin", if (isWindows()) "java.exe" else "java")
        val request =
            WorkerCommandRequest(
                javaExecutable = javaExecutable,
                classpath = System.getProperty("java.class.path"),
                mainClass = WorkerMain::class.java.name,
                requestDirectory = directory,
                requestFile = requestFile,
                responseFile = responseFile,
                jvmArgs = jvmArgs
            )
        return commandFactory?.create(request)
            ?: WorkerCommand(
                buildList {
                    add(javaExecutable.toString())
                    addAll(jvmArgs)
                    add("-cp")
                    add(request.classpath)
                    add(request.mainClass)
                    add("--request")
                    add(requestFile.toString())
                    add("--response")
                    add(responseFile.toString())
                }
            )
    }

    private fun failure(
        scope: WorkerScope,
        jvm: EffectiveJvmArguments,
        startedAt: Long,
        outcome: ExecutorOutcome,
        message: String,
        process: Process? = null,
        handshake: WorkerHandshake? = null
    ): ForkedWorkerException = ForkedWorkerException(
        ExecutorAttempt(
            executor = LogicalExecutor.LST_WORKER,
            phase = phaseFor(scope),
            processId = process?.pid(),
            jvmConfigurationSource = jvm.source,
            requestedMaximumHeapBytes = jvm.maximumHeapBytes,
            observedMaximumHeapBytes = handshake?.maximumHeapBytes,
            durationMillis = elapsedMillis(startedAt),
            outcome = outcome,
            exitCode = process?.takeIf { !it.isAlive }?.exitValue(),
            message = sanitize(message).take(2_000)
        )
    )

    private fun drain(
        input: InputStream,
        tag: String,
        tail: BoundedTail,
        consumer: (String) -> Unit
    ): Thread = thread(start = true, isDaemon = true, name = "rewrite-runner-worker-$tag") {
        try {
            BufferedReader(input.reader()).useLines { lines ->
                lines.forEach { line ->
                    tail.append(line)
                    consumer(line)
                    if (!line.startsWith(
                            WORKER_EVENT_PREFIX
                        )
                    ) {
                        logger.debug("[LST worker $tag] ${sanitize(line)}")
                    }
                }
            }
        } catch (_: Exception) {
            // Stream closure is expected during timeout/interruption cleanup.
        }
    }
}

private fun ResolvedExecutionRequest.toWire(): WorkerRequestPayload = WorkerRequestPayload(
    requestId = requestId,
    scope = scope.name,
    projectDir = projectDir.toString(),
    activeRecipe = activeRecipe,
    recipeArtifacts = recipeArtifacts,
    rewriteConfig = rewriteConfig?.toString(),
    rewriteConfigContent = rewriteConfigContent,
    dryRun = dryRun,
    excludePaths = excludePaths,
    plainTextMasks = plainTextMasks,
    protectedPaths = protectedPaths.toList(),
    cacheDir = cacheDir.toString(),
    repositories = repositories,
    includeMavenCentral = includeMavenCentral,
    downloadThreads = downloadThreads,
    subprocessRunTimeoutMillis = toolConfig.subprocessRunTimeout.toMillis(),
    pluginRunTimeoutMillis = toolConfig.pluginRunTimeout.toMillis(),
    resolverConnectTimeoutMillis = resolverConnectTimeout.toMillis(),
    resolverRequestTimeoutMillis = resolverRequestTimeout.toMillis()
)

internal fun phaseFor(scope: WorkerScope): ExecutorPhase = when (scope) {
    WorkerScope.FULL_FALLBACK -> ExecutorPhase.FULL_FALLBACK
    WorkerScope.SPECIALIZED_ONLY -> ExecutorPhase.SPECIALIZED_ONLY
}

private fun classifyFailure(exitCode: Int, output: String): ExecutorOutcome = when {
    output.contains("OutOfMemoryError", ignoreCase = true) -> ExecutorOutcome.CONFIRMED_HEAP_OOM
    exitCode == 137 -> ExecutorOutcome.LIKELY_OOM
    else -> ExecutorOutcome.FAILED
}

private fun terminateProcessTree(process: Process) {
    process.toHandle().descendants().forEach { handle ->
        runCatching { handle.destroyForcibly() }
    }
    runCatching { process.destroyForcibly() }
}

internal fun elapsedMillis(startedAt: Long): Long = (System.nanoTime() - startedAt) / 1_000_000

private fun isWindows(): Boolean =
    System.getProperty("os.name", "").contains("win", ignoreCase = true)

private class BoundedTail(private val limit: Int = 8_192) {
    private val value = StringBuilder()

    @Synchronized
    fun append(line: String) {
        value.append(sanitize(line)).append('\n')
        if (value.length > limit) value.delete(0, value.length - limit)
    }

    @Synchronized
    fun value(): String = value.toString().trim().takeLast(limit)
}

private fun sanitize(value: String?): String = value.orEmpty()
    .replace(Regex("(?i)(password|token|secret)=([^\\s&]+)"), "$1=<redacted>")
    .replace(Regex("(?i)(https?://)[^/@\\s]+@"), "$1<redacted>@")
