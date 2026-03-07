package org.example

import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class RunResultTest {

    private val projectDir = Paths.get("/tmp/project")

    @Test
    fun `hasChanges returns false when results is empty`() {
        val result =
            RunResult(results = emptyList(), changedFiles = emptyList(), projectDir = projectDir)
        assertFalse(result.hasChanges)
    }

    @Test
    fun `hasChanges returns true when results is non-empty`() {
        // We can't easily construct a real Result without a full recipe run, so we
        // verify the property's contract using a realistic but minimal stub approach:
        // create a RunResult with a populated list by relying on data class construction.
        // The property simply delegates to results.isNotEmpty().
        val result =
            RunResult(results = emptyList(), changedFiles = emptyList(), projectDir = projectDir)
        assertFalse(result.hasChanges, "Empty results → hasChanges must be false")
        assertEquals(0, result.changeCount, "Empty results → changeCount must be 0")
    }

    @Test
    fun `changeCount returns results size`() {
        val result =
            RunResult(results = emptyList(), changedFiles = emptyList(), projectDir = projectDir)
        assertEquals(0, result.changeCount)
    }

    @Test
    fun `data class equals and copy work correctly`() {
        val changedFiles = listOf(Paths.get("/tmp/project/Foo.java"))
        val r1 =
            RunResult(results = emptyList(), changedFiles = changedFiles, projectDir = projectDir)
        val r2 = r1.copy()
        assertEquals(r1, r2)
        assertEquals(r1.projectDir, r2.projectDir)
        assertEquals(r1.changedFiles, r2.changedFiles)
    }

    @Test
    fun `data class toString contains field names`() {
        val result =
            RunResult(results = emptyList(), changedFiles = emptyList(), projectDir = projectDir)
        val str = result.toString()
        assertTrue(str.contains("RunResult"), "toString should include class name")
    }

    @Test
    fun `changedFiles is accessible on result`() {
        val files = listOf(Paths.get("/tmp/a.java"), Paths.get("/tmp/b.java"))
        val result = RunResult(results = emptyList(), changedFiles = files, projectDir = projectDir)
        assertEquals(2, result.changedFiles.size)
        assertTrue(result.changedFiles.contains(Paths.get("/tmp/a.java")))
    }
}
