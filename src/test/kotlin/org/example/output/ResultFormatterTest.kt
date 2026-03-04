package org.example.output

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.openrewrite.ExecutionContext
import org.openrewrite.InMemoryExecutionContext
import org.openrewrite.Recipe
import org.openrewrite.Result
import org.openrewrite.TreeVisitor
import org.openrewrite.internal.InMemoryLargeSourceSet
import org.openrewrite.text.PlainText
import org.openrewrite.text.PlainTextParser
import org.openrewrite.text.PlainTextVisitor
import java.io.ByteArrayOutputStream
import java.io.PrintWriter
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResultFormatterTest {

    @TempDir
    lateinit var reportDir: Path

    private val json = ObjectMapper().registerKotlinModule()
    private val ctx = InMemoryExecutionContext {}

    /**
     * Run an inline recipe that replaces the full text of every PlainText file.
     * Going through the proper recipe pipeline ensures Result.diff() works correctly.
     */
    private fun makeResult(name: String, before: String, after: String): Result {
        val parser = PlainTextParser()
        val sourceFiles: List<org.openrewrite.SourceFile> = parser.parse(ctx, before)
            .map { (it as PlainText).withSourcePath(Path.of(name)) as org.openrewrite.SourceFile }
            .toList()

        val replaceRecipe = object : Recipe() {
            override fun getDisplayName() = "ReplaceText"
            override fun getDescription() = "Replaces full PlainText content for testing"
            override fun getVisitor(): TreeVisitor<*, ExecutionContext> =
                object : PlainTextVisitor<ExecutionContext>() {
                    override fun visitText(text: PlainText, p: ExecutionContext): PlainText =
                        text.withText(after)
                }
        }

        val recipeRun = replaceRecipe.run(InMemoryLargeSourceSet(sourceFiles), ctx)
        return recipeRun.changeset.allResults.first()
    }

    private fun captureOutput(block: (PrintWriter) -> Unit): String {
        val baos = ByteArrayOutputStream()
        val pw = PrintWriter(baos, true)
        block(pw)
        pw.flush()
        return baos.toString()
    }

    // ─── DIFF mode ────────────────────────────────────────────────────────────

    @Test
    fun `DIFF mode prints unified diff for changed file`() {
        val result = makeResult("Hello.txt", "hello\n", "hello world\n")
        val output = captureOutput { pw ->
            ResultFormatter(OutputMode.DIFF, pw).format(listOf(result))
        }
        assertTrue(output.contains("---"), "Diff output should contain '---' header")
        assertTrue(output.contains("+++"), "Diff output should contain '+++' header")
        assertTrue(output.contains("hello world"), "Diff should show new content")
    }

    @Test
    fun `DIFF mode prints message when no changes`() {
        val output = captureOutput { pw ->
            ResultFormatter(OutputMode.DIFF, pw).format(emptyList())
        }
        assertTrue(output.contains("No changes"), "Should indicate no changes when result list is empty")
    }

    @Test
    fun `DIFF mode handles multiple results`() {
        val r1 = makeResult("Foo.txt", "foo\n", "foo bar\n")
        val r2 = makeResult("Bar.txt", "bar\n", "bar baz\n")
        val output = captureOutput { pw ->
            ResultFormatter(OutputMode.DIFF, pw).format(listOf(r1, r2))
        }
        assertTrue(output.contains("Foo"), "Should contain first file diff")
        assertTrue(output.contains("Bar"), "Should contain second file diff")
    }

    // ─── FILES mode ───────────────────────────────────────────────────────────

    @Test
    fun `FILES mode prints only changed file paths`() {
        val result1 = makeResult("Foo.txt", "foo\n", "foo bar\n")
        val result2 = makeResult("Bar.txt", "bar\n", "bar baz\n")
        val output = captureOutput { pw ->
            ResultFormatter(OutputMode.FILES, pw).format(listOf(result1, result2))
        }
        assertTrue(output.contains("Foo.txt"), "Should print Foo.txt path")
        assertTrue(output.contains("Bar.txt"), "Should print Bar.txt path")
        assertFalse(output.contains("---"), "FILES mode should not include diff content")
        assertFalse(output.contains("+++"), "FILES mode should not include diff content")
    }

    @Test
    fun `FILES mode prints message when no changes`() {
        val output = captureOutput { pw ->
            ResultFormatter(OutputMode.FILES, pw).format(emptyList())
        }
        assertTrue(output.contains("No files changed"), "Should indicate no changes")
    }

    @Test
    fun `FILES mode lists each file on its own line`() {
        val results = listOf(
            makeResult("a.txt", "a\n", "aa\n"),
            makeResult("b.txt", "b\n", "bb\n"),
        )
        val output = captureOutput { pw ->
            ResultFormatter(OutputMode.FILES, pw).format(results)
        }
        val lines = output.trim().lines().filter { it.isNotBlank() }
        assertEquals(2, lines.size, "Each changed file should appear on its own line")
    }

    // ─── REPORT mode ──────────────────────────────────────────────────────────

    @Test
    fun `REPORT mode writes openrewrite-report_json to reportDir`() {
        val result = makeResult("Hello.txt", "hello\n", "hello world\n")
        captureOutput { pw ->
            ResultFormatter(OutputMode.REPORT, pw).format(listOf(result), reportDir)
        }
        val reportFile = reportDir.resolve("openrewrite-report.json").toFile()
        assertTrue(reportFile.exists(), "Report JSON file should be created")
    }

    @Test
    fun `REPORT mode JSON contains changed file path`() {
        val result = makeResult("Hello.txt", "hello\n", "hi\n")
        captureOutput { pw ->
            ResultFormatter(OutputMode.REPORT, pw).format(listOf(result), reportDir)
        }
        val reportContent = reportDir.resolve("openrewrite-report.json").readText()
        assertTrue(reportContent.contains("Hello.txt"), "Report should contain the file path")
    }

    @Test
    fun `REPORT mode JSON is valid and has expected top-level structure`() {
        val result = makeResult("Foo.txt", "foo\n", "bar\n")
        captureOutput { pw ->
            ResultFormatter(OutputMode.REPORT, pw).format(listOf(result), reportDir)
        }
        val tree = json.readTree(reportDir.resolve("openrewrite-report.json").readText())

        assertTrue(tree.has("results"), "Report JSON should have 'results' field")
        assertTrue(tree.has("totalChanged"), "Report JSON should have 'totalChanged' field")
        assertEquals(1, tree["totalChanged"].asInt(), "totalChanged should be 1")
        assertEquals(1, tree["results"].size(), "results array should have 1 entry")
    }

    @Test
    fun `REPORT mode JSON result entry has required fields`() {
        val result = makeResult("Foo.txt", "foo\n", "bar\n")
        captureOutput { pw ->
            ResultFormatter(OutputMode.REPORT, pw).format(listOf(result), reportDir)
        }
        val entry = json.readTree(reportDir.resolve("openrewrite-report.json").readText())["results"][0]

        assertTrue(entry.has("filePath"), "Each result should have filePath")
        assertTrue(entry.has("diff"), "Each result should have diff")
        assertTrue(entry.has("isNewFile"), "Each result should have isNewFile")
        assertTrue(entry.has("isDeletedFile"), "Each result should have isDeletedFile")
        assertFalse(entry["isNewFile"].asBoolean(), "Modified file should not be marked as new")
        assertFalse(entry["isDeletedFile"].asBoolean(), "Modified file should not be marked as deleted")
    }

    @Test
    fun `REPORT mode prints path of generated report file to stdout`() {
        val output = captureOutput { pw ->
            ResultFormatter(OutputMode.REPORT, pw).format(emptyList(), reportDir)
        }
        assertTrue(output.contains("openrewrite-report.json"), "Should print path to report file")
    }

    @Test
    fun `REPORT mode JSON totalChanged is zero for empty results`() {
        captureOutput { pw ->
            ResultFormatter(OutputMode.REPORT, pw).format(emptyList(), reportDir)
        }
        val tree = json.readTree(reportDir.resolve("openrewrite-report.json").readText())
        assertEquals(0, tree["totalChanged"].asInt())
        assertEquals(0, tree["results"].size())
    }
}
