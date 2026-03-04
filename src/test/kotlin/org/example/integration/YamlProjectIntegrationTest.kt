package org.example.integration

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests for YAML projects.
 *
 * Verifies that .yaml and .yml files are correctly parsed and modified by the CLI,
 * using both text-level (FindAndReplace) and structured YAML recipes (MergeYaml).
 */
class YamlProjectIntegrationTest : BaseIntegrationTest() {

    @TempDir lateinit var projectDir: Path
    @TempDir lateinit var cacheDir: Path

    // ─── FindAndReplace (text-level, guaranteed change) ───────────────────────

    @Test
    fun `FindAndReplace modifies yaml file content`() {
        val yamlFile = projectDir.resolve("application.yaml")
        yamlFile.writeText(
            """
            server:
              port: PLACEHOLDER
            spring:
              application:
                name: my-app
            """.trimIndent()
        )
        projectDir.writeFindAndReplaceRecipe(find = "PLACEHOLDER", replace = "8080")

        val result = runCli(
            "--project-dir", projectDir.toString(),
            "--active-recipe", "com.example.integration.FindAndReplace",
            "--rewrite-config", projectDir.resolve("rewrite.yaml").toString(),
            "--cache-dir", cacheDir.toString(),
            "--include-extensions", ".yaml",
        )

        assertEquals(0, result.exitCode, "stderr: ${result.stderr}")
        val content = yamlFile.readText()
        assertTrue(content.contains("8080"), "PLACEHOLDER should be replaced with 8080")
        assertTrue(!content.contains("PLACEHOLDER"), "Original placeholder should be removed")
    }

    @Test
    fun `FindAndReplace processes yml extension as well as yaml`() {
        val ymlFile = projectDir.resolve("config.yml")
        ymlFile.writeText("database:\n  url: jdbc:postgresql://PLACEHOLDER:5432/mydb")
        projectDir.writeFindAndReplaceRecipe(find = "PLACEHOLDER", replace = "localhost")

        val result = runCli(
            "--project-dir", projectDir.toString(),
            "--active-recipe", "com.example.integration.FindAndReplace",
            "--rewrite-config", projectDir.resolve("rewrite.yaml").toString(),
            "--cache-dir", cacheDir.toString(),
            "--include-extensions", ".yml",
        )

        assertEquals(0, result.exitCode, "stderr: ${result.stderr}")
        assertTrue(ymlFile.readText().contains("localhost"), "PLACEHOLDER should be replaced in .yml file")
    }

    @Test
    fun `FindAndReplace with --dry-run does not modify yaml file`() {
        val yamlFile = projectDir.resolve("application.yaml")
        val original = "server:\n  port: PLACEHOLDER\n"
        yamlFile.writeText(original)
        projectDir.writeFindAndReplaceRecipe(find = "PLACEHOLDER", replace = "8080")

        runCli(
            "--project-dir", projectDir.toString(),
            "--active-recipe", "com.example.integration.FindAndReplace",
            "--rewrite-config", projectDir.resolve("rewrite.yaml").toString(),
            "--cache-dir", cacheDir.toString(),
            "--include-extensions", ".yaml",
            "--dry-run",
        )

        assertEquals(original, yamlFile.readText(), "--dry-run must not write yaml changes to disk")
    }

    // ─── Diff output ──────────────────────────────────────────────────────────

    @Test
    fun `FindAndReplace produces unified diff for yaml file`() {
        projectDir.resolve("application.yaml").writeText(
            "environment: PLACEHOLDER\nregion: us-east-1\n"
        )
        projectDir.writeFindAndReplaceRecipe(find = "PLACEHOLDER", replace = "production")

        val result = runCli(
            "--project-dir", projectDir.toString(),
            "--active-recipe", "com.example.integration.FindAndReplace",
            "--rewrite-config", projectDir.resolve("rewrite.yaml").toString(),
            "--cache-dir", cacheDir.toString(),
            "--include-extensions", ".yaml",
            "--dry-run",
        )

        assertEquals(0, result.exitCode, "stderr: ${result.stderr}")
        assertTrue(result.stdout.contains("---"), "Expected unified diff:\n${result.stdout}")
        assertTrue(result.stdout.contains("PLACEHOLDER"), "Diff should show removed text:\n${result.stdout}")
        assertTrue(result.stdout.contains("production"), "Diff should show added text:\n${result.stdout}")
    }

    // ─── MergeYaml (structured YAML recipe) ───────────────────────────────────

    @Test
    fun `MergeYaml adds missing keys to yaml file`() {
        val yamlFile = projectDir.resolve("application.yaml")
        yamlFile.writeText("server:\n  port: 8080\n")
        projectDir.writeRewriteYaml(
            "com.example.integration.AddManagementPort",
            """
              - org.openrewrite.yaml.MergeYaml:
                  key: ${'$'}
                  yaml: |
                    management:
                      server:
                        port: 8090
            """.trimIndent()
        )

        val result = runCli(
            "--project-dir", projectDir.toString(),
            "--active-recipe", "com.example.integration.AddManagementPort",
            "--rewrite-config", projectDir.resolve("rewrite.yaml").toString(),
            "--cache-dir", cacheDir.toString(),
            "--include-extensions", ".yaml",
        )

        assertEquals(0, result.exitCode, "stderr: ${result.stderr}")
        val content = yamlFile.readText()
        assertTrue(content.contains("management"), "MergeYaml should add the management block")
        assertTrue(content.contains("8090"), "Merged port value should appear in file")
    }

    // ─── Multiple YAML files ──────────────────────────────────────────────────

    @Test
    fun `FindAndReplace updates all yaml files in project`() {
        projectDir.resolve("application.yaml").writeText("profile: PLACEHOLDER\n")
        projectDir.resolve("application-dev.yaml").writeText("profile: PLACEHOLDER\ndebug: true\n")
        projectDir.writeFindAndReplaceRecipe(find = "PLACEHOLDER", replace = "default")

        val result = runCli(
            "--project-dir", projectDir.toString(),
            "--active-recipe", "com.example.integration.FindAndReplace",
            "--rewrite-config", projectDir.resolve("rewrite.yaml").toString(),
            "--cache-dir", cacheDir.toString(),
            "--include-extensions", ".yaml",
            "--output", "files",
            "--dry-run",
        )

        assertEquals(0, result.exitCode, "stderr: ${result.stderr}")
        assertTrue(
            result.stdout.contains("application.yaml") && result.stdout.contains("application-dev.yaml"),
            "Both yaml files should be reported as changed: ${result.stdout}"
        )
    }
}
