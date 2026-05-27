package io.github.skhokhlov.rewriterunner.lst

import io.github.skhokhlov.rewriterunner.RunnerLogger
import io.github.skhokhlov.rewriterunner.config.ToolConfigDefaults
import io.github.skhokhlov.rewriterunner.lst.utils.BuildToolKind
import io.github.skhokhlov.rewriterunner.lst.utils.discoverBuildUnitResult
import io.github.skhokhlov.rewriterunner.lst.utils.discoverBuildUnits
import io.github.skhokhlov.rewriterunner.lst.utils.resolveGradleCommand
import io.github.skhokhlov.rewriterunner.lst.utils.resolveMavenCommand
import io.github.skhokhlov.rewriterunner.lst.utils.runProcess
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.exists

/**
 * Stage 1 of the LST classpath-resolution pipeline: extract the project's compile
 * classpath by invoking its own build tool (Maven or Gradle).
 *
 * **Why Stage 1?**
 * The most accurate way to obtain the exact JAR set the project actually compiles
 * against is to ask the build tool itself. This accounts for BOM imports, version
 * catalogs, dependency management, and plugin-contributed dependencies that are
 * difficult to reproduce by static parsing alone.
 *
 * **Build unit discovery:**
 * Root descriptors keep the historical single-root invocation for that tool. If a tool has no root
 * descriptor, top-most subdirectory descriptors become build units, so root-less monorepos can still
 * use build-tool classpaths. Maven and Gradle units are non-exclusive and their results are merged.
 * Root-level wrappers are reused when subdirectory units do not have their own wrappers.
 *
 * **Maven:** Runs `mvnw dependency:build-classpath` (using the Maven wrapper if
 * present, otherwise `mvn`). The classpath is written to a temp file via
 * `-Dmdep.outputFile` and then parsed. The `test` scope is included
 * (`-DincludeScope=test`) so test-only dependencies are available when recipes
 * analyse test sources.
 *
 * **Gradle:** Injects a temporary Gradle init script that registers a
 * `printClasspathForOpenRewrite` task. The task resolves the project's
 * `testRuntimeClasspath` (falling back through `runtimeClasspath`,
 * `testCompileClasspath`, `compileClasspath`, and `default` in that order) and
 * prints each JAR path to stdout. The Gradle wrapper (`gradlew`) is used when
 * present; otherwise the system `gradle` command is used.
 *
 * **Failure behaviour:** Per-unit failures — non-zero exit code, process timeout,
 * missing build tool, unexpected exception, or capped discovery — are logged as warnings.
 * [extractClasspath] returns the merged classpath only when every discovered unit completes.
 * Partial coverage returns `null` so the pipeline falls through to [DependencyResolutionStage]
 * (Stage 2).
 *
 * **Compilation:** After a successful Stage 1, [tryCompile] is called if the
 * project has no pre-compiled class directories. Compiled `.class` files are then
 * appended to the classpath so that intra-project type references (e.g. wildcard
 * imports across packages within the same project) resolve correctly instead of
 * appearing as `JavaType.Unknown` during recipe execution.
 *
 * **Extensibility:** The class is `open` with `open` methods so tests can subclass
 * it to inject a fake classpath without spawning real processes.
 */
