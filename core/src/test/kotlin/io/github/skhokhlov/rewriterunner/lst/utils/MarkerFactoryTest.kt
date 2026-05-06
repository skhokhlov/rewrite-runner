package io.github.skhokhlov.rewriterunner.lst.utils

import io.github.skhokhlov.rewriterunner.NoOpRunnerLogger
import io.kotest.core.spec.style.FunSpec
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import org.openrewrite.marker.BuildTool

class MarkerFactoryTest :
    FunSpec({
        var projectDir: Path = Path.of("")

        beforeEach { projectDir = Files.createTempDirectory("mft-") }

        afterEach { projectDir.toFile().deleteRecursively() }

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
