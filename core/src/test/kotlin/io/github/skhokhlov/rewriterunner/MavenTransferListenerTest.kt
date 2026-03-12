package io.github.skhokhlov.rewriterunner

import io.kotest.core.spec.style.FunSpec
import java.nio.file.Files
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.eclipse.aether.artifact.DefaultArtifact
import org.eclipse.aether.metadata.DefaultMetadata
import org.eclipse.aether.metadata.Metadata
import org.eclipse.aether.repository.LocalRepository
import org.eclipse.aether.supplier.RepositorySystemSupplier
import org.eclipse.aether.transfer.ArtifactNotFoundException
import org.eclipse.aether.transfer.MetadataNotFoundException
import org.eclipse.aether.transfer.TransferEvent
import org.eclipse.aether.transfer.TransferResource

/** Simple [RunnerLogger] that records calls for assertion in tests. */
private class CapturingLogger : RunnerLogger {
    data class LogEntry(val level: String, val message: String)

    val entries = mutableListOf<LogEntry>()

    override fun lifecycle(message: String) = entries.add(LogEntry("INFO", message)).let { Unit }
    override fun info(message: String) = entries.add(LogEntry("INFO", message)).let { Unit }
    override fun debug(message: String) = entries.add(LogEntry("DEBUG", message)).let { Unit }
    override fun warn(message: String) = entries.add(LogEntry("WARN", message)).let { Unit }
    override fun error(message: String, cause: Throwable?) =
        entries.add(LogEntry("ERROR", message)).let { Unit }
}

