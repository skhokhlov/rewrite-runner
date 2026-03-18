package io.github.skhokhlov.rewriterunner.lst.utils

import io.github.skhokhlov.rewriterunner.RunnerLogger
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists

/**
 * Runs an external process in [workDir] and waits up to [timeoutSeconds] for it to finish.
 *
 * Both stdout and stderr are always drained on background threads to prevent OS pipe-buffer
 * deadlock (~64 KB limit). Each line is logged at DEBUG level (visible with `--debug`).
 * When [captureStdout] is non-null, stdout lines are also appended to it (for classpath
 * extraction); stderr is still drained and logged.
 *
 * @return The process exit code, or `null` if the process could not be started or timed out.
 */
internal fun runProcess(
    workDir: Path,
    command: List<String>,
    captureStdout: StringBuilder? = null,
    timeoutSeconds: Long = 120,
    logger: RunnerLogger
): Int? {
    val pb = ProcessBuilder(command).directory(workDir.toFile())

    val process =
        try {
            pb.start()
        } catch (e: Exception) {
            logger.warn("Failed to start process ${command.first()}: ${e.message}")
            return null
        }

    // Always drain both streams on background threads to prevent pipe-buffer deadlock.
    // Log each line at DEBUG so Maven/Gradle output is visible with --debug.
    val prefix = command.first().substringAfterLast('/')
    fun drainStream(stream: InputStream, tag: String) = Thread(null, {
        stream.bufferedReader().forEachLine {
            logger.debug("[$prefix $tag] $it")
            if (tag == "stdout") captureStdout?.append(it)?.append('\n')
        }
    }, "process-drain-$tag").apply {
        isDaemon = true
        start()
    }
    val stdoutThread = drainStream(process.inputStream, "stdout")
    val stderrThread = drainStream(process.errorStream, "stderr")
    stdoutThread.join()
    stderrThread.join()

    val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
    if (!finished) {
        process.destroyForcibly()
        logger.warn("Process ${command.first()} timed out after ${timeoutSeconds}s")
        return null
    }

    return process.exitValue()
}

/** Returns the `build.gradle.kts` or `build.gradle` file in [dir], or null if neither exists. */
internal fun findBuildFile(dir: Path): Path? =
    dir.resolve("build.gradle.kts").takeIf { it.exists() }
        ?: dir.resolve("build.gradle").takeIf { it.exists() }

/** Returns the `settings.gradle.kts` or `settings.gradle` file in [dir], or null if neither exists. */
internal fun findSettingsFile(dir: Path): Path? =
    dir.resolve("settings.gradle.kts").takeIf { it.exists() }
        ?: dir.resolve("settings.gradle").takeIf { it.exists() }

/** Returns `true` when [dir] contains a `build.gradle` or `build.gradle.kts` or `settings.gradle` or `settings.gradle.kts` file. */
internal fun hasBuildGradle(dir: Path): Boolean =
    dir.resolve("build.gradle").exists() || dir.resolve("build.gradle.kts").exists() ||
        dir.resolve("settings.gradle").exists() ||
        dir.resolve("settings.gradle.kts").exists()

/** Returns `true` when any subdirectory of [dir] (up to depth 3) contains a `pom.xml`. */
internal fun hasMavenPomInSubdir(dir: Path): Boolean = try {
    Files.walk(dir, 3).use { stream ->
        stream.anyMatch { path ->
            path.parent != dir && path.fileName?.toString() == "pom.xml"
        }
    }
} catch (_: Exception) {
    false
}

/**
 * Returns `true` when any subdirectory of [dir] (up to depth 3) contains a
 * `build.gradle` or `build.gradle.kts` file.
 */
internal fun hasGradleBuildInSubdir(dir: Path): Boolean = try {
    Files.walk(dir, 3).use { stream ->
        stream.anyMatch { path ->
            val name = path.fileName?.toString() ?: return@anyMatch false
            path.parent != dir && (name == "build.gradle" || name == "build.gradle.kts")
        }
    }
} catch (_: Exception) {
    false
}

/**
 * Returns the Maven executable to use for [projectDir]:
 * - `./mvnw` if a Unix wrapper is present
 * - `mvnw.cmd` if a Windows wrapper is present
 * - `mvn` as a fallback (must be on PATH)
 */
internal fun resolveMavenCommand(projectDir: Path): String = when {
    projectDir.resolve("mvnw").exists() -> "./mvnw"
    projectDir.resolve("mvnw.cmd").exists() -> "mvnw.cmd"
    else -> "mvn"
}

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
