package io.github.skhokhlov.rewriterunner.plugin

import io.kotest.core.spec.style.FunSpec
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PluginExclusionEncodingTest :
    FunSpec({
        context("gradleDsl") {
            test("returns empty list when excludePaths is empty") {
                assertEquals(emptyList(), PluginExclusionEncoding.gradleDsl(emptyList()))
            }

            test("returns one exclusion(...) line per glob") {
                assertEquals(
                    listOf(
                        "exclusion(\"**/generated/**\")",
                        "exclusion(\"src/test/**\")"
                    ),
                    PluginExclusionEncoding.gradleDsl(listOf("**/generated/**", "src/test/**"))
                )
            }

            test("escapes Groovy specials in glob patterns") {
                assertEquals(
                    listOf(
                        "exclusion(\"path\\\\with\\\\backslash\")",
                        "exclusion(\"has\\\"quote\")",
                        "exclusion(\"has\\\$dollar\")"
                    ),
                    PluginExclusionEncoding.gradleDsl(
                        listOf("path\\with\\backslash", "has\"quote", "has\$dollar")
                    )
                )
            }
        }

        context("mavenCsv") {
            test("returns null when excludePaths is empty") {
                assertNull(PluginExclusionEncoding.mavenCsv(emptyList()))
            }

            test("returns the single glob unchanged") {
                assertEquals("src/test/**", PluginExclusionEncoding.mavenCsv(listOf("src/test/**")))
            }

            test("joins multiple globs with comma") {
                assertEquals(
                    "**/generated/**,**/*.md,src/test/**",
                    PluginExclusionEncoding.mavenCsv(
                        listOf("**/generated/**", "**/*.md", "src/test/**")
                    )
                )
            }
        }
    })
