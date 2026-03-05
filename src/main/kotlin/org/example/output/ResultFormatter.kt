package org.example.output

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.openrewrite.Result
import java.io.OutputStream
import java.io.PrintStream
import java.io.PrintWriter
import java.nio.file.Path

enum class OutputMode { DIFF, FILES, REPORT }

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
        json.factory.createGenerator(reportFile.writer()).use { gen ->
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
