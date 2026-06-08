package io.github.skhokhlov.rewriterunner.plugin

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

internal object DataTableReader {
    private const val SOURCES_FILE_RESULTS = "org.openrewrite.table.SourcesFileResults.csv"
    private const val SOURCE_PATH = "sourcePath"
    private const val ESTIMATED_TIME_SAVING = "estimatedTimeSaving"

    fun sumEstimatedTimeSaved(datatablesRoot: Path): Duration? {
        if (!datatablesRoot.exists() || !datatablesRoot.isDirectory()) return null

        val latestDir = latestTimestampDir(datatablesRoot) ?: return null
        val csv = latestDir.resolve(SOURCES_FILE_RESULTS)
        if (!csv.exists()) return null

        return sumEstimatedTimeSavedCsv(csv)
    }

    private fun latestTimestampDir(datatablesRoot: Path): Path? =
        Files.list(datatablesRoot).use { paths ->
            paths
                .filter { it.isDirectory() }
                .max(
                    compareBy<Path> { it.fileName.toString() }
                        .thenBy { safeModifiedMillis(it) }
                )
                .orElse(null)
        }

    private fun sumEstimatedTimeSavedCsv(csv: Path): Duration? {
        val lines = Files.readAllLines(csv, StandardCharsets.UTF_8)
        if (lines.isEmpty()) return null

        val header = parseCsvLine(lines.first())?.mapIndexed { index, value ->
            if (index == 0) value.removePrefix("\uFEFF") else value
        } ?: return null
        val sourcePathColumn = header.indexOf(SOURCE_PATH)
        val estimatedColumn = header.indexOf(ESTIMATED_TIME_SAVING)
        if (sourcePathColumn < 0 || estimatedColumn < 0) return null

        val secondsBySourcePath = linkedMapOf<String, Long>()
        for (line in lines.drop(1)) {
            if (line.isBlank()) continue
            val fields = parseCsvLine(line) ?: return null
            val value = fields.getOrNull(estimatedColumn)?.trim()
            if (value.isNullOrBlank()) continue
            val sourcePath = fields.getOrNull(sourcePathColumn)?.trim()
            if (sourcePath.isNullOrBlank()) return null
            val rowSeconds = try {
                value.toLong()
            } catch (_: ArithmeticException) {
                return null
            } catch (_: NumberFormatException) {
                return null
            }
            secondsBySourcePath[sourcePath] =
                maxOf(secondsBySourcePath[sourcePath] ?: rowSeconds, rowSeconds)
        }

        var seconds = 0L
        for (value in secondsBySourcePath.values) {
            seconds = try {
                Math.addExact(seconds, value)
            } catch (_: ArithmeticException) {
                return null
            }
        }
        return Duration.ofSeconds(seconds)
    }

    private fun parseCsvLine(line: String): List<String>? {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var index = 0

        while (index < line.length) {
            val char = line[index]
            if (inQuotes) {
                when {
                    char == '"' && index + 1 < line.length && line[index + 1] == '"' -> {
                        current.append('"')
                        index++
                    }

                    char == '"' -> inQuotes = false

                    else -> current.append(char)
                }
            } else {
                when (char) {
                    ',' -> {
                        fields.add(current.toString())
                        current.clear()
                    }

                    '"' -> inQuotes = true

                    else -> current.append(char)
                }
            }
            index++
        }

        if (inQuotes) return null
        fields.add(current.toString())
        return fields
    }

    private fun safeModifiedMillis(path: Path): Long = try {
        Files.getLastModifiedTime(path).toMillis()
    } catch (_: Exception) {
        0L
    }
}
