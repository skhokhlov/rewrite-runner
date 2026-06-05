package io.github.skhokhlov.rewriterunner.plugin

import io.kotest.core.spec.style.FunSpec
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PluginOutputReaderTest :
    FunSpec({
        test("reads estimate time saved duration from plugin output") {
            assertEquals(
                Duration.ofHours(1).plusMinutes(2).plusSeconds(3),
                PluginOutputReader.estimatedTimeSaved(
                    """
                    [WARNING] Results:
                    [WARNING] Estimate time saved: 1h 2m 3s
                    """.trimIndent()
                )
            )
        }

        test("uses the last estimate when dry-run and apply output are both present") {
            assertEquals(
                Duration.ofMinutes(7),
                PluginOutputReader.estimatedTimeSaved(
                    """
                    Estimate time saved: 5m
                    Please review and commit the results.
                    Estimate time saved: 7m
                    """.trimIndent()
                )
            )
        }

        test("returns null when no plugin estimate is present") {
            assertNull(PluginOutputReader.estimatedTimeSaved("No changes produced."))
        }
    })
