package io.github.skhokhlov.rewriterunner.lst.utils

import io.github.skhokhlov.rewriterunner.RunnerLogger
import io.github.skhokhlov.rewriterunner.config.DurationParser
import io.github.skhokhlov.rewriterunner.config.ToolConfigDefaults
import java.io.IOException
import java.io.InputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists

internal typealias ProcessRunner = (
    workDir: Path,
    command: List<String>,
    captureStdout: StringBuilder?,
    timeout: Duration,
    timeoutName: String,
    logger: RunnerLogger
) -> Int?

internal enum class BuildToolKind {
    MAVEN,
    GRADLE
}

/** A directory to invoke a build tool in, with the tool to use there. */
internal data class BuildUnit(val dir: Path, val tool: BuildToolKind)

/**
 * Runs an external process in [workDir] and waits up to [timeout] for it to finish.
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
    timeout: Duration = ToolConfigDefaults.SUBPROCESS_RUN_TIMEOUT,
    timeoutName: String = "processTimeout",
    logger: RunnerLogger
): Int? {
    DurationParser.requirePositive(timeout, timeoutName)
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
        try {
            stream.bufferedReader().forEachLine {
                logger.debug("[$prefix $tag] $it")
                if (tag == "stdout") captureStdout?.append(it)?.append('\n')
            }
        } catch (e: IOException) {
            logger.debug("[$prefix $tag] stream closed: ${e.message}")
        }
    }, "process-drain-$tag").apply {
        isDaemon = true
        start()
    }
    val stdoutThread = drainStream(process.inputStream, "stdout")
    val stderrThread = drainStream(process.errorStream, "stderr")

    val timeoutMillis = timeout.toMillis().coerceAtLeast(1)
    val finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
    if (!finished) {
        process.destroyForcibly()
        closeProcessStreams(process)
        stdoutThread.join(TIMEOUT_DRAIN_JOIN_MILLIS)
        stderrThread.join(TIMEOUT_DRAIN_JOIN_MILLIS)
        logger.warn("Process ${command.first()} timed out after $timeout")
        return null
    }

    stdoutThread.join()
    stderrThread.join()

    return process.exitValue()
}

private const val TIMEOUT_DRAIN_JOIN_MILLIS = 100L

private fun closeProcessStreams(process: Process) {
    listOf(process.outputStream, process.inputStream, process.errorStream).forEach { stream ->
        try {
            stream.close()
        } catch (_: IOException) {
            // Best effort: the process may already have closed one or more streams.
        }
    }
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

/**
 * Discovers build-tool invocation roots under [dir].
 *
 * Root descriptors keep the historical single-root invocation for that tool. When a tool has no
 * root descriptor, discovery falls back to top-most subdirectory descriptors up to depth 3,
 * skipping [FileCollector.DEFAULT_EXCLUDED_DIRS] and pruning below the first build unit found.
 */
internal fun discoverBuildUnits(
    dir: Path,
    maxUnits: Int = 25,
    logger: RunnerLogger
): List<BuildUnit> {
    if (maxUnits <= 0) return emptyList()

    val units = mutableListOf<BuildUnit>()
    var capWarned = false

    fun addUnit(unit: BuildUnit): Boolean {
        if (units.size >= maxUnits) {
            if (!capWarned) {
                logger.warn(
                    "Discovered more than $maxUnits build unit(s) under $dir; " +
                        "using the first $maxUnits and leaving the rest to later stages"
                )
                capWarned = true
            }
            return false
        }
        units += unit
        return true
    }

    val hasRootMaven = dir.resolve("pom.xml").exists()
    val hasRootGradle = hasBuildGradle(dir)

    if (hasRootMaven && !addUnit(BuildUnit(dir, BuildToolKind.MAVEN))) return units
    if (hasRootGradle && !addUnit(BuildUnit(dir, BuildToolKind.GRADLE))) return units

    val discoverMavenSubdirs = !hasRootMaven
    val discoverGradleSubdirs = !hasRootGradle
    if (!discoverMavenSubdirs && !discoverGradleSubdirs) return units

    try {
        Files.walkFileTree(
            dir,
            emptySet(),
            BUILD_UNIT_DISCOVERY_DEPTH,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(
                    current: Path,
                    attrs: BasicFileAttributes
                ): FileVisitResult {
                    if (current != dir &&
                        current.fileName?.toString() in FileCollector.DEFAULT_EXCLUDED_DIRS
                    ) {
                        return FileVisitResult.SKIP_SUBTREE
                    }

                    if (current == dir) return FileVisitResult.CONTINUE

                    var foundUnit = false
                    if (discoverMavenSubdirs && current.resolve("pom.xml").exists()) {
                        if (!addUnit(BuildUnit(current, BuildToolKind.MAVEN))) {
                            return FileVisitResult.TERMINATE
                        }
                        foundUnit = true
                    }
                    if (discoverGradleSubdirs && hasBuildGradle(current)) {
                        if (!addUnit(BuildUnit(current, BuildToolKind.GRADLE))) {
                            return FileVisitResult.TERMINATE
                        }
                        foundUnit = true
                    }
                    return if (foundUnit) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE
                }
            }
        )
    } catch (e: Exception) {
        logger.warn("Could not discover build units under $dir: ${e.message}")
    }

    return units
}

// walkFileTree visits the root at maxDepth=1, so 4 reaches build directories 3 levels below root.
private const val BUILD_UNIT_DISCOVERY_DEPTH = 4

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
 * Returns the Maven executable for [projectDir], falling back to a wrapper at [rootDir] when a
 * root-less build unit does not carry its own wrapper.
 */
internal fun resolveMavenCommand(projectDir: Path, rootDir: Path): String = when {
    projectDir.resolve("mvnw").exists() -> "./mvnw"
    projectDir.resolve("mvnw.cmd").exists() -> "mvnw.cmd"
    rootDir.resolve("mvnw").exists() -> rootDir.resolve("mvnw").toAbsolutePath().toString()
    rootDir.resolve("mvnw.cmd").exists() -> rootDir.resolve("mvnw.cmd").toAbsolutePath().toString()
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

/**
 * Returns the Gradle executable for [projectDir], falling back to a wrapper at [rootDir] when a
 * root-less build unit does not carry its own wrapper.
 */
internal fun resolveGradleCommand(projectDir: Path, rootDir: Path): String = when {
    projectDir.resolve("gradlew").exists() -> "./gradlew"

    projectDir.resolve("gradlew.bat").exists() -> "gradlew.bat"

    rootDir.resolve("gradlew").exists() -> rootDir.resolve("gradlew").toAbsolutePath().toString()

    rootDir.resolve("gradlew.bat").exists() ->
        rootDir.resolve("gradlew.bat").toAbsolutePath().toString()

    else -> "gradle"
}
