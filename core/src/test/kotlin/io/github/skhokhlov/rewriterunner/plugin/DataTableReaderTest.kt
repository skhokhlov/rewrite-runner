package io.github.skhokhlov.rewriterunner.plugin

import io.kotest.core.spec.style.FunSpec
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNull

private const val SOURCES_FILE_RESULTS = "org.openrewrite.table.SourcesFileResults.csv"

class DataTableReaderTest :
    FunSpec({
        var root: Path = Path.of("")

        beforeEach {
            root = Files.createTempDirectory("dtr-")
        }

        afterEach {
            root.toFile().deleteRecursively()
        }

        fun writeCsv(timestamp: String, content: String) {
            val dir = root.resolve(timestamp).createDirectories()
            dir.resolve(SOURCES_FILE_RESULTS).writeText(content.trimIndent())
        }

        test("sums estimated time savings from the latest SourcesFileResults table") {
            writeCsv(
                "2024-01-01T00-00-00Z",
                """
                sourcePath,recipes,estimatedTimeSaving
                "src/A,WithComma.java","Recipe, One",60
                src/B.java,Recipe Two,120
                src/C.java,Recipe Three,
                """
            )

            assertEquals(
                Duration.ofSeconds(180),
                DataTableReader.sumEstimatedTimeSaved(root)
            )
        }

        test("uses the lexicographically latest timestamp directory") {
            writeCsv(
                "2024-01-01T00-00-00Z",
                """
                sourcePath,estimatedTimeSaving
                src/A.java,90
                """
            )
            writeCsv(
                "2024-01-02T00-00-00Z",
                """
                sourcePath,estimatedTimeSaving
                src/A.java,30
                """
            )

            assertEquals(
                Duration.ofSeconds(30),
                DataTableReader.sumEstimatedTimeSaved(root)
            )
        }

        test("returns null when root, latest file, or column is missing") {
            assertNull(DataTableReader.sumEstimatedTimeSaved(root.resolve("missing")))

            root.resolve("2024-01-01T00-00-00Z").createDirectories()
            assertNull(DataTableReader.sumEstimatedTimeSaved(root))

            writeCsv(
                "2024-01-02T00-00-00Z",
                """
                sourcePath,recipe
                src/A.java,Recipe
                """
            )
            assertNull(DataTableReader.sumEstimatedTimeSaved(root))
        }

        test("returns zero for a header-only table") {
            writeCsv(
                "2024-01-01T00-00-00Z",
                """
                sourcePath,estimatedTimeSaving
                """
            )

            assertEquals(Duration.ZERO, DataTableReader.sumEstimatedTimeSaved(root))
        }

        test("returns null when an estimated time value is not parseable") {
            writeCsv(
                "2024-01-01T00-00-00Z",
                """
                sourcePath,estimatedTimeSaving
                src/A.java,nope
                """
            )

            assertNull(DataTableReader.sumEstimatedTimeSaved(root))
        }
    })
