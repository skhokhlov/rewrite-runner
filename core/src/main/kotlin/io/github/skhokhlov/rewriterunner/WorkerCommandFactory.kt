package io.github.skhokhlov.rewriterunner

import java.nio.file.Path

/**
 * Advanced packaging seam for constructing the LST worker process invocation.
 *
 * A factory receives structured values and returns process tokens plus environment changes;
 * it never receives or returns a shell command. Most callers should use the default launch
 * behaviour, which starts the current Java runtime with the current classpath.
 */
fun interface WorkerCommandFactory {
    fun create(request: WorkerCommandRequest): WorkerCommand
}

/** Structured inputs for [WorkerCommandFactory]. */
data class WorkerCommandRequest(
    val javaExecutable: Path,
    val classpath: String,
    val mainClass: String,
    val requestDirectory: Path,
    val requestFile: Path,
    val responseFile: Path,
    val jvmArgs: List<String>
)

/** A tokenized worker command and a narrow environment delta. */
data class WorkerCommand(
    val command: List<String>,
    val environment: Map<String, String> = emptyMap()
) {
    init {
        require(command.isNotEmpty()) { "worker command must contain an executable" }
    }
}
