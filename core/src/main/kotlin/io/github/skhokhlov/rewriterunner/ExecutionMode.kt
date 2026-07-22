package io.github.skhokhlov.rewriterunner

/**
 * Controls where rewrite-runner performs post-plugin Lossless Semantic Tree work.
 *
 * [FORKED] is the default: the coordinator remains small while recipe resolution,
 * parsing, and application run in a short-lived worker JVM. [IN_PROCESS] is an
 * explicit compatibility mode for library callers that need rich OpenRewrite
 * [org.openrewrite.Result] objects or use a custom change writer.
 */
enum class ExecutionMode {
    FORKED,
    IN_PROCESS;

    companion object {
        /** Parse the YAML/CLI spelling used in public configuration. */
        fun parse(value: String): ExecutionMode = entries.firstOrNull {
            it.name.equals(value.trim().replace('-', '_'), ignoreCase = true)
        }
            ?: throw IllegalArgumentException(
                "execution mode must be 'forked' or 'in-process', not '$value'"
            )
    }
}
