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
    Maven,
    Gradle,
    None
}

/** A directory to invoke a build tool in, with the tool to use there. */
internal data class BuildUnit(val dir: Path, val tool: BuildToolKind)

/** Build units plus whether discovery found more candidates than the caller will process. */
internal data class BuildUnitDiscoveryResult(val units: List<BuildUnit>, val truncated: Boolean)

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
 * Returns the single build-tool identity of [dir] for provenance/marker purposes.
 *
 * This verdict is intentionally exclusive and root-only. Classpath resolution remains
 * non-exclusive through [discoverBuildUnits], so projects with both build files still resolve both
 * tool classpaths while their provenance marker uses Gradle.
 */
internal fun detectBuildTool(dir: Path, logger: RunnerLogger): BuildToolKind {
    val hasGradle = hasBuildGradle(dir)
    val hasMaven = dir.resolve("pom.xml").exists()
    return when {
        hasGradle && hasMaven -> {
            logger.warn(
                "Both Gradle and Maven build files in $dir - " +
                    "treating as Gradle for provenance"
            )
            BuildToolKind.Gradle
        }

        hasGradle -> BuildToolKind.Gradle

        hasMaven -> BuildToolKind.Maven

        else -> BuildToolKind.None
    }
}

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
): List<BuildUnit> = discoverBuildUnitResult(dir, maxUnits, logger).units

internal fun discoverBuildUnitResult(
    dir: Path,
    maxUnits: Int = 25,
    logger: RunnerLogger
): BuildUnitDiscoveryResult {
    if (maxUnits <= 0) return BuildUnitDiscoveryResult(emptyList(), truncated = false)

    val candidates = mutableListOf<BuildUnit>()

    val hasRootMaven = dir.resolve("pom.xml").exists()
    val hasRootGradle = hasBuildGradle(dir)

    if (hasRootMaven) candidates += BuildUnit(dir, BuildToolKind.Maven)
    if (hasRootGradle) candidates += BuildUnit(dir, BuildToolKind.Gradle)

    val discoverMavenSubdirs = !hasRootMaven
    val discoverGradleSubdirs = !hasRootGradle
    if (!discoverMavenSubdirs && !discoverGradleSubdirs) {
        return capBuildUnits(candidates, dir, maxUnits, logger)
    }

    try {
        Files.walkFileTree(
            dir,
            emptySet(),
            BUILD_UNIT_DISCOVERY_WALK_MAX_DEPTH,
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
                        candidates += BuildUnit(current, BuildToolKind.Maven)
                        foundUnit = true
                    }
                    if (discoverGradleSubdirs && hasBuildGradle(current)) {
                        candidates += BuildUnit(current, BuildToolKind.Gradle)
                        foundUnit = true
                    }
                    return if (foundUnit) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE
                }
            }
        )
    } catch (e: Exception) {
        logger.warn("Could not discover build units under $dir: ${e.message}")
    }

    return capBuildUnits(candidates, dir, maxUnits, logger)
}

private const val BUILD_UNIT_DISCOVERY_SUBDIR_DEPTH = 3

// walkFileTree visits the root at maxDepth=1, so add one to reach subdirectories at depth 3.
private const val BUILD_UNIT_DISCOVERY_WALK_MAX_DEPTH = BUILD_UNIT_DISCOVERY_SUBDIR_DEPTH + 1

private fun capBuildUnits(
    candidates: List<BuildUnit>,
    dir: Path,
    maxUnits: Int,
    logger: RunnerLogger
): BuildUnitDiscoveryResult {
    val sorted = candidates.sortedWith(
        compareBy<BuildUnit> { normalizedRelativePath(dir, it.dir) }
            .thenBy { it.tool.ordinal }
    )
    val truncated = sorted.size > maxUnits
    if (truncated) {
        logger.warn(
            "Discovered more than $maxUnits build unit(s) under $dir; " +
                "using the first $maxUnits by root-relative path and leaving the rest to later stages"
        )
    }
    return BuildUnitDiscoveryResult(sorted.take(maxUnits), truncated)
}

private fun normalizedRelativePath(root: Path, path: Path): String =
    root.relativize(path).toString().replace('\\', '/')

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
