package io.github.skhokhlov.rewriterunner.apply

import org.openrewrite.Result

/** The kind of file change represented by an OpenRewrite [Result]. */
enum class ChangeKind {
    CREATED,
    MODIFIED,
    DELETED;

    companion object {
        fun from(result: Result): ChangeKind = when {
            result.before == null -> CREATED
            result.after == null -> DELETED
            else -> MODIFIED
        }
    }
}

/** A change that was successfully applied to a target. */
data class AppliedChange(val kind: ChangeKind, val path: String)

/** A change that could not be applied to a target. */
data class ApplyFailure(val kind: ChangeKind, val path: String, val cause: String)

/**
 * Per-file outcome for applying OpenRewrite results to a target.
 *
 * Dry-run, plugin-only, and no-change runs use [EMPTY].
 */
data class WriteOutcome(
    val successes: List<AppliedChange> = emptyList(),
    val failures: List<ApplyFailure> = emptyList()
) {
    val failed: Boolean get() = failures.isNotEmpty()

    companion object {
        val EMPTY = WriteOutcome()
    }
}

internal interface ChangeWriter {
    fun apply(results: List<Result>): WriteOutcome
}

internal fun Result.changePath(): String = (after ?: before)?.sourcePath?.toString()
    ?: "(unknown)"
