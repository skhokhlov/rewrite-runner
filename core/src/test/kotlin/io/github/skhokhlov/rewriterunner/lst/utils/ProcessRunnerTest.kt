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

        fun mkdir(relative: String): Path =
            projectDir.resolve(relative).also { Files.createDirectories(it) }

        test("discoverBuildUnits returns root Maven unit when root pom exists") {
            projectDir.resolve("pom.xml").writeText("<project/>")
            mkdir("service").resolve("pom.xml").writeText("<project/>")

            val units = discoverBuildUnits(projectDir, logger = NoOpRunnerLogger)

            assertEquals(setOf(BuildToolKind.MAVEN to ""), unitPaths(units))
        }

        test("discoverBuildUnits returns root Gradle unit when root settings exists") {
            projectDir.resolve("settings.gradle.kts").writeText("include(\":app\")")
            mkdir("app").resolve("build.gradle.kts").writeText("")

            val units = discoverBuildUnits(projectDir, logger = NoOpRunnerLogger)

            assertEquals(setOf(BuildToolKind.GRADLE to ""), unitPaths(units))
        }

        test("discoverBuildUnits finds top-most Maven subdir units when root has no pom") {
            mkdir("services/api").resolve("pom.xml").writeText("<project/>")
            mkdir("services/worker").resolve("pom.xml").writeText("<project/>")

            val units = discoverBuildUnits(projectDir, logger = NoOpRunnerLogger)

            assertEquals(
                setOf(
                    BuildToolKind.MAVEN to "services/api",
                    BuildToolKind.MAVEN to "services/worker"
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
                    BuildToolKind.MAVEN to "java-api",
                    BuildToolKind.GRADLE to "kotlin-api"
                ),
                unitPaths(units)
            )
        }

        test("discoverBuildUnits prunes nested build files below a top-most unit") {
            mkdir("platform").resolve("pom.xml").writeText("<project/>")
            mkdir("platform/module").resolve("pom.xml").writeText("<project/>")

            val units = discoverBuildUnits(projectDir, logger = NoOpRunnerLogger)

            assertEquals(setOf(BuildToolKind.MAVEN to "platform"), unitPaths(units))
        }

        test("discoverBuildUnits skips excluded directories") {
            mkdir("target/generated").resolve("pom.xml").writeText("<project/>")
            mkdir("node_modules/example").resolve("build.gradle").writeText("")
            mkdir("src/service").resolve("pom.xml").writeText("<project/>")

            val units = discoverBuildUnits(projectDir, logger = NoOpRunnerLogger)

            assertEquals(setOf(BuildToolKind.MAVEN to "src/service"), unitPaths(units))
        }

        test("discoverBuildUnits respects depth three boundary") {
            mkdir("one/two/three").resolve("pom.xml").writeText("<project/>")
            mkdir("one/two/three/four").resolve("build.gradle.kts").writeText("")

            val units = discoverBuildUnits(projectDir, logger = NoOpRunnerLogger)

            assertEquals(setOf(BuildToolKind.MAVEN to "one/two/three"), unitPaths(units))
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

        test("discoverBuildUnits returns empty list for empty directory") {
            val units = discoverBuildUnits(projectDir, logger = NoOpRunnerLogger)

            assertTrue(units.isEmpty())
        }

        test("resolveMavenCommand prefers Unix wrapper then Windows wrapper then mvn") {
            assertEquals("mvn", resolveMavenCommand(projectDir))

            projectDir.resolve("mvnw.cmd").writeText("")
            assertEquals("mvnw.cmd", resolveMavenCommand(projectDir))

            projectDir.resolve("mvnw").writeText("")
            assertEquals("./mvnw", resolveMavenCommand(projectDir))
        }

        test("resolveGradleCommand prefers Unix wrapper then Windows wrapper then gradle") {
            assertEquals("gradle", resolveGradleCommand(projectDir))

            projectDir.resolve("gradlew.bat").writeText("")
            assertEquals("gradlew.bat", resolveGradleCommand(projectDir))

            projectDir.resolve("gradlew").writeText("")
            assertEquals("./gradlew", resolveGradleCommand(projectDir))
        }
    })

private class CapturingLogger : RunnerLogger by NoOpRunnerLogger {
    val warns = mutableListOf<String>()

    override fun warn(message: String) {
        warns += message
    }
}
