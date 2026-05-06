package io.github.skhokhlov.rewriterunner

import java.time.Duration

/** Central defaults for external execution and resolver network timeouts. */
object ExecutionTimeouts {
    val DEFAULT_PROCESS_TIMEOUT: Duration = Duration.ofSeconds(120)
    val DEFAULT_PLUGIN_TIMEOUT: Duration = Duration.ofMinutes(10)
    val DEFAULT_RESOLVER_CONNECT_TIMEOUT: Duration = Duration.ofSeconds(30)
    val DEFAULT_RESOLVER_REQUEST_TIMEOUT: Duration = Duration.ofSeconds(60)
}
