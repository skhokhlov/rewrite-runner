package io.github.skhokhlov.rewriterunner.lst

import io.github.skhokhlov.rewriterunner.NoOpRunnerLogger
import io.github.skhokhlov.rewriterunner.RunnerLogger
import java.nio.file.Files
import java.nio.file.Path
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
 * **Build tool detection:**
 * The presence of `pom.xml` signals a Maven project; any of `build.gradle`,
 * `build.gradle.kts`, or `settings.gradle(.kts)` signals a Gradle project.
 * If neither is found, [extractClasspath] returns `null` immediately.
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
 * **Failure behaviour:** Any failure — non-zero exit code, process timeout,
 * missing build tool, or an unexpected exception — is logged as a warning and
 * causes [extractClasspath] to return `null`. The pipeline then falls through to
 * [DependencyResolutionStage] (Stage 2).
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
open class BuildToolStage(protected val logger: RunnerLogger) {
    /**
     * Attempts to extract the project's compile classpath by invoking the build tool.
     *
     * Detects the build tool from [projectDir]:
     * - `pom.xml` present → Maven via `mvnw dependency:build-classpath` (or `mvn`)
     * - `build.gradle` / `build.gradle.kts` / `settings.gradle(.kts)` present → Gradle via
     *   a temporary init script that registers a `printClasspathForOpenRewrite` task
     *
     * @return The list of JAR paths that make up the project's compile/test classpath, or
     *   `null` if the build tool could not be invoked, returned a non-zero exit code,
     *   or produced an empty result. A `null` return signals [LstBuilder] to fall through
     *   to [DependencyResolutionStage].
     */
    open fun extractClasspath(projectDir: Path): List<Path>? = when {
        projectDir.resolve("pom.xml").exists() -> extractMavenClasspath(projectDir)
        hasBuildGradle(projectDir) -> extractGradleClasspath(projectDir)
        else -> null
    }

    // ─── Maven ───────────────────────────────────────────────────────────────

    private fun extractMavenClasspath(projectDir: Path): List<Path>? {
        val outputFile = Files.createTempFile("openrewrite-cp-", ".txt")
        try {
            val mvnCmd = if (projectDir.resolve("mvnw").exists()) "./mvnw" else "mvn"
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
                logger = logger
            ) ?: return null

            if (result != 0) {
                logger.warn(
                    "Maven classpath extraction failed with exit code $result — falling through to Stage 2"
                )
                return null
            }

            val content = outputFile.toFile().readText().trim()
            if (content.isEmpty()) return null

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

    private fun extractGradleClasspath(projectDir: Path): List<Path>? {
        val initScript = Files.createTempFile("openrewrite-init-", ".gradle")
        try {
            initScript.toFile().writeText(GRADLE_INIT_SCRIPT)

            val gradleCmd = resolveGradleCommand(projectDir)
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
     * Attempts to compile the project using its build tool.
     *
     * Called by [LstBuilder] after a successful [extractClasspath] when no pre-compiled
     * class directories are found (`target/classes`, `build/classes/java/main`, etc.).
     * Compilation makes the project's own `.class` files available on the classpath, so
     * intra-project type references and wildcard imports within the project itself resolve
     * correctly during OpenRewrite's type-attribution phase.
     *
     * Runs `mvn compile -q` for Maven or `gradle classes -q` for Gradle.
     * Uses the project wrapper (`mvnw` / `gradlew`) when present.
     *
     * @return `true` if compilation exited with code 0; `false` on any failure (non-zero
     *   exit, missing build tool, or exception). Never throws — failure is logged as a
     *   warning and the pipeline continues without compiled class directories.
     */
    open fun tryCompile(projectDir: Path): Boolean = when {
        projectDir.resolve("pom.xml").exists() -> {
            logger.debug("Project appears to be Maven (pom.xml found) -> attempting compilation")
            tryMavenCompile(projectDir)
        }

        hasBuildGradle(projectDir) -> {
            logger.debug(
                "Project appears to be Gradle (build.gradle or settings.gradle found) -> attempting compilation"
            )
            tryGradleCompile(projectDir)
        }

        else -> {
            logger.info("No build tool detected for compilation -> skipping")
            false
        }
    }

    private fun tryMavenCompile(projectDir: Path): Boolean {
        val mvnCmd = if (projectDir.resolve("mvnw").exists()) "./mvnw" else "mvn"
        return runCompileTask(projectDir, listOf(mvnCmd, "compile", "-q"), "Maven")
    }

    private fun tryGradleCompile(projectDir: Path): Boolean = runCompileTask(
        projectDir,
        listOf(resolveGradleCommand(projectDir), "classes", "-q"),
        "Gradle"
    )

    private fun runCompileTask(projectDir: Path, command: List<String>, toolName: String): Boolean =
        try {
            val result = runProcess(projectDir, command, logger = logger) ?: return false
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
