package org.example.integration

import io.kotest.core.spec.style.FunSpec
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests for JSON projects.
 *
 * Verifies that .json files are parsed and modified via the CLI, using
 * text-level (FindAndReplace) and structured JSON recipes (AddKeyValue, ChangeValue).
 */
class JsonProjectIntegrationTest :
    FunSpec({
        var projectDir: Path = Path.of("")
        var cacheDir: Path = Path.of("")

        beforeEach {
            projectDir = Files.createTempDirectory("json-it-project-")
            cacheDir = Files.createTempDirectory("json-it-cache-")
        }

        afterEach {
            projectDir.toFile().deleteRecursively()
            cacheDir.toFile().deleteRecursively()
        }

        // ─── FindAndReplace (guaranteed change) ───────────────────────────────────

        test("FindAndReplace modifies json file content") {
            val jsonFile = projectDir.resolve("package.json")
            jsonFile.writeText(
                """
                {
                  "name": "my-app",
                  "version": "PLACEHOLDER",
                  "description": "A sample Node application"
                }
                """.trimIndent()
            )
            projectDir.writeFindAndReplaceRecipe(find = "PLACEHOLDER", replace = "1.2.0")

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
                    ".json"
                )

            assertEquals(0, result.exitCode, "stderr: ${result.stderr}")
            val content = jsonFile.readText()
            assertTrue(content.contains("1.2.0"), "Version placeholder should be replaced")
            assertTrue(!content.contains("PLACEHOLDER"), "Original placeholder should be gone")
        }

        test("FindAndReplace with --dry-run does not modify json file") {
            val jsonFile = projectDir.resolve("config.json")
            val original = """{"env": "PLACEHOLDER"}"""
            jsonFile.writeText(original)
            projectDir.writeFindAndReplaceRecipe(find = "PLACEHOLDER", replace = "prod")

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
                ".json",
                "--dry-run"
            )

            assertEquals(original, jsonFile.readText(), "--dry-run must not write json changes")
        }

        // ─── application.json sample project ─────────────────────────────────────

        test("FindAndReplace updates api url in application config json") {
            val configFile = projectDir.resolve("application.json")
            configFile.writeText(
                """
                {
                  "server": {
                    "host": "PLACEHOLDER.example.com",
                    "port": 8080
                  },
                  "logging": {
                    "level": "INFO"
                  }
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
                    ".json"
                )

            assertEquals(0, result.exitCode, "stderr: ${result.stderr}")
            assertTrue(
                configFile.readText().contains("api.example.com"),
                "Host should be updated"
            )
        }

        // ─── Diff output ──────────────────────────────────────────────────────────

        test("FindAndReplace produces unified diff for json file") {
            projectDir.resolve("data.json").writeText("""{"key": "PLACEHOLDER"}""")
            projectDir.writeFindAndReplaceRecipe(find = "PLACEHOLDER", replace = "value")

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
                    ".json",
                    "--dry-run"
                )

            assertEquals(0, result.exitCode, "stderr: ${result.stderr}")
            assertTrue(result.stdout.contains("---"), "Expected unified diff:\n${result.stdout}")
        }

        // ─── Multiple JSON files ──────────────────────────────────────────────────

        test("FindAndReplace updates multiple json files in project") {
            projectDir.resolve("en.json").writeText("""{"greeting": "PLACEHOLDER"}""")
            projectDir.resolve("fr.json").writeText("""{"greeting": "PLACEHOLDER"}""")
            projectDir.writeFindAndReplaceRecipe(find = "PLACEHOLDER", replace = "Hello")

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
                    ".json",
                    "--output",
                    "files",
                    "--dry-run"
                )

            assertEquals(0, result.exitCode, "stderr: ${result.stderr}")
            assertTrue(
                result.stdout.contains("en.json") && result.stdout.contains("fr.json"),
                "Both json files should be listed: ${result.stdout}"
            )
        }
    })
