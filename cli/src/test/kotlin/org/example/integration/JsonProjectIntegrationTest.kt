package org.example.integration

import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Integration tests for JSON projects.
 *
 * Verifies that .json files are parsed and modified via the CLI, using
 * text-level (FindAndReplace) and structured JSON recipes (AddKeyValue, ChangeValue).
 */
class JsonProjectIntegrationTest : BaseIntegrationTest() {

    @TempDir lateinit var projectDir: Path

    @TempDir lateinit var cacheDir: Path

    // ─── FindAndReplace (guaranteed change) ───────────────────────────────────

    @Test
    fun `FindAndReplace modifies json file content`() {
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

        val result = runCli(
            "--project-dir", projectDir.toString(),
            "--active-recipe", "com.example.integration.FindAndReplace",
            "--rewrite-config", projectDir.resolve("rewrite.yaml").toString(),
            "--cache-dir", cacheDir.toString(),
            "--include-extensions", ".json"
        )

        assertEquals(0, result.exitCode, "stderr: ${result.stderr}")
        val content = jsonFile.readText()
        assertTrue(content.contains("1.2.0"), "Version placeholder should be replaced")
        assertTrue(!content.contains("PLACEHOLDER"), "Original placeholder should be gone")
    }

    @Test
    fun `FindAndReplace with --dry-run does not modify json file`() {
        val jsonFile = projectDir.resolve("config.json")
        val original = """{"env": "PLACEHOLDER"}"""
        jsonFile.writeText(original)
        projectDir.writeFindAndReplaceRecipe(find = "PLACEHOLDER", replace = "prod")

        runCli(
            "--project-dir", projectDir.toString(),
            "--active-recipe", "com.example.integration.FindAndReplace",
            "--rewrite-config", projectDir.resolve("rewrite.yaml").toString(),
            "--cache-dir", cacheDir.toString(),
            "--include-extensions", ".json",
            "--dry-run"
        )

        assertEquals(original, jsonFile.readText(), "--dry-run must not write json changes")
    }

    // ─── application.json sample project ─────────────────────────────────────

    @Test
    fun `FindAndReplace updates api url in application config json`() {
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

        val result = runCli(
            "--project-dir", projectDir.toString(),
            "--active-recipe", "com.example.integration.FindAndReplace",
            "--rewrite-config", projectDir.resolve("rewrite.yaml").toString(),
            "--cache-dir", cacheDir.toString(),
            "--include-extensions", ".json"
        )

        assertEquals(0, result.exitCode, "stderr: ${result.stderr}")
        assertTrue(configFile.readText().contains("api.example.com"), "Host should be updated")
    }

    // ─── Diff output ──────────────────────────────────────────────────────────

    @Test
    fun `FindAndReplace produces unified diff for json file`() {
        projectDir.resolve("data.json").writeText("""{"key": "PLACEHOLDER"}""")
        projectDir.writeFindAndReplaceRecipe(find = "PLACEHOLDER", replace = "value")

        val result = runCli(
            "--project-dir", projectDir.toString(),
            "--active-recipe", "com.example.integration.FindAndReplace",
            "--rewrite-config", projectDir.resolve("rewrite.yaml").toString(),
            "--cache-dir", cacheDir.toString(),
            "--include-extensions", ".json",
            "--dry-run"
        )

        assertEquals(0, result.exitCode, "stderr: ${result.stderr}")
        assertTrue(result.stdout.contains("---"), "Expected unified diff:\n${result.stdout}")
    }

    // ─── Multiple JSON files ──────────────────────────────────────────────────

    @Test
    fun `FindAndReplace updates multiple json files in project`() {
        projectDir.resolve("en.json").writeText("""{"greeting": "PLACEHOLDER"}""")
        projectDir.resolve("fr.json").writeText("""{"greeting": "PLACEHOLDER"}""")
        projectDir.writeFindAndReplaceRecipe(find = "PLACEHOLDER", replace = "Hello")

        val result = runCli(
            "--project-dir", projectDir.toString(),
            "--active-recipe", "com.example.integration.FindAndReplace",
            "--rewrite-config", projectDir.resolve("rewrite.yaml").toString(),
            "--cache-dir", cacheDir.toString(),
            "--include-extensions", ".json",
            "--output", "files",
            "--dry-run"
        )

        assertEquals(0, result.exitCode, "stderr: ${result.stderr}")
        assertTrue(
            result.stdout.contains("en.json") && result.stdout.contains("fr.json"),
            "Both json files should be listed: ${result.stdout}"
        )
    }
}
