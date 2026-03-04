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

    private data class ResultEntry(
        val filePath: String?,
        val diff: String,
        val isNewFile: Boolean,
        val isDeletedFile: Boolean,
    )

    private data class Report(
        val results: List<ResultEntry>,
        val totalChanged: Int,
    )

    private fun writeReport(results: List<Result>, reportDir: Path) {
        val entries = results.map { r ->
            ResultEntry(
                filePath = (r.after?.sourcePath ?: r.before?.sourcePath)?.toString(),
                diff = r.diff(),
                isNewFile = r.before == null,
                isDeletedFile = r.after == null,
            )
        }

        val report = Report(entries, results.size)
        val reportFile = reportDir.resolve("openrewrite-report.json").toFile()
        json.writerWithDefaultPrettyPrinter().writeValue(reportFile, report)
        out.println("Report written to: ${reportFile.absolutePath}")
    }
}
