package io.github.skhokhlov.rewriterunner.integration

import io.kotest.core.spec.style.FunSpec
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests for TOML projects.
 *
 * Verifies that `.toml` files (Cargo.toml, pyproject.toml, etc.) are correctly
 * parsed and modified by the CLI using the TomlParser.
 */
class TomlProjectIntegrationTest :
    FunSpec({
        var projectDir: Path = Path.of("")
        var cacheDir: Path = Path.of("")

        beforeEach {
            projectDir = Files.createTempDirectory("toml-it-project-")
            cacheDir = Files.createTempDirectory("toml-it-cache-")
        }

        afterEach {
            projectDir.toFile().deleteRecursively()
            cacheDir.toFile().deleteRecursively()
        }

        // ─── FindAndReplace (text-level, guaranteed change) ───────────────────────

        test("FindAndReplace modifies toml file content") {
            val tomlFile = projectDir.resolve("Cargo.toml")
            tomlFile.writeText(
                """
                [package]
                name = "my-app"
                version = "PLACEHOLDER"
                edition = "2021"
                """.trimIndent()
            )
            projectDir.writeFindAndReplaceRecipe(find = "PLACEHOLDER", replace = "1.0.0")

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
                    ".toml"
                )

            assertEquals(0, result.exitCode, "stderr: ${result.stderr}")
            val content = tomlFile.readText()
            assertTrue(content.contains("1.0.0"), "PLACEHOLDER should be replaced with 1.0.0")
            assertTrue(!content.contains("PLACEHOLDER"), "Original placeholder should be removed")
        }

        test("FindAndReplace with --dry-run does not modify toml file") {
            val tomlFile = projectDir.resolve("pyproject.toml")
            val original =
                "[tool.poetry]\nname = \"my-project\"\nversion = \"PLACEHOLDER\"\n"
            tomlFile.writeText(original)
            projectDir.writeFindAndReplaceRecipe(find = "PLACEHOLDER", replace = "0.1.0")

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
                ".toml",
                "--dry-run"
            )

            assertEquals(
                original,
                tomlFile.readText(),
                "--dry-run must not write toml changes to disk"
            )
        }

        // ─── Diff output ──────────────────────────────────────────────────────────

        test("FindAndReplace produces unified diff for toml file") {
            projectDir.resolve("Cargo.toml").writeText(
                "[package]\nname = \"my-crate\"\nversion = \"PLACEHOLDER\"\n"
            )
            projectDir.writeFindAndReplaceRecipe(find = "PLACEHOLDER", replace = "2.0.0")

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
                    ".toml",
                    "--dry-run"
                )

            assertEquals(0, result.exitCode, "stderr: ${result.stderr}")
            assertTrue(result.stdout.contains("---"), "Expected unified diff:\n${result.stdout}")
            assertTrue(
                result.stdout.contains("PLACEHOLDER"),
                "Diff should show removed text:\n${result.stdout}"
            )
            assertTrue(
                result.stdout.contains("2.0.0"),
                "Diff should show added text:\n${result.stdout}"
            )
        }

        // ─── Multiple TOML files ──────────────────────────────────────────────────

        test("FindAndReplace updates all toml files in project") {
            projectDir.resolve("Cargo.toml").writeText(
                "[package]\nversion = \"PLACEHOLDER\"\n"
            )
            projectDir.resolve("pyproject.toml").writeText(
                "[tool.poetry]\nversion = \"PLACEHOLDER\"\n"
            )
            projectDir.writeFindAndReplaceRecipe(find = "PLACEHOLDER", replace = "1.2.3")

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
                    ".toml",
                    "--output",
                    "files",
                    "--dry-run"
                )

            assertEquals(0, result.exitCode, "stderr: ${result.stderr}")
            assertTrue(
                result.stdout.contains("Cargo.toml") &&
                    result.stdout.contains("pyproject.toml"),
                "Both toml files should be reported as changed: ${result.stdout}"
            )
        }
    })
