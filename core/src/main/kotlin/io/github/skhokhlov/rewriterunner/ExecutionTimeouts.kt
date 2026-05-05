package io.github.skhokhlov.rewriterunner

/** Central defaults for external execution and resolver network timeouts. */
object ExecutionTimeouts {
    const val DEFAULT_PROCESS_TIMEOUT_SECONDS = 120L
    const val DEFAULT_PLUGIN_TIMEOUT_SECONDS = 600L
    const val DEFAULT_RESOLVER_CONNECT_TIMEOUT_MS = 30_000
    const val DEFAULT_RESOLVER_REQUEST_TIMEOUT_MS = 60_000
}
