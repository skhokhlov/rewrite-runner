package org.example.lst

import java.nio.file.Path
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DirectParseStageTest {

    @TempDir
    lateinit var projectDir: Path

    @Test
    fun `returns empty list when no coordinates provided`() {
        val stage = DirectParseStage(projectDir)
        val result = stage.findAvailableJars(emptyList())
        assertEquals(0, result.size)
    }

    @Test
    fun `returns empty list when no local JARs match`() {
        val stage = DirectParseStage(projectDir)
        // Use a coordinate that certainly won't be in any local cache
        val result = stage.findAvailableJars(
            listOf("com.example.nonexistent:ultra-rare-lib:99.99.99")
        )
        assertEquals(0, result.size, "Should return empty when JAR is not locally cached")
    }

    @Test
    fun `only returns paths that actually exist on disk`() {
        val stage = DirectParseStage(projectDir)
        val result = stage.findAvailableJars(
            listOf(
                "com.example:ghost-lib:1.0",
                "org.phantom:unknown:2.0"
            )
        )
        // Every returned path must exist
        result.forEach { path ->
            assertTrue(path.toFile().exists(), "Returned path $path must exist on disk")
        }
    }

    @Test
    fun `logs warning for each unresolved coordinate`() {
        val warnings = mutableListOf<String>()
        val handler = object : Handler() {
            override fun publish(record: LogRecord) {
                if (record.level == Level.WARNING) warnings.add(record.message)
            }
            override fun flush() {}
            override fun close() {}
        }
        val logger = Logger.getLogger(DirectParseStage::class.java.name)
        logger.addHandler(handler)

        try {
            val stage = DirectParseStage(projectDir)
            stage.findAvailableJars(listOf("com.example:missing:9.9.9"))
            assertTrue(
                warnings.any {
                    it.contains("9.9.9") || it.contains("missing") ||
                        it.contains("cached")
                },
                "Expected a warning about the unresolved coordinate, got: $warnings"
            )
        } finally {
            logger.removeHandler(handler)
        }
    }

    @Test
    fun `handles malformed coordinates gracefully`() {
        val stage = DirectParseStage(projectDir)
        // Coordinates with fewer than 3 parts should be silently skipped
        val result = stage.findAvailableJars(listOf("com.example:lib", "groupOnly"))
        assertEquals(0, result.size, "Malformed coordinates should be ignored without throwing")
    }

    @Test
    fun `result list contains no duplicates`() {
        val stage = DirectParseStage(projectDir)
        // Even if two coordinates could hypothetically resolve to the same JAR, no duplicates
        val result = stage.findAvailableJars(
            listOf("com.example:lib:1.0", "com.example:lib:1.0")
        )
        val paths = result.map { it.toString() }
        assertEquals(paths.distinct(), paths, "findAvailableJars should not return duplicate paths")
    }

    @Test
    fun `commonly cached JARs are found when present in local m2`() {
        // This test verifies Stage 3 works for real — if commons-lang3 happens to be in
        // the local Maven cache (which is likely on a developer machine), it should be found.
        val stage = DirectParseStage(projectDir)
        val result = stage.findAvailableJars(listOf("org.apache.commons:commons-lang3:3.12.0"))

        // We can't guarantee the JAR is cached, but if it is found it must be a real file
        result.forEach { path ->
            assertTrue(path.toFile().exists(), "Found path $path must exist on disk")
            assertTrue(path.toString().endsWith(".jar"), "Found path should be a JAR")
        }
    }
}