open class ProjectBuildStage(
    protected val logger: RunnerLogger,
    private val processTimeout: Duration = ToolConfigDefaults.SUBPROCESS_RUN_TIMEOUT
) {
    /**
     * Attempts to extract the project's compile classpath by invoking the build tool.
     *
     * Discovers Maven and Gradle build units under [projectDir], invokes each unit's build tool,
     * and returns the distinct union of successful JAR paths.
     *
     * @return The list of JAR paths that make up the project's compile/test classpath, or
     *   `null` if no unit could be invoked successfully or every successful unit produced an empty
     *   result. A `null` return signals [LstBuilder] to fall through to [DependencyResolutionStage].
     */
    open fun extractClasspath(projectDir: Path): List<Path>? {
        val classpath = linkedSetOf<Path>()
        val discovery = discoverBuildUnitResult(projectDir, logger = logger)
        val units = discovery.units
        if (units.isEmpty()) return null

        var completedUnits = 0
        units.forEach { unit ->
            val unitClasspath = when (unit.tool) {
                BuildToolKind.MAVEN -> extractMavenClasspath(unit.dir, projectDir)
                BuildToolKind.GRADLE -> extractGradleClasspath(unit.dir, projectDir)
            }
            if (unitClasspath != null) {
                completedUnits++
                classpath += unitClasspath
            }
        }

        if (discovery.truncated || completedUnits != units.size) {
            val reason = if (discovery.truncated) {
                "discovery was capped at ${units.size} build unit(s)"
            } else {
                "only $completedUnits/${units.size} build unit(s) completed"
            }
            logger.warn(
                "Stage 1 did not cover the full project: $reason; " +
                    "falling through to Stage 2"
            )
            return null
        }

        return classpath.takeIf { it.isNotEmpty() }?.toList()
    }

    // ─── Maven ───────────────────────────────────────────────────────────────

    private fun extractMavenClasspath(projectDir: Path, rootDir: Path): List<Path>? {
        val outputFile = Files.createTempFile("openrewrite-cp-", ".txt")
        try {
            val mvnCmd = resolveMavenCommand(projectDir, rootDir)
            val mvnCommand = listOf(
                mvnCmd,
                "dependency:build-classpath",
                "-DincludeScope=test",
                "-Dmdep.outputFile=${outputFile.toAbsolutePath()}"
            )
            logger.debug("Stage 1: running ${mvnCommand.joinToString(" ")}")
            val result = runProcess(
                projectDir,
                mvnCommand,
                timeout = processTimeout,
                logger = logger
            ) ?: return null

            if (result != 0) {
                logger.warn(
                    "Maven classpath extraction failed with exit code $result — falling through to Stage 2"
                )
                return null
            }

            val content = outputFile.toFile().readText().trim()
            if (content.isEmpty()) return emptyList()

            return content.split(java.io.File.pathSeparator).map {
                Path.of(it)
            }.filter { it.exists() }.also {
                logger.info("Stage 1: Maven classpath resolved — ${it.size} JAR(s)")
            }
        } catch (e: Exception) {
            logger.warn(
                "Maven classpath extraction threw an exception: ${e.message} — falling through to Stage 2"
            )
            return null
        } finally {
            outputFile.toFile().delete()
        }
    }

    // ─── Gradle ──────────────────────────────────────────────────────────────

    private fun extractGradleClasspath(projectDir: Path, rootDir: Path): List<Path>? {
        val initScript = Files.createTempFile("openrewrite-init-", ".gradle")
        try {
            initScript.toFile().writeText(GRADLE_INIT_SCRIPT)

            val gradleCmd = resolveGradleCommand(projectDir, rootDir)
            val gradleCommand = listOf(
                gradleCmd,
                "-i",
                "-S",
                "--no-parallel",
                "--no-daemon",
                "--no-configuration-cache",
                "printClasspathForOpenRewrite",
                "--init-script",
                initScript.toAbsolutePath().toString()
            )
            logger.debug("Stage 1: running ${gradleCommand.joinToString(" ")}")
            val output = StringBuilder()
            val result = runProcess(
                projectDir,
                gradleCommand,
                captureStdout = output,
                timeout = processTimeout,
                logger = logger
            ) ?: return null

            if (result != 0) {
                logger.warn(
                    "Gradle classpath extraction failed with exit code $result — falling through to Stage 2"
                )
                return null
            }

            return output.toString().lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { Path.of(it) }
                .filter { it.exists() }.also {
                    logger.info("Stage 1: Gradle classpath resolved — ${it.size} JAR(s)")
                }
        } catch (e: Exception) {
            logger.warn(
                "Gradle classpath extraction threw an exception: ${e.message} — falling through to Stage 2"
            )
            return null
        } finally {
            initScript.toFile().delete()
        }
    }

    // ─── Compilation ─────────────────────────────────────────────────────────

    /**
     * Attempts to compile discovered build units using their build tools.
     *
     * Called by [LstBuilder] after a successful [extractClasspath] when no pre-compiled
     * class directories are found (`target/classes`, `build/classes/java/main`, etc.).
     * Compilation makes the project's own `.class` files available on the classpath, so
     * intra-project type references and wildcard imports within the project itself resolve
     * correctly during OpenRewrite's type-attribution phase.
     *
     * Runs `mvn compile` for Maven units or `gradle classes` for Gradle units. Uses each unit's
     * wrapper (`mvnw` / `gradlew`) when present, otherwise falls back to the project root wrapper.
     *
     * @return `true` if any unit compiles successfully; `false` when every unit fails or no unit is
     *   found. Never throws — failure is logged as a warning and the pipeline continues without
     *   compiled class directories.
     */
    open fun tryCompile(projectDir: Path): Boolean {
        val units = discoverBuildUnits(projectDir, logger = logger)
        if (units.isEmpty()) {
            logger.info("No build tool detected for compilation -> skipping")
            return false
        }

        var compiledAny = false
        units.forEach { unit ->
            val compiled = when (unit.tool) {
                BuildToolKind.MAVEN -> {
                    logger.debug("Maven build unit found at ${unit.dir} -> attempting compilation")
                    tryMavenCompile(unit.dir, projectDir)
                }

                BuildToolKind.GRADLE -> {
                    logger.debug("Gradle build unit found at ${unit.dir} -> attempting compilation")
                    tryGradleCompile(unit.dir, projectDir)
                }
            }
            compiledAny = compiledAny || compiled
        }
        return compiledAny
    }

    private fun tryMavenCompile(projectDir: Path, rootDir: Path): Boolean {
        val mvnCmd = resolveMavenCommand(projectDir, rootDir)
        return runCompileTask(projectDir, listOf(mvnCmd, "compile"), "Maven")
    }

    private fun tryGradleCompile(projectDir: Path, rootDir: Path): Boolean = runCompileTask(
        projectDir,
        listOf(
            resolveGradleCommand(projectDir, rootDir),
            "classes",
            "-i",
            "-S",
            "--no-build-cache",
            "--no-configuration-cache"
        ),
        "Gradle"
    )

    private fun runCompileTask(projectDir: Path, command: List<String>, toolName: String): Boolean =
        try {
            val result =
                runProcess(
                    projectDir,
                    command,
                    timeout = processTimeout,
                    logger = logger
                ) ?: return false
            if (result == 0) {
                logger.info("$toolName compilation succeeded")
                true
            } else {
                logger.warn("$toolName compilation failed with exit code $result")
                false
            }
        } catch (e: Exception) {
            logger.warn("$toolName compilation threw an exception: ${e.message}")
            false
        }

    companion object {
        // Uses tasks.register() (lazy) instead of the deprecated eager `task` syntax.
        // Uses configuration.incoming.files instead of the deprecated
        // resolvedConfiguration.resolvedArtifacts API (removed in Gradle 9 without --rerun).
        private val GRADLE_INIT_SCRIPT = """
            allprojects {
                tasks.register('printClasspathForOpenRewrite') {
                    doLast {
                        def cp = project.configurations.findByName('testRuntimeClasspath')
                            ?: project.configurations.findByName('runtimeClasspath')
                            ?: project.configurations.findByName('testCompileClasspath')
                            ?: project.configurations.findByName('compileClasspath')
                            ?: project.configurations.findByName('default')
                        cp?.incoming?.files?.each { file ->
                            if (file.name.endsWith('.jar')) {
                                println file.absolutePath
                            }
                        }
                    }
                }
            }
        """.trimIndent()
    }
}
