package io.github.skhokhlov.rewriterunner

import org.eclipse.aether.transfer.AbstractTransferListener
import org.eclipse.aether.transfer.TransferEvent
import org.slf4j.LoggerFactory

/**
 * Logs Maven artifact download progress to SLF4J in the same format Maven itself uses:
 *
 * ```
 * Downloading from central: https://repo.maven.apache.org/maven2/org/foo/bar-1.0.jar
 * Downloaded from central: https://repo.maven.apache.org/maven2/org/foo/bar-1.0.jar (5 kB at 234 kB/s)
 * ```
 *
 * Only GET requests (downloads) are logged; PUT uploads are silently ignored.
 */
class MavenTransferListener : AbstractTransferListener() {
    private val log = LoggerFactory.getLogger(MavenTransferListener::class.java)

    override fun transferStarted(event: TransferEvent) {
        if (event.requestType != TransferEvent.RequestType.GET) return
        val r = event.resource
        log.info("Downloading from {}: {}{}", r.repositoryId, r.repositoryUrl, r.resourceName)
    }

    override fun transferSucceeded(event: TransferEvent) {
        if (event.requestType != TransferEvent.RequestType.GET) return
        val r = event.resource
        val bytes = event.transferredBytes
        val elapsedMs = java.time.Duration.between(r.startTime, java.time.Instant.now()).toMillis()
        val size = formatBytes(bytes)
        val speed = if (elapsedMs > 0) "${formatBytes(bytes * 1_000L / elapsedMs)}/s" else "?"
        log.info(
            "Downloaded from {}: {}{} ({} at {})",
            r.repositoryId,
            r.repositoryUrl,
            r.resourceName,
            size,
            speed,
        )
    }

    override fun transferFailed(event: TransferEvent) {
        val r = event.resource
        log.warn(
            "Failed to download {}{}: {}",
            r.repositoryUrl,
            r.resourceName,
            event.exception?.message ?: "unknown error",
        )
    }

    private fun formatBytes(bytes: Long): String =
        when {
            bytes < 1_024 -> "$bytes B"
            bytes < 1_024 * 1_024 -> "${bytes / 1_024} kB"
            else -> String.format("%.1f MB", bytes.toDouble() / (1_024 * 1_024))
        }
}
