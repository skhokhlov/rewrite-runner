package io.github.skhokhlov.rewriterunner.plugin

import io.kotest.core.spec.style.FunSpec
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EstimatedTimeSavedResolverTest :
    FunSpec({
        test("first strictly positive source wins") {
            assertEquals(
                Duration.ofSeconds(30),
                EstimatedTimeSavedResolver.resolve(
                    listOf(
                        { Duration.ofSeconds(30) },
                        { Duration.ofSeconds(60) }
                    )
                )
            )
        }

        test("zero falls through to a later positive source") {
            assertEquals(
                Duration.ofMinutes(5),
                EstimatedTimeSavedResolver.resolve(
                    listOf(
                        { Duration.ZERO },
                        { Duration.ofMinutes(5) }
                    )
                )
            )
        }

        test("all non-positive or absent sources resolve to null") {
            assertNull(
                EstimatedTimeSavedResolver.resolve(
                    listOf(
                        { null },
                        { Duration.ZERO }
                    )
                )
            )
        }

        test("empty source list resolves to null") {
            assertNull(EstimatedTimeSavedResolver.resolve(emptyList()))
        }

        test("later sources are not invoked once a positive source is found") {
            var laterInvoked = false

            val result =
                EstimatedTimeSavedResolver.resolve(
                    listOf(
                        { Duration.ofSeconds(1) },
                        {
                            laterInvoked = true
                            Duration.ofSeconds(2)
                        }
                    )
                )

            assertEquals(Duration.ofSeconds(1), result)
            assertEquals(false, laterInvoked)
        }
    })
