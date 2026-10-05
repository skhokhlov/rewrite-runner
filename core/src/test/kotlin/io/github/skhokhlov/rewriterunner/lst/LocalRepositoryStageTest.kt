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

class LocalRepositoryStageTest :
    FunSpec({
        var projectDir: Path = Path.of("")
        var userHome: Path = Path.of("")

        beforeEach {
            projectDir = Files.createTempDirectory("lrst-")
            userHome = Files.createTempDirectory("lrst-home-")
        }

        afterEach {
            projectDir.toFile().deleteRecursively()
            userHome.toFile().deleteRecursively()
        }

        fun seedJar(root: Path, relativePath: String): Path {
            val jar = root.resolve(relativePath)
            Files.createDirectories(jar.parent)
            Files.write(jar, ByteArray(0))
            return jar
        }

        test("returns empty list when no coordinates provided") {
            val stage = LocalRepositoryStage(projectDir, NoOpRunnerLogger, userHome)
            val result = stage.findAvailableJars(emptyList())
            assertEquals(0, result.size)
        }

        test("returns empty list when no local JARs match") {
            val stage = LocalRepositoryStage(projectDir, NoOpRunnerLogger, userHome)
            val result =
                stage.findAvailableJars(listOf("com.example.nonexistent:ultra-rare-lib:99.99.99"))
            assertEquals(0, result.size, "Should return empty when JAR is not locally cached")
        }

        test("only returns paths that actually exist on disk") {
            val stage = LocalRepositoryStage(projectDir, NoOpRunnerLogger, userHome)
            val jar = seedJar(projectDir, ".m2/repository/com/example/lib/1.0/lib-1.0.jar")
            val result = stage.findAvailableJars(
                listOf("com.example:lib:1.0", "org.phantom:unknown:2.0")
            )
            assertEquals(listOf(jar), result)
            result.forEach { path ->
                assertTrue(path.toFile().exists(), "Returned path $path must exist on disk")
            }
        }

        test("logs warning for each unresolved coordinate") {
            val log = CapturingLogger()
            val stage = LocalRepositoryStage(projectDir, log, userHome)
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
            val stage = LocalRepositoryStage(projectDir, NoOpRunnerLogger, userHome)
            // Coordinates with fewer than 3 parts should be silently skipped
            val result = stage.findAvailableJars(listOf("com.example:lib", "groupOnly"))
            assertEquals(
                0,
                result.size,
                "Malformed coordinates should be ignored without throwing"
            )
        }

        test("ignores coordinates with empty segments") {
            val stage = LocalRepositoryStage(projectDir, NoOpRunnerLogger, userHome)
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
            LocalRepositoryStage(projectDir, log, userHome).findAvailableJars(
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
            val stage = LocalRepositoryStage(projectDir, NoOpRunnerLogger, userHome)
            val jar = seedJar(projectDir, ".m2/repository/com/example/lib/1.0/lib-1.0.jar")
            val result =
                stage.findAvailableJars(listOf("com.example:lib:1.0", "com.example:lib:1.0"))
            assertEquals(listOf(jar), result)
        }

        test("finds JAR seeded in global m2") {
            val jar = seedJar(userHome, ".m2/repository/com/example/lib/1.0/lib-1.0.jar")
            val result = LocalRepositoryStage(projectDir, NoOpRunnerLogger, userHome)
                .findAvailableJars(listOf("com.example:lib:1.0"))
            assertEquals(listOf(jar), result)
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

            val result = LocalRepositoryStage(projectDir, NoOpRunnerLogger, userHome)
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

            val result = LocalRepositoryStage(projectDir, NoOpRunnerLogger, userHome)
                .findAvailableJars(listOf("$group:$artifact:$version"))

            assertTrue(
                result.any {
                    it == jar.toPath()
                },
                "Should find JAR in projectDir/.gradle/caches"
            )
        }

        test("prefers global m2 over project-local m2 for same coordinate") {
            val relativePath = ".m2/repository/com/example/lib/1.0/lib-1.0.jar"
            val globalJar = seedJar(userHome, relativePath)
            seedJar(projectDir, relativePath)
            val result = LocalRepositoryStage(projectDir, NoOpRunnerLogger, userHome)
                .findAvailableJars(listOf("com.example:lib:1.0"))
            assertEquals(listOf(globalJar), result)
        }

        test("prefers project-local m2 over global Gradle cache") {
            val mavenJar = seedJar(projectDir, ".m2/repository/com/example/lib/1.0/lib-1.0.jar")
            seedJar(
                userHome,
                ".gradle/caches/modules-2/files-2.1/com.example/lib/1.0/hash/lib-1.0.jar"
            )
            val result = LocalRepositoryStage(projectDir, NoOpRunnerLogger, userHome)
                .findAvailableJars(listOf("com.example:lib:1.0"))
            assertEquals(listOf(mavenJar), result)
        }

        test("prefers global Gradle cache over project-local Gradle cache") {
            val relativePath =
                ".gradle/caches/modules-2/files-2.1/com.example/lib/1.0/hash/lib-1.0.jar"
            val globalJar = seedJar(userHome, relativePath)
            seedJar(projectDir, relativePath)
            val result = LocalRepositoryStage(projectDir, NoOpRunnerLogger, userHome)
                .findAvailableJars(listOf("com.example:lib:1.0"))
            assertEquals(listOf(globalJar), result)
        }
    })
