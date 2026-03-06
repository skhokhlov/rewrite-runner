package org.example.output

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.openrewrite.Result
import java.io.OutputStream
import java.io.PrintStream
import java.io.PrintWriter
import java.nio.file.Path

/**
 * The three output modes supported by [ResultFormatter].
 *
 * - [DIFF]: Print a unified diff for each changed file to stdout.
 * - [FILES]: Print one changed file path per line to stdout.
 * - [REPORT]: Write a structured JSON file (`openrewrite-report.json`) to the report directory.
 */
enum class OutputMode { DIFF, FILES, REPORT }

/**
 * Formats the OpenRewrite [org.openrewrite.Result] changeset in one of three modes.
 *
 * @param outputMode Controls the output format. See [OutputMode] for details.
 * @param out Destination for textual output. Defaults to [System.out].
 *
 * The secondary constructor accepts a picocli [java.io.PrintWriter] for CLI integration.
 * Library consumers that do not need formatted output can ignore this class entirely and
 * work with [org.example.RunResult.results] directly.
 *
 * @see OutputMode
 */
class ResultFormatter(
    private val outputMode: OutputMode = OutputMode.DIFF,
    private val out: PrintStream = System.out,
) {
    /** Secondary constructor accepting a picocli-style PrintWriter. */
    constructor(outputMode: OutputMode, writer: PrintWriter) :
        this(outputMode, PrintStream(object : OutputStream() {
            override fun write(b: Int) { writer.write(b) }
            override fun write(b: ByteArray, off: Int, len: Int) { writer.write(String(b, off, len)) }
            override fun flush() { writer.flush() }
        }, true))
    private val json = ObjectMapper().registerKotlinModule()

    /**
     * Write formatted output for the given [results].
     *
     * @param results The changeset from a recipe run. May be empty.
     * @param reportDir Directory where `openrewrite-report.json` is written when
     *   [outputMode] is [OutputMode.REPORT]. Defaults to the current directory.
     *   Ignored for [OutputMode.DIFF] and [OutputMode.FILES].
     */
    fun format(results: List<Result>, reportDir: Path? = null) {
        when (outputMode) {
            OutputMode.DIFF -> printDiffs(results)
            OutputMode.FILES -> printFiles(results)
            OutputMode.REPORT -> writeReport(results, reportDir ?: Path.of("."))
        }
    }

    // ─── diff ─────────────────────────────────────────────────────────────────

    private fun printDiffs(results: List<Result>) {
        if (results.isEmpty()) {
            out.println("No changes produced.")
            return
        }
        for (result in results) {
            val diff = result.diff()
            if (diff.isNotBlank()) {
                out.println(diff)
            }
        }
    }

    // ─── files ────────────────────────────────────────────────────────────────

    private fun printFiles(results: List<Result>) {
        if (results.isEmpty()) {
            out.println("No files changed.")
            return
        }
        for (result in results) {
            val path = result.after?.sourcePath ?: result.before?.sourcePath
            if (path != null) out.println(path)
        }
    }

    // ─── report ───────────────────────────────────────────────────────────────

    private fun writeReport(results: List<Result>, reportDir: Path) {
        val reportFile = reportDir.resolve("openrewrite-report.json").toFile()
        // Stream entries one-by-one so diff strings can be GC'd as we go,
        // rather than holding every diff in memory before the file is written.
        json.factory.createGenerator(reportFile.writer(Charsets.UTF_8)).use { gen ->
            gen.useDefaultPrettyPrinter()
            gen.writeStartObject()
            gen.writeNumberField("totalChanged", results.size)
            gen.writeArrayFieldStart("results")
            for (r in results) {
                gen.writeStartObject()
                gen.writeObjectField("filePath", (r.after?.sourcePath ?: r.before?.sourcePath)?.toString())
                gen.writeBooleanField("isNewFile", r.before == null)
                gen.writeBooleanField("isDeletedFile", r.after == null)
                gen.writeStringField("diff", r.diff())
                gen.writeEndObject()
            }
            gen.writeEndArray()
            gen.writeEndObject()
        }
        out.println("Report written to: ${reportFile.absolutePath}")
    }
}
