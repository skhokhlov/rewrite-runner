package io.github.skhokhlov.rewriterunner.apply

import org.openrewrite.Result

internal class InMemoryChangeWriter(private val failPaths: Set<String> = emptySet()) :
    ChangeWriter {
    val recordedResults = mutableListOf<Result>()

    override fun apply(results: List<Result>): WriteOutcome {
        val successes = mutableListOf<AppliedChange>()
        val failures = mutableListOf<ApplyFailure>()

        for (result in results) {
            recordedResults += result
            val kind = ChangeKind.from(result)
            val path = result.changePath()
            if (path in failPaths) {
                failures += ApplyFailure(kind, path, "configured failure")
            } else {
                successes += AppliedChange(kind, path)
            }
        }

        return WriteOutcome(successes = successes, failures = failures)
    }
}
