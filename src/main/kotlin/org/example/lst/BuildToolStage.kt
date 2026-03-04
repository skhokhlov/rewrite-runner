package org.example.lst

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.logging.Logger
import kotlin.io.path.exists

/**
 * Stage 1: Extract classpath by invoking the project's own build tool (Maven or Gradle).
 * Returns null on any failure (non-zero exit, timeout, missing wrapper), signalling
 * that the pipeline should fall through to Stage 2.
 */
open class BuildToolStage {
    private val log = Logger.getLogger(BuildToolStage::class.java.name)

    open fun extractClasspath(projectDir: Path): List<Path>? {
        return when {
            projectDir.resolve("pom.xml").exists() -> extractMavenClasspath(projectDir)
            hasBuildGradle(projectDir) -> extractGradleClasspath(projectDir)
            else -> null
        }
    }

    // ─── Maven ───────────────────────────────────────────────────────────────

    private fun extractMavenClasspath(projectDir: Path): List<Path>? {
        val outputFile = Files.createTempFile("openrewrite-cp-", ".txt")
        try {
            val mvnCmd = if (projectDir.resolve("mvnw").exists()) "./mvnw" else "mvn"
            val result = runProcess(
                projectDir,
                listOf(
                    mvnCmd,
                    "dependency:build-classpath",
                    "-q",
                    "-DincludeScope=test",
                    "-Dmdep.outputFile=${outputFile.toAbsolutePath()}",
                ),
            ) ?: return null

            if (result != 0) {
                log.warning("Maven classpath extraction failed with exit code $result — falling through to Stage 2")
                return null
            }

            val content = outputFile.toFile().readText().trim()
            if (content.isEmpty()) return null

            return content.split(":").map { Path.of(it) }.filter { it.exists() }
        } catch (e: Exception) {
            log.warning("Maven classpath extraction threw an exception: ${e.message} — falling through to Stage 2")
            return null
        } finally {
            outputFile.toFile().delete()
        }
    }

    // ─── Gradle ──────────────────────────────────────────────────────────────

    private fun extractGradleClasspath(projectDir: Path): List<Path>? {
        val initScript = Files.createTempFile("openrewrite-init-", ".gradle.kts")
        try {
            initScript.toFile().writeText(GRADLE_INIT_SCRIPT)

            val gradleCmd = when {
                projectDir.resolve("gradlew").exists() -> "./gradlew"
                projectDir.resolve("gradlew.bat").exists() -> "gradlew.bat"
                else -> "gradle"
            }

            val output = StringBuilder()
            val result = runProcess(
                projectDir,
                listOf(
                    gradleCmd,
                    "-q",
                    "printClasspathForOpenRewrite",
                    "--init-script",
                    initScript.toAbsolutePath().toString(),
                ),
                captureStdout = output,
            ) ?: return null

            if (result != 0) {
                log.warning("Gradle classpath extraction failed with exit code $result — falling through to Stage 2")
                return null
            }

            return output.toString().lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { Path.of(it) }
                .filter { it.exists() }
        } catch (e: Exception) {
            log.warning("Gradle classpath extraction threw an exception: ${e.message} — falling through to Stage 2")
            return null
        } finally {
            initScript.toFile().delete()
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun hasBuildGradle(dir: Path): Boolean =
        dir.resolve("build.gradle").exists() || dir.resolve("build.gradle.kts").exists()

    private fun runProcess(
        workDir: Path,
        command: List<String>,
        captureStdout: StringBuilder? = null,
        timeoutSeconds: Long = 120,
    ): Int? {
        val pb = ProcessBuilder(command)
            .directory(workDir.toFile())
            .redirectErrorStream(captureStdout == null)

        if (captureStdout != null) {
            pb.redirectError(ProcessBuilder.Redirect.DISCARD)
        }

        val process = try {
            pb.start()
        } catch (e: Exception) {
            log.warning("Failed to start process ${command.first()}: ${e.message}")
            return null
        }

        if (captureStdout != null) {
            captureStdout.append(process.inputStream.bufferedReader().readText())
        }

        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            log.warning("Process ${command.first()} timed out after ${timeoutSeconds}s")
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
