package io.github.skhokhlov.rewriterunner

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.core.spec.style.FunSpec
import java.nio.file.Files
import kotlin.test.assertTrue
import org.eclipse.aether.repository.LocalRepository
import org.eclipse.aether.supplier.RepositorySystemSupplier
import org.eclipse.aether.transfer.TransferEvent
import org.eclipse.aether.transfer.TransferResource
import org.slf4j.LoggerFactory

class MavenTransferListenerTest :
    FunSpec({
        lateinit var appender: ListAppender<ILoggingEvent>
        lateinit var logger: Logger

        beforeEach {
            appender = ListAppender<ILoggingEvent>().also { it.start() }
            logger =
                LoggerFactory.getLogger(MavenTransferListener::class.java) as Logger
            logger.level = Level.DEBUG
            logger.addAppender(appender)
        }

        afterEach { logger.detachAppender(appender) }

        fun session() =
            RepositorySystemSupplier()
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
            contentLength: Long = 0L,
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
            val r = resource()
            MavenTransferListener().transferStarted(startedEvent(r))

            val messages = appender.list.map { it.formattedMessage }
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
            val r = resource(repoId = "extra-0", repoUrl = "https://private.example.com/maven2/")
            MavenTransferListener().transferStarted(startedEvent(r))

            val messages = appender.list.map { it.formattedMessage }
            assertTrue(
                messages.any { it.startsWith("Downloading from extra-0:") },
                "Expected 'Downloading from extra-0:' log; got: $messages"
            )
        }

        test("transferStarted is logged at INFO level") {
            MavenTransferListener().transferStarted(startedEvent(resource()))

            val levels = appender.list.map { it.level }
            assertTrue(Level.INFO in levels, "Expected INFO level; got: $levels")
        }

        // ─── transferSucceeded ────────────────────────────────────────────────────

        test("transferSucceeded logs Downloaded from repoId and full URL at INFO") {
            val r = resource(contentLength = 5_120)
            MavenTransferListener().transferSucceeded(succeededEvent(r, bytes = 5_120))

            val messages = appender.list.map { it.formattedMessage }
            assertTrue(
                messages.any { msg ->
                    msg.startsWith("Downloaded from central:") &&
                        msg.contains("rewrite-core-8.75.3.jar")
                },
                "Expected 'Downloaded from central: ...' log; got: $messages"
            )
        }

        test("transferSucceeded includes file size in parentheses") {
            val r = resource(contentLength = 2_048)
            MavenTransferListener().transferSucceeded(succeededEvent(r, bytes = 2_048))

            val messages = appender.list.map { it.formattedMessage }
            assertTrue(
                messages.any { it.contains("(") && it.contains("kB") },
                "Expected size in parentheses like '(2 kB at ...)'; got: $messages"
            )
        }

        test("transferSucceeded is logged at INFO level") {
            val r = resource(contentLength = 1_000)
            MavenTransferListener().transferSucceeded(succeededEvent(r, bytes = 1_000))

            val levels = appender.list.map { it.level }
            assertTrue(Level.INFO in levels, "Expected INFO level; got: $levels")
        }

        // ─── transferFailed ───────────────────────────────────────────────────────

        test("transferFailed logs at WARN level") {
            val r = resource()
            MavenTransferListener().transferFailed(
                failedEvent(r, cause = Exception("connection refused"))
            )

            val levels = appender.list.map { it.level }
            assertTrue(Level.WARN in levels, "Expected WARN level; got: $levels")
        }

        test("transferFailed log message contains resource URL and error") {
            val r = resource()
            MavenTransferListener().transferFailed(
                failedEvent(r, cause = Exception("connection refused"))
            )

            val messages = appender.list.map { it.formattedMessage }
            assertTrue(
                messages.any { msg ->
                    msg.contains("rewrite-core-8.75.3.jar") && msg.contains("connection refused")
                },
                "Expected failed message with URL and error; got: $messages"
            )
        }
    })
