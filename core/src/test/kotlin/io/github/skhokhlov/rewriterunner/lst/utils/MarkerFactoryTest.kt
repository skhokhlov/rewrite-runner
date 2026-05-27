package io.github.skhokhlov.rewriterunner.lst.utils

import io.github.skhokhlov.rewriterunner.NoOpRunnerLogger
import io.github.skhokhlov.rewriterunner.RunnerLogger
import io.kotest.core.spec.style.FunSpec
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.openrewrite.marker.BuildTool

class MarkerFactoryTest :
    FunSpec({
        var projectDir: Path = Path.of("")

        beforeEach { projectDir = Files.createTempDirectory("mft-") }

        afterEach { projectDir.toFile().deleteRecursively() }

        fun markerFactory(
            logger: RunnerLogger = NoOpRunnerLogger,
            processRunner: ProcessRunner = { _, _, output, _, _, _ ->
                output?.append("Apache Maven 3.9.9\n")
                0
            }
        ): MarkerFactory = MarkerFactory(
            logger = logger,
            staticParser = StaticBuildFileParser(logger),
            versionDetector = VersionDetector(logger),
            processRunner = processRunner
        )

        test("build-tool marker uses Gradle and warns when Maven and Gradle are both present") {
            projectDir.resolve("pom.xml").writeText("<project/>")
            projectDir.resolve("build.gradle.kts").writeText("")
            val logger = MarkerCapturingLogger()
            var mavenProbeCalled = false

            val marker = markerFactory(
                logger = logger,
                processRunner = { _, _, _, _, _, _ ->
                    mavenProbeCalled = true
                    0
                }
            ).detectBuildToolMarker(projectDir)

            assertEquals(BuildTool.Type.Gradle, marker?.type)
            assertEquals("unknown", marker?.version)
            assertTrue(
                logger.warns.any { "Both Gradle and Maven build files" in it },
                "Expected both-build-files warning, got ${logger.warns}"
            )
            assertFalse(mavenProbeCalled)
        }

        test("build-tool marker uses Maven for pom-only roots") {
            projectDir.resolve("pom.xml").writeText("<project/>")

            val marker = markerFactory().detectBuildToolMarker(projectDir)

            assertEquals(BuildTool.Type.Maven, marker?.type)
            assertEquals("3.9.9", marker?.version)
        }

        test("build-tool marker is absent when no root descriptor exists") {
            assertNull(markerFactory().detectBuildToolMarker(projectDir))
        }

        test("maven metadata probe uses a short timeout cap") {
            projectDir.resolve("pom.xml").writeText("<project/>")
            projectDir.resolve("mvnw").writeText("")
            var observedCommand: List<String>? = null
            var observedTimeout: Duration? = null

            val factory =
                MarkerFactory(
                    logger = NoOpRunnerLogger,
                    staticParser = StaticBuildFileParser(NoOpRunnerLogger),
                    versionDetector = VersionDetector(NoOpRunnerLogger),
                    processTimeout = Duration.ofSeconds(20),
                    processRunner = { workDir, command, _, timeout, _, _ ->
                        assertEquals(projectDir, workDir)
                        observedCommand = command
                        observedTimeout = timeout
                        null
                    }
                )

            val marker = factory.detectBuildToolMarker(projectDir)

            assertEquals(BuildTool.Type.Maven, marker?.type)
            assertEquals("unknown", marker?.version)
            assertEquals(listOf("./mvnw", "--version"), observedCommand)
            assertEquals(Duration.ofSeconds(5), observedTimeout)
        }
    })

private class MarkerCapturingLogger : RunnerLogger by NoOpRunnerLogger {
    val warns = mutableListOf<String>()

    override fun warn(message: String) {
        warns += message
    }
}
