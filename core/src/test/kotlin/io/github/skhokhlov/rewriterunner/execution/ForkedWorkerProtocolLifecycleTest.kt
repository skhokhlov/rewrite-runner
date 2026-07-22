package io.github.skhokhlov.rewriterunner.execution

import io.github.skhokhlov.rewriterunner.ExecutionMode
import io.github.skhokhlov.rewriterunner.ExecutorOutcome
import io.github.skhokhlov.rewriterunner.NoOpRunnerLogger
import io.github.skhokhlov.rewriterunner.RewriteRunner
import io.github.skhokhlov.rewriterunner.RunResult
import io.github.skhokhlov.rewriterunner.RunnerLogger
import io.github.skhokhlov.rewriterunner.WorkerCommand
import io.github.skhokhlov.rewriterunner.WorkerCommandFactory
import io.github.skhokhlov.rewriterunner.WorkerCommandRequest
import io.kotest.core.spec.style.FunSpec
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
                        fixtureCommand("tree", requestDirectory) { listOf(pids.toString()) },
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

        test("a partial worker response is a terminal protocol failure") {
            val requestDirectory = AtomicReference<Path?>()
            val failure = assertFailsWith<ForkedWorkerException> {
                runner(
                    projectDir,
                    cacheDir,
                    fixtureCommand("partial-response", requestDirectory) { request ->
                        listOf(request.responseFile.toString())
                    }
                ).run()
            }

            assertEquals(ExecutorOutcome.PROTOCOL_FAILURE, failure.attempt.outcome)
            assertEquals("unchanged\n", Files.readString(projectDir.resolve("sample.txt")))
            assertFalse(Files.exists(assertNotNull(requestDirectory.get())))
        }

        test("a missing worker response is a terminal protocol failure") {
            val requestDirectory = AtomicReference<Path?>()
            val failure = assertFailsWith<ForkedWorkerException> {
                runner(
                    projectDir,
                    cacheDir,
                    fixtureCommand("missing-response", requestDirectory)
                ).run()
            }

            assertEquals(ExecutorOutcome.PROTOCOL_FAILURE, failure.attempt.outcome)
            assertEquals("unchanged\n", Files.readString(projectDir.resolve("sample.txt")))
            assertFalse(Files.exists(assertNotNull(requestDirectory.get())))
        }

        test("incidental worker stdout and stderr remain debug output around framed events") {
            val requestDirectory = AtomicReference<Path?>()
            val logger = CapturingWorkerLogger()

            val result =
                runner(
                    projectDir,
                    cacheDir,
                    fixtureCommand("incidental-output", requestDirectory) { request ->
                        listOf(request.requestFile.toString(), request.responseFile.toString())
                    },
                    logger = logger
                ).run()

            assertEquals(
                ExecutorOutcome.NO_CHANGES,
                result.executionDiagnostics.executorAttempts.single().outcome
            )
            assertTrue(logger.debugMessages.any { it.contains("before framed event") })
            assertTrue(logger.debugMessages.any { it.contains("after framed event") })
            assertTrue(logger.debugMessages.any { it.contains("incidental stderr") })
            assertFalse(Files.exists(assertNotNull(requestDirectory.get())))
        }

        test("a real worker heap exhaustion is confirmed from its JVM output") {
            val requestDirectory = AtomicReference<Path?>()
            val failure = assertFailsWith<ForkedWorkerException> {
                runner(
                    projectDir,
                    cacheDir,
                    fixtureCommand("oom", requestDirectory),
                    timeout = Duration.ofSeconds(10),
                    workerJvmArgs = listOf("-Xmx32m")
                ).run()
            }

            assertEquals(ExecutorOutcome.CONFIRMED_HEAP_OOM, failure.attempt.outcome)
            assertEquals("unchanged\n", Files.readString(projectDir.resolve("sample.txt")))
            assertFalse(Files.exists(assertNotNull(requestDirectory.get())))
        }

        test("exit 137 without heap evidence remains only likely OOM") {
            val requestDirectory = AtomicReference<Path?>()
            val failure = assertFailsWith<ForkedWorkerException> {
                runner(projectDir, cacheDir, fixtureCommand("exit-137", requestDirectory)).run()
            }

            assertEquals(ExecutorOutcome.LIKELY_OOM, failure.attempt.outcome)
            assertEquals(137, failure.attempt.exitCode)
            assertFalse(failure.attempt.message.orEmpty().contains("OutOfMemoryError"))
            assertFalse(Files.exists(assertNotNull(requestDirectory.get())))
        }

        test("an injected worker start failure is terminal and cleans transport files") {
            val requestDirectory = AtomicReference<Path?>()
            val failure = assertFailsWith<ForkedWorkerException> {
                runner(
                    projectDir,
                    cacheDir,
                    WorkerCommandFactory { request ->
                        requestDirectory.set(request.requestDirectory)
                        WorkerCommand(
                            listOf(request.requestDirectory.resolve("missing-worker").toString())
                        )
                    }
                ).run()
            }

            assertEquals(ExecutorOutcome.START_FAILURE, failure.attempt.outcome)
            assertEquals("unchanged\n", Files.readString(projectDir.resolve("sample.txt")))
            assertFalse(Files.exists(assertNotNull(requestDirectory.get())))
        }

        test("two concurrent runs do not overlap a blocking worker") {
            val fixtureDir = Files.createTempDirectory("worker-concurrency-")
            val firstStarted = fixtureDir.resolve("first-started")
            val secondStarted = fixtureDir.resolve("second-started")
            val releaseFile = fixtureDir.resolve("release")
            val firstRequestDirectory = AtomicReference<Path?>()
            val secondRequestDirectory = AtomicReference<Path?>()
            val secondRunEntered = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val first =
                    executor.submit<RunResult> {
                        runner(
                            projectDir,
                            cacheDir,
                            blockingFixtureCommand(firstStarted, releaseFile, firstRequestDirectory)
                        ).run()
                    }
                assertTrue(
                    awaitCondition { Files.exists(firstStarted) },
                    "first worker did not begin blocking"
                )

                val second =
                    executor.submit<RunResult> {
                        secondRunEntered.countDown()
                        runner(
                            projectDir,
                            cacheDir,
                            blockingFixtureCommand(
                                secondStarted,
                                releaseFile,
                                secondRequestDirectory
                            )
                        ).run()
                    }
                assertTrue(secondRunEntered.await(5, TimeUnit.SECONDS))
                assertFalse(
                    awaitCondition(Duration.ofMillis(750)) { Files.exists(secondStarted) },
                    "second worker overlapped the first; removing executionGate must make this fail"
                )

                Files.writeString(releaseFile, "release")
                assertTrue(
                    awaitCondition { Files.exists(secondStarted) },
                    "second worker did not start after the first released the gate"
                )
                assertEquals(
                    ExecutorOutcome.NO_CHANGES,
                    first.get(5, TimeUnit.SECONDS)
                        .executionDiagnostics.executorAttempts.single().outcome
                )
                assertEquals(
                    ExecutorOutcome.NO_CHANGES,
                    second.get(5, TimeUnit.SECONDS)
                        .executionDiagnostics.executorAttempts.single().outcome
                )
                assertFalse(Files.exists(assertNotNull(firstRequestDirectory.get())))
                assertFalse(Files.exists(assertNotNull(secondRequestDirectory.get())))
            } finally {
                if (!Files.exists(releaseFile)) Files.writeString(releaseFile, "release")
                executor.shutdownNow()
                executor.awaitTermination(5, TimeUnit.SECONDS)
                fixtureDir.toFile().deleteRecursively()
            }
        }
    })

