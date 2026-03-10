package io.github.skhokhlov.rewriterunner.output

import java.io.OutputStream
import java.io.PrintStream
import java.io.PrintWriter
import java.nio.file.Path
import org.openrewrite.Result
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

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
 * work with [io.github.skhokhlov.rewriterunner.RunResult.results] directly.
 *
 * @see OutputMode
 */
class ResultFormatter(
    private val outputMode: OutputMode = OutputMode.DIFF,
    private val out: PrintStream = System.out
) {
    /** Secondary constructor accepting a picocli-style PrintWriter. */
    constructor(outputMode: OutputMode, writer: PrintWriter) :
        this(
            outputMode,
            PrintStream(
                object : OutputStream() {
                    override fun write(b: Int) {
                        writer.write(b)
                    }
                    override fun write(b: ByteArray, off: Int, len: Int) {
                        writer.write(String(b, off, len))
                    }
                    override fun flush() {
                        writer.flush()
                    }
                },
                true
            )
        )
    private val json = JsonMapper.builder()
        .addModule(KotlinModule.Builder().build())
        .build()

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
        val report = mapOf(
            "totalChanged" to results.size,
            "results" to results.map { r ->
                mapOf(
                    "filePath" to (r.after?.sourcePath ?: r.before?.sourcePath)?.toString(),
                    "isNewFile" to (r.before == null),
                    "isDeletedFile" to (r.after == null),
                    "diff" to r.diff()
                )
            }
        )
        json.writerWithDefaultPrettyPrinter().writeValue(reportFile, report)
        out.println("Report written to: ${reportFile.absolutePath}")
    }
}
