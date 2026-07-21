package io.github.skhokhlov.rewriterunner.execution

import io.github.skhokhlov.rewriterunner.ExecutionMode
import io.github.skhokhlov.rewriterunner.ExecutorOutcome
import io.github.skhokhlov.rewriterunner.RewriteRunner
import io.github.skhokhlov.rewriterunner.WorkerCommand
import io.github.skhokhlov.rewriterunner.WorkerCommandFactory
import io.kotest.core.spec.style.FunSpec
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Exercises actual child JVM lifecycle and framing failures through the public builder seam. */
class ForkedWorkerProtocolLifecycleTest :
    FunSpec({
        var projectDir: Path = Path.of("")
        var cacheDir: Path = Path.of("")

        beforeEach {
            projectDir = Files.createTempDirectory("worker-protocol-project-")
            cacheDir = Files.createTempDirectory("worker-protocol-cache-")
            Files.writeString(projectDir.resolve("sample.txt"), "unchanged\n")
        }

        afterEach {
            projectDir.toFile().deleteRecursively()
            cacheDir.toFile().deleteRecursively()
        }

        test("an incompatible worker handshake is terminal and never retries work in process") {
            val requestDirectory = AtomicReference<Path?>()
            val failure = assertFailsWith<ForkedWorkerException> {
                runner(projectDir, cacheDir, fixtureCommand("incompatible", requestDirectory)).run()
            }

            assertEquals(ExecutorOutcome.PROTOCOL_FAILURE, failure.attempt.outcome)
            assertEquals("unchanged\n", Files.readString(projectDir.resolve("sample.txt")))
            assertFalse(Files.exists(assertNotNull(requestDirectory.get())))
        }

        test(
            "worker timeout terminates its complete Java process tree and cleans transport files"
        ) {
            val pids = Files.createTempFile("worker-protocol-pids-", ".txt")
            val requestDirectory = AtomicReference<Path?>()
            try {
                val failure = assertFailsWith<ForkedWorkerException> {
                    runner(
                        projectDir,
                        cacheDir,
                        fixtureCommand("tree", requestDirectory, pids),
                        timeout = Duration.ofMillis(750)
                    ).run()
                }

                assertEquals(ExecutorOutcome.TIMEOUT, failure.attempt.outcome)
                val recordedPids = waitForPids(pids)
                assertTrue(recordedPids.size >= 2, "Fixture did not record parent and child PIDs")
                assertTrue(
                    awaitCondition { recordedPids.none(::isAlive) },
                    "Timed-out worker descendants remained alive: $recordedPids"
                )
                assertFalse(Files.exists(assertNotNull(requestDirectory.get())))
            } finally {
                Files.deleteIfExists(pids)
            }
        }

        test("a relative cache directory is made absolute before it is sent to the worker") {
            val relativeCacheDir = Path.of("forked-worker-cache-${UUID.randomUUID()}")
            val serializedCacheDir = AtomicReference<String>()
            val command =
                WorkerCommandFactory { request ->
                    val envelope =
                        WorkerJson.read(
                            Files.readString(request.requestFile),
                            WorkerRequestEnvelope::class.java
                        )
                    serializedCacheDir.set(envelope.request.cacheDir)
                    fixtureCommand("incompatible", AtomicReference()).create(request)
                }

            assertFailsWith<ForkedWorkerException> {
                runner(projectDir, relativeCacheDir, command).run()
            }

            assertEquals(
                relativeCacheDir.toAbsolutePath().normalize().toString(),
                serializedCacheDir.get()
            )
        }
    })

private fun runner(
    projectDir: Path,
    cacheDir: Path,
    factory: WorkerCommandFactory,
    timeout: Duration? = null
): RewriteRunner = RewriteRunner.builder()
    .projectDir(projectDir)
    .activeRecipe("com.example.NeverRuns")
    .cacheDir(cacheDir)
    .skipPluginRun(true)
    .executionMode(ExecutionMode.FORKED)
    .workerCommandFactory(factory)
    .apply { timeout?.let(::lstWorkerTimeout) }
    .build()

private fun fixtureCommand(
    mode: String,
    requestDirectory: AtomicReference<Path?>,
    pids: Path? = null
): WorkerCommandFactory = WorkerCommandFactory { request ->
    requestDirectory.set(request.requestDirectory)
    WorkerCommand(
        buildList {
            add(request.javaExecutable.toString())
            addAll(request.jvmArgs)
            add("-cp")
            add(request.classpath)
            add(ForkedWorkerFixture::class.java.name)
            add(mode)
            pids?.let { add(it.toString()) }
        }
    )
}

private fun waitForPids(file: Path): List<Long> {
    val available = awaitCondition { Files.size(file) > 0 }
    assertTrue(available, "Fixture did not write process IDs")
    return Files.readAllLines(file).mapNotNull(String::toLongOrNull)
}

private fun awaitCondition(condition: () -> Boolean): Boolean {
    val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
    while (System.nanoTime() < deadline) {
        if (condition()) return true
        Thread.sleep(10)
    }
    return condition()
}

private fun isAlive(pid: Long): Boolean =
    ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)

/** Java fixture main used only to prove lifecycle behavior across a real process boundary. */
object ForkedWorkerFixture {
    @JvmStatic
    fun main(args: Array<String>) {
        when (args.firstOrNull()) {
            "incompatible" -> {
                emitHandshake(protocolVersion = WORKER_PROTOCOL_VERSION + 1)
                Thread.sleep(Duration.ofMinutes(1).toMillis())
            }

            "tree" -> {
                val pidFile = Path.of(requireNotNull(args.getOrNull(1)))
                val java =
                    Path.of(
                        System.getProperty("java.home"),
                        "bin",
                        if (System.getProperty("os.name").contains("win", ignoreCase = true)) {
                            "java.exe"
                        } else {
                            "java"
                        }
                    )
                val child =
                    ProcessBuilder(
                        java.toString(),
                        "-cp",
                        System.getProperty("java.class.path"),
                        ForkedWorkerFixture::class.java.name,
                        "child"
                    ).start()
                Files.writeString(
                    pidFile,
                    "${ProcessHandle.current().pid()}\n${child.pid()}\n"
                )
                emitHandshake()
                child.waitFor()
            }

            "child" -> Thread.sleep(Duration.ofMinutes(1).toMillis())

            else -> error("Unknown fixture mode ${args.firstOrNull()}")
        }
    }

    private fun emitHandshake(protocolVersion: Int = WORKER_PROTOCOL_VERSION) {
        println(
            WORKER_EVENT_PREFIX +
                WorkerJson.write(
                    WorkerEvent(
                        type = "handshake",
                        payload =
                            WorkerHandshake(
                                protocolVersion = protocolVersion,
                                processId = ProcessHandle.current().pid(),
                                javaVersion = System.getProperty("java.version"),
                                maximumHeapBytes = Runtime.getRuntime().maxMemory(),
                                scopes = WorkerScope.entries.map { it.name }
                            )
                    )
                )
        )
        System.out.flush()
    }
}