private fun runner(
    projectDir: Path,
    cacheDir: Path,
    factory: WorkerCommandFactory,
    timeout: Duration? = null,
    workerJvmArgs: List<String> = emptyList(),
    logger: RunnerLogger = NoOpRunnerLogger
): RewriteRunner = RewriteRunner.builder()
    .projectDir(projectDir)
    .activeRecipe("com.example.NeverRuns")
    .cacheDir(cacheDir)
    .skipPluginRun(true)
    .executionMode(ExecutionMode.FORKED)
    .workerCommandFactory(factory)
    .logger(logger)
    .apply {
        timeout?.let(::lstWorkerTimeout)
        if (workerJvmArgs.isNotEmpty()) lstWorkerJvmArgs(workerJvmArgs)
    }
    .build()

private fun fixtureCommand(
    mode: String,
    requestDirectory: AtomicReference<Path?>,
    extraArguments: (WorkerCommandRequest) -> List<String> = { emptyList() }
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
            addAll(extraArguments(request))
        }
    )
}

private fun blockingFixtureCommand(
    startedFile: Path,
    releaseFile: Path,
    requestDirectory: AtomicReference<Path?>
): WorkerCommandFactory = fixtureCommand("block", requestDirectory) { request ->
    listOf(
        request.requestFile.toString(),
        request.responseFile.toString(),
        startedFile.toString(),
        releaseFile.toString()
    )
}

