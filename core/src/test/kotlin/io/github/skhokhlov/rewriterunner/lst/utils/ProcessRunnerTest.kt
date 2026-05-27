package io.github.skhokhlov.rewriterunner.lst.utils

import io.github.skhokhlov.rewriterunner.NoOpRunnerLogger
import io.github.skhokhlov.rewriterunner.RunnerLogger
import io.kotest.core.spec.style.FunSpec
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProcessRunnerTest :
    FunSpec({
        var projectDir: Path = Path.of("")

        beforeEach { projectDir = Files.createTempDirectory("prt-") }

        afterEach { projectDir.toFile().deleteRecursively() }

        fun unitPaths(units: List<BuildUnit>): Set<Pair<BuildToolKind, String>> =
            units.map { unit ->
                unit.tool to projectDir.relativize(unit.dir).toString().replace('\\', '/')
            }.toSet()

        fun orderedUnitPaths(units: List<BuildUnit>): List<Pair<BuildToolKind, String>> =
            units.map { unit ->
                unit.tool to projectDir.relativize(unit.dir).toString().replace('\\', '/')
            }

        fun mkdir(relative: String): Path =
            projectDir.resolve(relative).also { Files.createDirectories(it) }

        test("discoverBuildUnits returns root Maven unit when root pom exists") {
            projectDir.resolve("pom.xml").writeText("<project/>")
            mkdir("service").resolve("pom.xml").writeText("<project/>")

            val units = discoverBuildUnits(projectDir, logger = NoOpRunnerLogger)

            assertEquals(setOf(BuildToolKind.Maven to ""), unitPaths(units))
        }

        test("discoverBuildUnits returns root Gradle unit when root settings exists") {
            projectDir.resolve("settings.gradle.kts").writeText("include(\":app\")")
            mkdir("app").resolve("build.gradle.kts").writeText("")

            val units = discoverBuildUnits(projectDir, logger = NoOpRunnerLogger)

            assertEquals(setOf(BuildToolKind.Gradle to ""), unitPaths(units))
        }

        test("discoverBuildUnits finds top-most Maven subdir units when root has no pom") {
            mkdir("services/api").resolve("pom.xml").writeText("<project/>")
            mkdir("services/worker").resolve("pom.xml").writeText("<project/>")

            val units = discoverBuildUnits(projectDir, logger = NoOpRunnerLogger)

            assertEquals(
                setOf(
                    BuildToolKind.Maven to "services/api",
                    BuildToolKind.Maven to "services/worker"
                ),
                unitPaths(units)
            )
        }

        test("discoverBuildUnits finds mixed Maven and Gradle subdir units") {
            mkdir("java-api").resolve("pom.xml").writeText("<project/>")
            mkdir("kotlin-api").resolve("build.gradle.kts").writeText("")

            val units = discoverBuildUnits(projectDir, logger = NoOpRunnerLogger)

            assertEquals(
                setOf(
                    BuildToolKind.Maven to "java-api",
                    BuildToolKind.Gradle to "kotlin-api"
                ),
                unitPaths(units)
            )
        }

        test("discoverBuildUnits prunes nested build files below a top-most unit") {
            mkdir("platform").resolve("pom.xml").writeText("<project/>")
            mkdir("platform/module").resolve("pom.xml").writeText("<project/>")

            val units = discoverBuildUnits(projectDir, logger = NoOpRunnerLogger)

            assertEquals(setOf(BuildToolKind.Maven to "platform"), unitPaths(units))
        }

        test("discoverBuildUnits skips excluded directories") {
            mkdir("target/generated").resolve("pom.xml").writeText("<project/>")
            mkdir("node_modules/example").resolve("build.gradle").writeText("")
            mkdir("src/service").resolve("pom.xml").writeText("<project/>")

            val units = discoverBuildUnits(projectDir, logger = NoOpRunnerLogger)

            assertEquals(setOf(BuildToolKind.Maven to "src/service"), unitPaths(units))
        }

        test("discoverBuildUnits includes descriptors at depth three") {
            mkdir("one/two/three").resolve("pom.xml").writeText("<project/>")

            val units = discoverBuildUnits(projectDir, logger = NoOpRunnerLogger)

            assertEquals(setOf(BuildToolKind.Maven to "one/two/three"), unitPaths(units))
        }

        test("discoverBuildUnits excludes descriptors deeper than depth three") {
            mkdir("one/two/three/four").resolve("pom.xml").writeText("<project/>")

            val units = discoverBuildUnits(projectDir, logger = NoOpRunnerLogger)

            assertTrue(units.isEmpty(), "Depth-four descriptors should not be discovered")
        }

        test("discoverBuildUnits caps units and warns when more units are present") {
            repeat(27) { index ->
                mkdir("service-$index").resolve("pom.xml").writeText("<project/>")
            }
            val logger = CapturingLogger()

            val units = discoverBuildUnits(projectDir, maxUnits = 25, logger = logger)

            assertEquals(25, units.size)
            assertTrue(
                logger.warns.any { "25" in it && "build unit" in it },
                "Expected cap warning, got ${logger.warns}"
            )
        }

        test("discoverBuildUnits sorts candidates before applying cap") {
            mkdir("z-service").resolve("pom.xml").writeText("<project/>")
            mkdir("a-service").resolve("pom.xml").writeText("<project/>")
            mkdir("m-service").resolve("pom.xml").writeText("<project/>")

            val units = discoverBuildUnits(projectDir, maxUnits = 2, logger = NoOpRunnerLogger)

            assertEquals(
                listOf(
                    BuildToolKind.Maven to "a-service",
                    BuildToolKind.Maven to "m-service"
                ),
                orderedUnitPaths(units)
            )
        }

        test("discoverBuildUnitResult reports truncated discovery") {
            mkdir("a-service").resolve("pom.xml").writeText("<project/>")
            mkdir("b-service").resolve("pom.xml").writeText("<project/>")

            val result = discoverBuildUnitResult(
                projectDir,
                maxUnits = 1,
                logger = NoOpRunnerLogger
            )

            assertEquals(1, result.units.size)
            assertTrue(result.truncated)
        }

        test("discoverBuildUnits returns empty list for empty directory") {
            val units = discoverBuildUnits(projectDir, logger = NoOpRunnerLogger)

            assertTrue(units.isEmpty())
        }

        test("detectBuildTool returns Maven for a root pom") {
            projectDir.resolve("pom.xml").writeText("<project/>")

            assertEquals(BuildToolKind.Maven, detectBuildTool(projectDir, NoOpRunnerLogger))
        }

        test("detectBuildTool returns Gradle for a root build file") {
            projectDir.resolve("build.gradle.kts").writeText("")

            assertEquals(BuildToolKind.Gradle, detectBuildTool(projectDir, NoOpRunnerLogger))
        }

        test("detectBuildTool returns Gradle for settings only") {
            projectDir.resolve("settings.gradle").writeText("pluginManagement {}")

            assertEquals(BuildToolKind.Gradle, detectBuildTool(projectDir, NoOpRunnerLogger))
        }

        test("detectBuildTool returns Gradle and warns when Maven and Gradle are both present") {
            projectDir.resolve("pom.xml").writeText("<project/>")
            projectDir.resolve("build.gradle.kts").writeText("")
            val logger = CapturingLogger()

            val tool = detectBuildTool(projectDir, logger)

            assertEquals(BuildToolKind.Gradle, tool)
            assertTrue(
                logger.warns.any { "Both Gradle and Maven build files" in it },
                "Expected both-build-files warning, got ${logger.warns}"
            )
        }

        test("detectBuildTool returns None when no root descriptor is present") {
            assertEquals(BuildToolKind.None, detectBuildTool(projectDir, NoOpRunnerLogger))
        }

        test("resolveMavenCommand prefers Unix wrapper then Windows wrapper then mvn") {
            assertEquals("mvn", resolveMavenCommand(projectDir))

            projectDir.resolve("mvnw.cmd").writeText("")
            assertEquals("mvnw.cmd", resolveMavenCommand(projectDir))

            projectDir.resolve("mvnw").writeText("")
            assertEquals("./mvnw", resolveMavenCommand(projectDir))
        }

        test("resolveMavenCommand can reuse root wrapper for subdirectory unit") {
            val moduleDir = mkdir("services/api")
            projectDir.resolve("mvnw").writeText("")

            assertEquals(
                projectDir.resolve("mvnw").toAbsolutePath().toString(),
                resolveMavenCommand(moduleDir, projectDir)
            )
        }

        test("resolveGradleCommand prefers Unix wrapper then Windows wrapper then gradle") {
            assertEquals("gradle", resolveGradleCommand(projectDir))

            projectDir.resolve("gradlew.bat").writeText("")
            assertEquals("gradlew.bat", resolveGradleCommand(projectDir))

            projectDir.resolve("gradlew").writeText("")
            assertEquals("./gradlew", resolveGradleCommand(projectDir))
        }

        test("resolveGradleCommand can reuse root wrapper for subdirectory unit") {
            val moduleDir = mkdir("services/api")
            projectDir.resolve("gradlew").writeText("")

            assertEquals(
                projectDir.resolve("gradlew").toAbsolutePath().toString(),
                resolveGradleCommand(moduleDir, projectDir)
            )
        }
    })

private class CapturingLogger : RunnerLogger by NoOpRunnerLogger {
    val warns = mutableListOf<String>()

    override fun warn(message: String) {
        warns += message
    }
}
