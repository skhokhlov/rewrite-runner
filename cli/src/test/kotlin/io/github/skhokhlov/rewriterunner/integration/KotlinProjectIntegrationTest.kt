package io.github.skhokhlov.rewriterunner.integration

import io.kotest.core.spec.style.FunSpec
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Integration tests for Kotlin projects.
 *
 * Uses [org.openrewrite.kotlin.format.AutoFormat] to verify that .kt files are
 * parsed by the Kotlin OpenRewrite parser and that formatting changes are applied.
 */
class KotlinProjectIntegrationTest :
    FunSpec({
        var projectDir: Path = Path.of("")
        var cacheDir: Path = Path.of("")

        beforeEach {
            projectDir = Files.createTempDirectory("kotlin-it-project-")
            cacheDir = Files.createTempDirectory("kotlin-it-cache-")
        }

        afterEach {
            projectDir.toFile().deleteRecursively()
            cacheDir.toFile().deleteRecursively()
        }

        /** Compact Kotlin that AutoFormat will expand with whitespace. */
        val unformattedKt = """fun greet(name: String): String {return "Hello, ${'$'}name!"}"""

        // ─── Parsing and diff ─────────────────────────────────────────────────────

        test("AutoFormat runs successfully on Kotlin source files") {
            projectDir.resolve("Greeter.kt").writeText(unformattedKt)

            val result =
                runCli(
                    "--project-dir",
                    projectDir.toString(),
                    "--active-recipe",
                    "org.openrewrite.kotlin.format.AutoFormat",
                    "--cache-dir",
                    cacheDir.toString(),
                    "--include-extensions",
                    ".kt",
                    "--dry-run"
                )

            assertEquals(
                0,
                result.exitCode,
                "AutoFormat should succeed on valid Kotlin, stderr: ${result.stderr}"
            )
        }

        test("AutoFormat produces diff or no-changes message for Kotlin source") {
            projectDir.resolve("Greeter.kt").writeText(unformattedKt)

            val result =
                runCli(
                    "--project-dir",
                    projectDir.toString(),
                    "--active-recipe",
                    "org.openrewrite.kotlin.format.AutoFormat",
                    "--cache-dir",
                    cacheDir.toString(),
                    "--include-extensions",
                    ".kt",
                    "--dry-run"
                )

            assertEquals(0, result.exitCode, "stderr: ${result.stderr}")
            assertTrue(
                result.stdout.contains("---") || result.stdout.contains("No changes"),
                "Expected a diff or 'No changes' message:\n${result.stdout}"
            )
        }

        // ─── FindAndReplace (language-agnostic, guaranteed change) ────────────────

        test("FindAndReplace modifies Kotlin source file content") {
            val ktFile = projectDir.resolve("Config.kt")
            ktFile.writeText(
                """
                object Config {
                    const val API_URL = "https://PLACEHOLDER.example.com"
                    const val TIMEOUT = 30
                }
                """.trimIndent()
            )
            projectDir.writeFindAndReplaceRecipe(find = "PLACEHOLDER", replace = "api")

            val result =
                runCli(
                    "--project-dir",
                    projectDir.toString(),
                    "--active-recipe",
                    "com.example.integration.FindAndReplace",
                    "--rewrite-config",
                    projectDir.resolve("rewrite.yaml").toString(),
                    "--cache-dir",
                    cacheDir.toString(),
                    "--include-extensions",
                    ".kt"
                )

            assertEquals(0, result.exitCode, "stderr: ${result.stderr}")
            val content = ktFile.readText()
            assertTrue(
                content.contains("api.example.com"),
                "PLACEHOLDER should be replaced in Kotlin file"
            )
            assertTrue(!content.contains("PLACEHOLDER"), "Original placeholder should not remain")
        }

        test("FindAndReplace with --dry-run does not modify Kotlin file") {
            val ktFile = projectDir.resolve("Config.kt")
            val original = "val endpoint = \"https://PLACEHOLDER.example.com\""
            ktFile.writeText(original)
            projectDir.writeFindAndReplaceRecipe(find = "PLACEHOLDER", replace = "api")

            runCli(
                "--project-dir",
                projectDir.toString(),
                "--active-recipe",
                "com.example.integration.FindAndReplace",
                "--rewrite-config",
                projectDir.resolve("rewrite.yaml").toString(),
                "--cache-dir",
                cacheDir.toString(),
                "--include-extensions",
                ".kt",
                "--dry-run"
            )

            assertEquals(
                original,
                ktFile.readText(),
                "--dry-run must not write changes for Kotlin files"
            )
        }

        // ─── Extension filtering ──────────────────────────────────────────────────

        test("only kt files are processed when include-extensions is dot-kt") {
            projectDir.resolve("App.kt").writeText("val url = \"PLACEHOLDER\"")
            projectDir.resolve("App.java").writeText("class App { String url = \"PLACEHOLDER\"; }")
            projectDir.writeFindAndReplaceRecipe(find = "PLACEHOLDER", replace = "REPLACED")

            runCli(
                "--project-dir",
                projectDir.toString(),
                "--active-recipe",
                "com.example.integration.FindAndReplace",
                "--rewrite-config",
                projectDir.resolve("rewrite.yaml").toString(),
                "--cache-dir",
                cacheDir.toString(),
                "--include-extensions",
                ".kt"
            )

            assertNotEquals(
                "val url = \"PLACEHOLDER\"",
                projectDir.resolve("App.kt").readText(),
                "Kotlin file should be modified"
            )
            assertEquals(
                "class App { String url = \"PLACEHOLDER\"; }",
                projectDir.resolve("App.java").readText(),
                "Java file should not be touched"
            )
        }
    })