private fun waitForPids(file: Path): List<Long> {
    val available = awaitCondition { Files.size(file) > 0 }
    assertTrue(available, "Fixture did not write process IDs")
    return Files.readAllLines(file).mapNotNull(String::toLongOrNull)
}

private fun awaitCondition(
    timeout: Duration = Duration.ofSeconds(5),
    condition: () -> Boolean
): Boolean {
    val deadline = System.nanoTime() + timeout.toNanos()
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

            "partial-response" -> {
                val responseFile = Path.of(requireNotNull(args.getOrNull(1)))
                emitHandshake()
                Files.writeString(responseFile, "{")
            }

            "missing-response" -> emitHandshake()

            "incidental-output" -> {
                val requestFile = Path.of(requireNotNull(args.getOrNull(1)))
                val responseFile = Path.of(requireNotNull(args.getOrNull(2)))
                println("incidental stdout before framed event")
                System.err.println("incidental stderr")
                emitHandshake()
                println("incidental stdout after framed event")
                System.out.flush()
                writeSuccessfulResponse(requestFile, responseFile)
            }

            "oom" -> {
                emitHandshake()
                exhaustHeap()
            }

            "exit-137" -> {
                emitHandshake()
                Runtime.getRuntime().halt(137)
            }

            "block" -> {
                val requestFile = Path.of(requireNotNull(args.getOrNull(1)))
                val responseFile = Path.of(requireNotNull(args.getOrNull(2)))
                val startedFile = Path.of(requireNotNull(args.getOrNull(3)))
                val releaseFile = Path.of(requireNotNull(args.getOrNull(4)))
                emitHandshake()
                Files.writeString(startedFile, ProcessHandle.current().pid().toString())
                while (!Files.exists(releaseFile)) Thread.sleep(10)
                writeSuccessfulResponse(requestFile, responseFile)
            }

            else -> error("Unknown fixture mode ${args.firstOrNull()}")
        }
    }

    private fun exhaustHeap() {
        val chunks = ArrayList<ByteArray>()
        while (true) chunks.add(ByteArray(1024 * 1024))
    }

    private fun writeSuccessfulResponse(requestFile: Path, responseFile: Path) {
        val request = WorkerJson.read(
            Files.readString(requestFile),
            WorkerRequestEnvelope::class.java
        )
        val response =
            WorkerResponseEnvelope(
                requestId = request.request.requestId,
                success = true,
                outcome =
                    WorkerOutcomePayload(
                        rawDiffs = emptyMap(),
                        changedFiles = emptyList(),
                        diagnostics =
                            WorkerDiagnosticsPayload(
                                stageUsed = null,
                                resolvedJarCount = 0,
                                parseFailures = emptyList(),
                                parsedFileCount = 0,
                                estimatedTimeSavedMillis = null,
                                writeSuccesses = emptyList(),
                                writeFailures = emptyList()
                            )
                    )
            )
        Files.writeString(responseFile, WorkerJson.write(response))
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

private class CapturingWorkerLogger : RunnerLogger by NoOpRunnerLogger {
    val debugMessages = ConcurrentLinkedQueue<String>()

    override fun debug(message: String) {
        debugMessages.add(message)
    }
}
