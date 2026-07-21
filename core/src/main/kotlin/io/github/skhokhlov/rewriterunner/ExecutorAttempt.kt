package io.github.skhokhlov.rewriterunner

import io.github.skhokhlov.rewriterunner.execution.JvmConfigurationSource

/** Logical process responsible for an execution attempt. */
enum class LogicalExecutor { GRADLE_PLUGIN, MAVEN_PLUGIN, LST_WORKER }

/** A compact phase label for a runner-owned executor attempt. */
enum class ExecutorPhase { PLUGIN_DRY_RUN, PLUGIN_APPLY, FULL_FALLBACK, SPECIALIZED_ONLY }

/** Terminal status of a runner-owned executor attempt. */
enum class ExecutorOutcome {
    SUCCESS,
    NO_CHANGES,
    FAILED,
    START_FAILURE,
    TIMEOUT,
    LIKELY_OOM,
    CONFIRMED_HEAP_OOM,
    PROTOCOL_FAILURE
}

/**
 * Serializable, sanitized record of a process attempt. It deliberately contains no raw command,
 * arbitrary JVM flags, credentials, or absolute host paths.
 */
data class ExecutorAttempt(
    val executor: LogicalExecutor,
    val phase: ExecutorPhase,
    val workingDirectory: String = ".",
    val processId: Long? = null,
    val jvmConfigurationSource: JvmConfigurationSource,
    val requestedMaximumHeapBytes: Long? = null,
    val observedMaximumHeapBytes: Long? = null,
    val durationMillis: Long,
    val outcome: ExecutorOutcome,
    val exitCode: Int? = null,
    val message: String? = null
)
