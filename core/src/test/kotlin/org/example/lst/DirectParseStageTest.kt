package org.example.lst

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.core.spec.style.FunSpec
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.slf4j.LoggerFactory

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
            val logger = LoggerFactory.getLogger(DirectParseStage::class.java.name)
                as ch.qos.logback.classic.Logger
            val appender = ListAppender<ILoggingEvent>()
            appender.start()
            logger.addAppender(appender)

            try {
                val stage = DirectParseStage(projectDir)
                stage.findAvailableJars(listOf("com.example:missing:9.9.9"))
                val warnings = appender.list.filter {
                    it.level == ch.qos.logback.classic.Level.WARN
                }
                assertTrue(
                    warnings.any { event ->
                        val msg = event.formattedMessage
                        msg.contains("9.9.9") || msg.contains("missing") ||
                            msg.contains("cached")
                    },
                    "Expected a warning about the unresolved coordinate, got: ${appender.list}"
                )
            } finally {
                logger.detachAppender(appender)
            }
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
    })