class MavenTransferListenerTest :
    FunSpec({
        fun session() = RepositorySystemSupplier()
            .get()
            .createSessionBuilder()
            .withLocalRepositories(
                LocalRepository(Files.createTempDirectory("aether-test-"))
            )
            .build()

        fun resource(
            repoId: String = "central",
            repoUrl: String = "https://repo.maven.apache.org/maven2/",
            resourceName: String = "org/openrewrite/rewrite-core/8.75.3/rewrite-core-8.75.3.jar",
            contentLength: Long = 0L
        ): TransferResource =
            TransferResource(repoId, repoUrl, resourceName, null as java.nio.file.Path?, null, null)
                .setContentLength(contentLength)

        fun startedEvent(resource: TransferResource): TransferEvent =
            TransferEvent.Builder(session(), resource)
                .setType(TransferEvent.EventType.STARTED)
                .setRequestType(TransferEvent.RequestType.GET)
                .build()

        fun succeededEvent(resource: TransferResource, bytes: Long): TransferEvent =
            TransferEvent.Builder(session(), resource)
                .setType(TransferEvent.EventType.SUCCEEDED)
                .setRequestType(TransferEvent.RequestType.GET)
                .setTransferredBytes(bytes)
                .build()

        fun failedEvent(resource: TransferResource, cause: Exception): TransferEvent =
            TransferEvent.Builder(session(), resource)
                .setType(TransferEvent.EventType.FAILED)
                .setRequestType(TransferEvent.RequestType.GET)
                .setException(cause)
                .build()

        // ─── transferStarted ──────────────────────────────────────────────────────

        test("transferStarted logs Downloading from repoId and full URL at INFO") {
            val log = CapturingLogger()
            val r = resource()
            MavenTransferListener(log).transferStarted(startedEvent(r))

            val messages = log.entries.map { it.message }
            assertTrue(
                messages.any { msg ->
                    msg.startsWith("Downloading from central:") &&
                        msg.contains("https://repo.maven.apache.org/maven2/") &&
                        msg.contains("rewrite-core-8.75.3.jar")
                },
                "Expected 'Downloading from central: ...' log; got: $messages"
            )
        }

        test("transferStarted uses repoId from resource") {
            val log = CapturingLogger()
            val r = resource(repoId = "extra-0", repoUrl = "https://private.example.com/maven2/")
            MavenTransferListener(log).transferStarted(startedEvent(r))

            val messages = log.entries.map { it.message }
            assertTrue(
                messages.any { it.startsWith("Downloading from extra-0:") },
                "Expected 'Downloading from extra-0:' log; got: $messages"
            )
        }

        test("transferStarted is logged at INFO level") {
            val log = CapturingLogger()
            MavenTransferListener(log).transferStarted(startedEvent(resource()))

            val levels = log.entries.map { it.level }
            assertTrue("INFO" in levels, "Expected INFO level; got: $levels")
        }

        // ─── transferSucceeded ────────────────────────────────────────────────────

        test("transferSucceeded logs Downloaded from repoId and full URL at INFO") {
            val log = CapturingLogger()
            val r = resource(contentLength = 5_120)
            MavenTransferListener(log).transferSucceeded(succeededEvent(r, bytes = 5_120))

            val messages = log.entries.map { it.message }
            assertTrue(
                messages.any { msg ->
                    msg.startsWith("Downloaded from central:") &&
                        msg.contains("rewrite-core-8.75.3.jar")
                },
                "Expected 'Downloaded from central: ...' log; got: $messages"
            )
        }

        test("transferSucceeded includes file size in parentheses") {
            val log = CapturingLogger()
            val r = resource(contentLength = 2_048)
            MavenTransferListener(log).transferSucceeded(succeededEvent(r, bytes = 2_048))

            val messages = log.entries.map { it.message }
            assertTrue(
                messages.any { it.contains("(") && it.contains("kB") },
                "Expected size in parentheses like '(2 kB at ...)'; got: $messages"
            )
        }

        test("transferSucceeded is logged at INFO level") {
            val log = CapturingLogger()
            val r = resource(contentLength = 1_000)
            MavenTransferListener(log).transferSucceeded(succeededEvent(r, bytes = 1_000))

            val levels = log.entries.map { it.level }
            assertTrue("INFO" in levels, "Expected INFO level; got: $levels")
        }

        // ─── transferFailed ───────────────────────────────────────────────────────

        test("transferFailed logs at WARN level") {
            val log = CapturingLogger()
            val r = resource()
            MavenTransferListener(log).transferFailed(
                failedEvent(r, cause = Exception("connection refused"))
            )

            val levels = log.entries.map { it.level }
            assertTrue("WARN" in levels, "Expected WARN level; got: $levels")
        }

        test("transferFailed log message contains resource URL and error") {
            val log = CapturingLogger()
            val r = resource()
            MavenTransferListener(log).transferFailed(
                failedEvent(r, cause = Exception("connection refused"))
            )

            val messages = log.entries.map { it.message }
            assertTrue(
                messages.any { msg ->
                    msg.contains("rewrite-core-8.75.3.jar") && msg.contains("connection refused")
                },
                "Expected failed message with URL and error; got: $messages"
            )
        }

        test("transferFailed logs ArtifactNotFoundException at DEBUG not WARN") {
            val log = CapturingLogger()
            val r = resource()
            val artifact = DefaultArtifact("log4j:log4j:1.2.17.redhat-00008")
            MavenTransferListener(log).transferFailed(
                failedEvent(r, cause = ArtifactNotFoundException(artifact, "not found in central"))
            )

            val levels = log.entries.map { it.level }
            assertTrue("DEBUG" in levels, "Expected DEBUG level; got: $levels")
            assertFalse("WARN" in levels, "Expected no WARN level; got: $levels")
        }

        test("transferFailed logs MetadataNotFoundException at DEBUG not WARN") {
            val log = CapturingLogger()
            val r = resource()
            val metadata =
                DefaultMetadata(
                    "org.springframework.osgi",
                    "log4j.osgi",
                    "1.2.15-SNAPSHOT",
                    "maven-metadata.xml",
                    Metadata.Nature.RELEASE_OR_SNAPSHOT
                )
            MavenTransferListener(log).transferFailed(
                failedEvent(r, cause = MetadataNotFoundException(metadata, null, "not found"))
            )

            val levels = log.entries.map { it.level }
            assertTrue("DEBUG" in levels, "Expected DEBUG level; got: $levels")
            assertFalse("WARN" in levels, "Expected no WARN level; got: $levels")
        }
    })
