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
 * When [captureStdout] is null both stdout and stderr are drained on background threads and
 * logged at DEBUG level (visible with `--debug`), preventing OS pipe-buffer deadlock.
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
    }
    // When captureStdout is null we leave both streams as pipes so we can drain them
    // (either to logger.debug or /dev/null via background threads) — prevents OS
    // pipe-buffer deadlock (~64 KB limit) that occurred with redirectErrorStream(true).

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
    } else {
        // Drain stdout and stderr on background threads to prevent pipe-buffer deadlock.
        // Log each line at DEBUG so Maven/Gradle output is visible with --debug.
        val prefix = command.first().substringAfterLast('/')
        fun drainStream(stream: java.io.InputStream, tag: String) = Thread(null, {
            stream.bufferedReader().forEachLine { logger.debug("[$prefix $tag] $it") }
        }, "process-drain-$tag").apply {
            isDaemon = true
            start()
        }
        val stdoutThread = drainStream(process.inputStream, "stdout")
        val stderrThread = drainStream(process.errorStream, "stderr")
        stdoutThread.join()
        stderrThread.join()
    }

    val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
    if (!finished) {
        process.destroyForcibly()
        logger.warn("Process ${command.first()} timed out after ${timeoutSeconds}s")
        return null
    }

    return process.exitValue()
}

/** Returns `true` when [dir] contains a `build.gradle` or `build.gradle.kts` or `settings.gradle` or `settings.gradle.kts` file. */
internal fun hasBuildGradle(dir: Path): Boolean =
    dir.resolve("build.gradle").exists() || dir.resolve("build.gradle.kts").exists() ||
        dir.resolve("settings.gradle").exists() ||
        dir.resolve("settings.gradle.kts").exists()

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
