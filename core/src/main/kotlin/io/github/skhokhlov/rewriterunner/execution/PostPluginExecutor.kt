package io.github.skhokhlov.rewriterunner.execution

import io.github.skhokhlov.rewriterunner.ExecutorAttempt
import io.github.skhokhlov.rewriterunner.ExecutorOutcome
import io.github.skhokhlov.rewriterunner.ExecutorPhase
import io.github.skhokhlov.rewriterunner.LogicalExecutor
import io.github.skhokhlov.rewriterunner.RunnerLogger
import io.github.skhokhlov.rewriterunner.WorkerCommandFactory

/** Domain seam between the coordinator and either form of post-plugin LST execution. */
internal fun interface PostPluginExecutor {
    fun execute(request: ResolvedExecutionRequest): PostPluginExecutionOutcome
}

/** LST outcome together with the compact executor record the coordinator must aggregate. */
internal data class PostPluginExecutionOutcome(
    val outcome: LstExecutionOutcome,
    val attempt: ExecutorAttempt
)

/** Explicit compatibility executor that keeps rich Result objects in the coordinator JVM. */
internal class InProcessLstExecutor(private val logger: RunnerLogger) : PostPluginExecutor {
    override fun execute(request: ResolvedExecutionRequest): PostPluginExecutionOutcome {
        val startedAt = System.nanoTime()
        val outcome = LstExecutionEngine(logger).execute(request)
        return PostPluginExecutionOutcome(
            outcome = outcome,
            attempt =
                ExecutorAttempt(
                    executor = LogicalExecutor.LST_WORKER,
                    phase = phaseFor(request.scope),
                    jvmConfigurationSource = JvmConfigurationSource.RUNNER,
                    requestedMaximumHeapBytes = Runtime.getRuntime().maxMemory(),
                    observedMaximumHeapBytes = Runtime.getRuntime().maxMemory(),
                    durationMillis = elapsedMillis(startedAt),
                    outcome =
                        if (outcome.rawDiffs.isEmpty()) {
                            ExecutorOutcome.NO_CHANGES
                        } else {
                            ExecutorOutcome.SUCCESS
                        }
                )
        )
    }
}

/** Forked adapter whose launch and framing mechanics remain encapsulated by [ForkedLstExecutor]. */
internal class ForkedPostPluginExecutor(
    private val logger: RunnerLogger,
    private val jvm: EffectiveJvmArguments,
    private val timeout: java.time.Duration?,
    private val commandFactory: WorkerCommandFactory? = null
) : PostPluginExecutor {
    override fun execute(request: ResolvedExecutionRequest): PostPluginExecutionOutcome =
        ForkedLstExecutor(logger, commandFactory)
            .execute(request, jvm, timeout)
            .let { PostPluginExecutionOutcome(it.outcome, it.attempt) }
}
