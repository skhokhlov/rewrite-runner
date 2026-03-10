package io.github.skhokhlov.rewriterunner.lst

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import org.slf4j.LoggerFactory

/**
 * Stage 1: Extract classpath by invoking the project's own build tool (Maven or Gradle).
 * Returns null on any failure (non-zero exit, timeout, missing wrapper), signalling
 * that the pipeline should fall through to Stage 2.
 */
open class BuildToolStage {
    private val log = LoggerFactory.getLogger(BuildToolStage::class.java.name)

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
            log.info("Stage 1: running '$mvnCmd dependency:build-classpath'")
            val result = runProcess(
                projectDir,
                listOf(
                    mvnCmd,
                    "dependency:build-classpath",
                    "-q",
                    "-DincludeScope=test",
                    "-Dmdep.outputFile=${outputFile.toAbsolutePath()}"
                )
            ) ?: return null

            if (result != 0) {
                log.warn(
                    "Maven classpath extraction failed with exit code $result — falling through to Stage 2"
                )
                return null
            }

            val content = outputFile.toFile().readText().trim()
            if (content.isEmpty()) return null

            return content.split(java.io.File.pathSeparator).map {
                Path.of(it)
            }.filter { it.exists() }.also {
                log.info("Stage 1: Maven classpath resolved — ${it.size} JAR(s)")
            }
        } catch (e: Exception) {
            log.warn(
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

            val gradleCmd = when {
                projectDir.resolve("gradlew").exists() -> "./gradlew"
                projectDir.resolve("gradlew.bat").exists() -> "gradlew.bat"
                else -> "gradle"
            }

            log.info("Stage 1: running '$gradleCmd printClasspathForOpenRewrite'")
            val output = StringBuilder()
            val result = runProcess(
                projectDir,
                listOf(
                    gradleCmd,
                    "-q",
                    "printClasspathForOpenRewrite",
                    "--init-script",
                    initScript.toAbsolutePath().toString()
                ),
                captureStdout = output
            ) ?: return null

            if (result != 0) {
                log.warn(
                    "Gradle classpath extraction failed with exit code $result — falling through to Stage 2"
                )
                return null
            }

            return output.toString().lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { Path.of(it) }
                .filter { it.exists() }.also {
                    log.info("Stage 1: Gradle classpath resolved — ${it.size} JAR(s)")
                }
        } catch (e: Exception) {
            log.warn(
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
     * Returns true if compilation succeeded, false on any failure.
     * Never throws — failure is logged as a warning and the pipeline continues.
     */
    open fun tryCompile(projectDir: Path): Boolean = when {
        projectDir.resolve("pom.xml").exists() -> tryMavenCompile(projectDir)
        hasBuildGradle(projectDir) -> tryGradleCompile(projectDir)
        else -> false
    }

    private fun tryMavenCompile(projectDir: Path): Boolean {
        val mvnCmd = if (projectDir.resolve("mvnw").exists()) "./mvnw" else "mvn"
        return try {
            val result = runProcess(projectDir, listOf(mvnCmd, "compile", "-q")) ?: return false
            if (result == 0) {
                log.info("Maven compilation succeeded")
                true
            } else {
                log.warn("Maven compilation failed with exit code $result")
                false
            }
        } catch (e: Exception) {
            log.warn("Maven compilation threw an exception: ${e.message}")
            false
        }
    }

    private fun tryGradleCompile(projectDir: Path): Boolean {
        val gradleCmd = when {
            projectDir.resolve("gradlew").exists() -> "./gradlew"
            projectDir.resolve("gradlew.bat").exists() -> "gradlew.bat"
            else -> "gradle"
        }
        return try {
            val result = runProcess(projectDir, listOf(gradleCmd, "classes", "-q")) ?: return false
            if (result == 0) {
                log.info("Gradle compilation succeeded")
                true
            } else {
                log.warn("Gradle compilation failed with exit code $result")
                false
            }
        } catch (e: Exception) {
            log.warn("Gradle compilation threw an exception: ${e.message}")
            false
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun hasBuildGradle(dir: Path): Boolean =
        dir.resolve("build.gradle").exists() || dir.resolve("build.gradle.kts").exists()

    private fun runProcess(
        workDir: Path,
        command: List<String>,
        captureStdout: StringBuilder? = null,
        timeoutSeconds: Long = 120
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

        val process = try {
            pb.start()
        } catch (e: Exception) {
            log.warn("Failed to start process ${command.first()}: ${e.message}")
            return null
        }

        if (captureStdout != null) {
            // Read all stdout before waitFor so the stdout pipe never fills up.
            captureStdout.append(process.inputStream.bufferedReader().readText())
        }

        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            log.warn("Process ${command.first()} timed out after ${timeoutSeconds}s")
            return null
        }

        return process.exitValue()
    }

    companion object {
        private val GRADLE_INIT_SCRIPT = """
            allprojects {
                task printClasspathForOpenRewrite {
                    doLast {
                        def cp = configurations.findByName('testRuntimeClasspath')
                            ?: configurations.findByName('runtimeClasspath')
                            ?: configurations.findByName('testCompileClasspath')
                            ?: configurations.findByName('compileClasspath')
                            ?: configurations.findByName('default')
                        cp?.resolvedConfiguration?.resolvedArtifacts?.each { artifact ->
                            println artifact.file.absolutePath
                        }
                    }
                }
            }
        """.trimIndent()
    }
}
