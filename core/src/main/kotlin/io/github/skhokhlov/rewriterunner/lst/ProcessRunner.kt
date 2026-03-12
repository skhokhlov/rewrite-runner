package io.github.skhokhlov.rewriterunner.lst

import io.github.skhokhlov.rewriterunner.NoOpRunnerLogger
import io.github.skhokhlov.rewriterunner.RunnerLogger
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists

/**
 * Runs an external process in [workDir] and waits up to [timeoutSeconds] for it to finish.
 *
 * When [captureStdout] is non-null the child's stdout is appended to it; stderr is discarded.
 * When [captureStdout] is null both stdout and stderr are discarded so the child can always
 * write without blocking, regardless of how much it produces (prevents OS pipe-buffer deadlock).
 *
 * @return The process exit code, or `null` if the process could not be started or timed out.
 */
internal fun runProcess(
    workDir: Path,
    command: List<String>,
    captureStdout: StringBuilder? = null,
    timeoutSeconds: Long = 120,
    logger: RunnerLogger = NoOpRunnerLogger
): Int? {
    val pb = ProcessBuilder(command).directory(workDir.toFile())

    if (captureStdout != null) {
        // Capture stdout; discard stderr so it never fills and blocks the child.
        pb.redirectError(ProcessBuilder.Redirect.DISCARD)
    } else {
        // Output not needed — redirect both streams to DISCARD so the child process
        // can always write without blocking, regardless of how much it produces.
        // Previously redirectErrorStream(true) merged the streams but left the merged
        // pipe unread, causing a deadlock once output exceeded the OS pipe buffer (~64 KB).
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD)
        pb.redirectError(ProcessBuilder.Redirect.DISCARD)
    }

    val process =
        try {
            pb.start()
        } catch (e: Exception) {
            logger.warn("Failed to start process ${command.first()}: ${e.message}")
            return null
        }

    if (captureStdout != null) {
        // Read all stdout before waitFor so the stdout pipe never fills up.
        captureStdout.append(process.inputStream.bufferedReader().readText())
    }

    val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
    if (!finished) {
        process.destroyForcibly()
        logger.warn("Process ${command.first()} timed out after ${timeoutSeconds}s")
        return null
    }

    return process.exitValue()
}

/** Returns `true` when [dir] contains a `build.gradle` or `build.gradle.kts` file. */
internal fun hasBuildGradle(dir: Path): Boolean =
    dir.resolve("build.gradle").exists() || dir.resolve("build.gradle.kts").exists()

/**
 * Returns the Gradle executable to use for [projectDir]:
 * - `./gradlew` if a Unix wrapper is present
 * - `gradlew.bat` if a Windows wrapper is present
 * - `gradle` as a fallback (must be on PATH)
 */
internal fun resolveGradleCommand(projectDir: Path): String = when {
    projectDir.resolve("gradlew").exists() -> "./gradlew"
    projectDir.resolve("gradlew.bat").exists() -> "gradlew.bat"
    else -> "gradle"
}
