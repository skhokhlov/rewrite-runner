package io.github.skhokhlov.rewriterunner.lst

import java.nio.file.Files
import java.nio.file.Path
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

            val gradleCmd = resolveGradleCommand(projectDir)
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
        val gradleCmd = resolveGradleCommand(projectDir)
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

    companion object {
        // Uses tasks.register() (lazy) instead of the deprecated eager `task` syntax.
        // Uses configuration.incoming.files instead of the deprecated
        // resolvedConfiguration.resolvedArtifacts API (removed in Gradle 9 without --rerun).
        private val GRADLE_INIT_SCRIPT = """
            allprojects {
                tasks.register('printClasspathForOpenRewrite') {
                    doLast {
                        def cp = configurations.findByName('testRuntimeClasspath')
                            ?: configurations.findByName('runtimeClasspath')
                            ?: configurations.findByName('testCompileClasspath')
                            ?: configurations.findByName('compileClasspath')
                            ?: configurations.findByName('default')
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
