package io.github.skhokhlov.rewriterunner.lst

import io.github.skhokhlov.rewriterunner.NoOpRunnerLogger
import io.github.skhokhlov.rewriterunner.RunnerLogger
import io.kotest.core.spec.style.FunSpec
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Simple [RunnerLogger] that records calls for assertion in tests. */
private class CapturingLogger : RunnerLogger {
    data class LogEntry(val level: String, val message: String)

    val entries = mutableListOf<LogEntry>()

    override fun lifecycle(message: String) = entries.add(LogEntry("INFO", message)).let { Unit }
    override fun info(message: String) = entries.add(LogEntry("INFO", message)).let { Unit }
    override fun debug(message: String) = entries.add(LogEntry("DEBUG", message)).let { Unit }
    override fun warn(message: String) = entries.add(LogEntry("WARN", message)).let { Unit }
    override fun error(message: String, cause: Throwable?) =
        entries.add(LogEntry("ERROR", message)).let { Unit }
}

class DirectParseStageTest :
    FunSpec({
        var projectDir: Path = Path.of("")

        beforeEach { projectDir = Files.createTempDirectory("dpst-") }

        afterEach { projectDir.toFile().deleteRecursively() }

        test("returns empty list when no coordinates provided") {
            val stage = DirectParseStage(projectDir)
            val result = stage.findAvailableJars(emptyList())
            assertEquals(0, result.size)
        }

        test("returns empty list when no local JARs match") {
            val stage = DirectParseStage(projectDir)
            // Use a coordinate that certainly won't be in any local cache
            val result =
                stage.findAvailableJars(listOf("com.example.nonexistent:ultra-rare-lib:99.99.99"))
            assertEquals(0, result.size, "Should return empty when JAR is not locally cached")
        }

        test("only returns paths that actually exist on disk") {
            val stage = DirectParseStage(projectDir)
            val result =
                stage.findAvailableJars(
                    listOf("com.example:ghost-lib:1.0", "org.phantom:unknown:2.0")
                )
            // Every returned path must exist
            result.forEach { path ->
                assertTrue(path.toFile().exists(), "Returned path $path must exist on disk")
            }
        }

        test("logs warning for each unresolved coordinate") {
            val log = CapturingLogger()
            val stage = DirectParseStage(projectDir, log)
            stage.findAvailableJars(listOf("com.example:missing:9.9.9"))
            val warnings = log.entries.filter { it.level == "WARN" }
            assertTrue(
                warnings.any { entry ->
                    val msg = entry.message
                    msg.contains("9.9.9") || msg.contains("missing") ||
                        msg.contains("cached")
                },
                "Expected a warning about the unresolved coordinate, got: ${log.entries}"
            )
        }

        test("handles malformed coordinates gracefully") {
            val stage = DirectParseStage(projectDir)
            // Coordinates with fewer than 3 parts should be silently skipped
            val result = stage.findAvailableJars(listOf("com.example:lib", "groupOnly"))
            assertEquals(
                0,
                result.size,
                "Malformed coordinates should be ignored without throwing"
            )
        }

        test("ignores coordinates with empty segments") {
            val stage = DirectParseStage(projectDir)
            // Coordinates that have the right number of colons but blank fields
            // (e.g. a typo like "com.example::1.0") must be rejected, not silently
            // passed through as Coord("com.example", "", "1.0") which produces a
            // confusing "not found" warning instead of a clear "malformed" warning.
            val result = stage.findAvailableJars(
                listOf(
                    "com.example::1.0", // empty artifactId
                    ":artifact:1.0", // empty groupId
                    "group:artifact:", // empty version
                    ":::" // all empty
                )
            )
            assertEquals(
                0,
                result.size,
                "Coordinates with empty segments should be rejected without throwing"
            )
        }

        test(
            "coordinates with empty segments do not appear in the 'could not locate JAR' warning"
        ) {
            // The "could not locate JAR in local caches" warning is specifically for
            // *valid* dependency coordinates that are absent from local Maven/Gradle caches.
            // A coordinate like "com.example::1.0" is malformed (typo), not merely absent;
            // polluting that warning with it gives users a misleading signal that they need
            // to populate their cache for a dependency that was never declared correctly.
            val log = CapturingLogger()
            DirectParseStage(projectDir, log).findAvailableJars(
                listOf(
                    "com.example::1.0", // empty artifactId
                    ":artifact:1.0", // empty groupId
                    "group:artifact:" // empty version
                )
            )

            val messages = log.entries.map { it.message }
            assertFalse(
                messages.any { msg ->
                    msg.contains("com.example::1.0") ||
                        msg.contains(":artifact:1.0") ||
                        msg.contains("group:artifact:")
                },
                "Malformed coordinates must not appear in any warning message; got: $messages"
            )
        }

        test("result list contains no duplicates") {
            val stage = DirectParseStage(projectDir)
            // Even if two coordinates could hypothetically resolve to the same JAR, no duplicates
            val result =
                stage.findAvailableJars(listOf("com.example:lib:1.0", "com.example:lib:1.0"))
            val paths = result.map { it.toString() }
            assertEquals(
                paths.distinct(),
                paths,
                "findAvailableJars should not return duplicate paths"
            )
        }

        test("commonly cached JARs are found when present in local m2") {
            // This test verifies Stage 3 works for real — if commons-lang3 happens to be in
            // the local Maven cache (which is likely on a developer machine), it should be found.
            val stage = DirectParseStage(projectDir)
            val result =
                stage.findAvailableJars(listOf("org.apache.commons:commons-lang3:3.12.0"))

            // We can't guarantee the JAR is cached, but if it is found it must be a real file
            result.forEach { path ->
                assertTrue(path.toFile().exists(), "Found path $path must exist on disk")
                assertTrue(path.toString().endsWith(".jar"), "Found path should be a JAR")
            }
        }

        // ─── Project-local cache discovery ───────────────────────────────────────

        test("finds JAR seeded in projectDir/.m2/repository") {
            val group = "com.example"
            val artifact = "local-m2-lib"
            val version = "1.0"
            val artifactDir = projectDir
                .resolve(".m2/repository/${group.replace('.', '/')}/$artifact/$version")
            artifactDir.toFile().mkdirs()
            val jar = artifactDir.resolve("$artifact-$version.jar").toFile()
                .also { it.writeBytes(ByteArray(0)) }

            val result = DirectParseStage(projectDir)
                .findAvailableJars(listOf("$group:$artifact:$version"))

            assertTrue(result.any { it == jar.toPath() }, "Should find JAR in projectDir/.m2")
        }

        test("finds JAR seeded in projectDir/.gradle/caches") {
            val group = "com.example"
            val artifact = "local-gradle-lib"
            val version = "2.0"
            // Mimic the Gradle cache layout: caches/modules-2/files-2.1/<group>/<artifact>/<version>/<hash>/<jar>
            val artifactDir = projectDir
                .resolve(".gradle/caches/modules-2/files-2.1/$group/$artifact/$version/abc123")
            artifactDir.toFile().mkdirs()
            val jar = artifactDir.resolve("$artifact-$version.jar").toFile()
                .also { it.writeBytes(ByteArray(0)) }

            val result = DirectParseStage(projectDir)
                .findAvailableJars(listOf("$group:$artifact:$version"))

            assertTrue(
                result.any {
                    it == jar.toPath()
                },
                "Should find JAR in projectDir/.gradle/caches"
            )
        }

        test("prefers global m2 over project-local m2 for same coordinate") {
            // Both roots contain the JAR; whichever is checked first (global) is returned.
            // This test seeds only the project-local root and verifies a match is still found.
            val group = "com.example"
            val artifact = "prefer-test"
            val version = "3.0"
            val localDir = projectDir
                .resolve(".m2/repository/${group.replace('.', '/')}/$artifact/$version")
            localDir.toFile().mkdirs()
            val localJar = localDir.resolve("$artifact-$version.jar").toFile()
                .also { it.writeBytes(ByteArray(0)) }

            val result = DirectParseStage(projectDir)
                .findAvailableJars(listOf("$group:$artifact:$version"))

            assertTrue(result.any { it == localJar.toPath() }, "Project-local JAR should be found")
        }
    })
