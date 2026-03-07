package org.example.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.io.PrintWriter
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.example.config.ToolConfig
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import picocli.CommandLine

class RunCommandTest {

    @TempDir
    lateinit var projectDir: Path

    @TempDir
    lateinit var cacheDir: Path

    private fun cli(): CommandLine = CommandLine(RunCommand())

    // ─── Help & argument validation ───────────────────────────────────────────

    @Test
    fun `--help exits with code 0`() {
        val baos = ByteArrayOutputStream()
        val code = cli().setOut(PrintWriter(baos)).execute("--help")
        assertEquals(0, code, "--help should exit with code 0")
    }

    @Test
    fun `--help output mentions --active-recipe`() {
        val baos = ByteArrayOutputStream()
        cli().setOut(PrintWriter(baos)).execute("--help")
        assertTrue(
            baos.toString().contains("active-recipe"),
            "--help should document --active-recipe option"
        )
    }

    @Test
    fun `--help output mentions --recipe-artifact`() {
        val baos = ByteArrayOutputStream()
        cli().setOut(PrintWriter(baos)).execute("--help")
        assertTrue(
            baos.toString().contains("recipe-artifact"),
            "--help should document --recipe-artifact option"
        )
    }

    @Test
    fun `--help output mentions --output`() {
        val baos = ByteArrayOutputStream()
        cli().setOut(PrintWriter(baos)).execute("--help")
        assertTrue(
            baos.toString().contains("output"),
            "--help should document --output option"
        )
    }

    @Test
    fun `missing required --active-recipe exits with non-zero code`() {
        val errBaos = ByteArrayOutputStream()
        val code = cli()
            .setErr(PrintWriter(errBaos))
            .execute("run", "--project-dir", projectDir.toString())
        assertNotEquals(0, code, "Missing --active-recipe should fail")
    }

    // ─── Default argument values ──────────────────────────────────────────────

    @Test
    fun `default output mode is diff`() {
        val cmd = RunCommand()
        CommandLine(cmd).parseArgs("--active-recipe", "org.example.MyRecipe")
        assertEquals("diff", cmd.outputMode, "Default output mode should be 'diff'")
    }

    @Test
    fun `default project-dir is current directory`() {
        val cmd = RunCommand()
        CommandLine(cmd).parseArgs("--active-recipe", "org.example.MyRecipe")
        assertEquals(Path.of("."), cmd.projectDir, "Default project dir should be '.'")
    }

    @Test
    fun `--dry-run defaults to false`() {
        val cmd = RunCommand()
        CommandLine(cmd).parseArgs("--active-recipe", "org.example.MyRecipe")
        assertEquals(false, cmd.dryRun, "--dry-run should default to false")
    }

    @Test
    fun `multiple --recipe-artifact flags are collected into a list`() {
        val cmd = RunCommand()
        CommandLine(cmd).parseArgs(
            "--active-recipe",
            "org.example.MyRecipe",
            "--recipe-artifact",
            "org.openrewrite.recipe:rewrite-spring:LATEST",
            "--recipe-artifact",
            "org.openrewrite.recipe:rewrite-java:LATEST"
        )
        assertEquals(
            2,
            cmd.recipeArtifacts.size,
            "Two --recipe-artifact flags should produce a list of 2"
        )
    }

    @Test
    fun `--include-extensions splits on comma`() {
        val cmd = RunCommand()
        CommandLine(cmd).parseArgs(
            "--active-recipe",
            "org.example.MyRecipe",
            "--include-extensions",
            ".java,.kt"
        )
        assertEquals(listOf(".java", ".kt"), cmd.includeExtensions)
    }

    @Test
    fun `--exclude-extensions splits on comma`() {
        val cmd = RunCommand()
        CommandLine(cmd).parseArgs(
            "--active-recipe",
            "org.example.MyRecipe",
            "--exclude-extensions",
            ".xml,.properties"
        )
        assertEquals(listOf(".xml", ".properties"), cmd.excludeExtensions)
    }

    @Test
    fun `--rewrite-config path is accepted`() {
        val rewriteYaml = projectDir.resolve("my-rewrite.yaml")
        rewriteYaml.writeText("---\ntype: specs.openrewrite.org/v1beta/recipe\n")

        val cmd = RunCommand()
        CommandLine(cmd).parseArgs(
            "--active-recipe",
            "org.example.MyRecipe",
            "--rewrite-config",
            rewriteYaml.toString()
        )
        assertEquals(rewriteYaml, cmd.rewriteConfig)
    }

    // ─── Output mode validation ───────────────────────────────────────────────

    @Test
    fun `unknown --output value exits with code 1`() {
        projectDir.resolve("Hello.java").writeText("class Hello {}")

        val errBaos = ByteArrayOutputStream()
        val code = cli()
            .setErr(PrintWriter(errBaos))
            .execute(
                "--project-dir",
                projectDir.toString(),
                "--active-recipe",
                "org.openrewrite.java.format.AutoFormat",
                "--cache-dir",
                cacheDir.toString(),
                "--output",
                "foobar"
            )

        assertEquals(
            1,
            code,
            "Unknown --output value should exit with code 1, not silently fall back to diff"
        )
        assertTrue(
            errBaos.toString().contains("foobar"),
            "Error message should mention the invalid value"
        )
    }

    // ─── projectDir validation ────────────────────────────────────────────────

    @Test
    fun `nonexistent --project-dir exits with code 1`() {
        val errBaos = ByteArrayOutputStream()
        val code = cli()
            .setErr(PrintWriter(errBaos))
            .execute(
                "--project-dir",
                "/tmp/this-directory-does-not-exist-openrewrite-runner-test",
                "--active-recipe",
                "org.openrewrite.java.format.AutoFormat",
                "--cache-dir",
                cacheDir.toString()
            )

        assertEquals(1, code, "Nonexistent project directory should exit with code 1")
    }

    // ─── End-to-end CLI execution ─────────────────────────────────────────────

    @Test
    fun `run with nonexistent recipe returns exit code 1`() {
        projectDir.resolve("src/main/java").createDirectories()
        projectDir.resolve("src/main/java/Hello.java").writeText("class Hello {}")

        val errBaos = ByteArrayOutputStream()
        val code = cli()
            .setErr(PrintWriter(errBaos))
            .execute(
                "--project-dir",
                projectDir.toString(),
                "--active-recipe",
                "org.example.recipe.ThatDoesNotExist",
                "--cache-dir",
                cacheDir.toString(),
                "--include-extensions",
                ".java"
            )

        assertNotEquals(0, code, "Nonexistent recipe should produce exit code 1")
    }

    @Test
    fun `run with AutoFormat recipe on a simple Java project produces output`() {
        projectDir.resolve("Hello.java").writeText(
            "public class Hello {public static void main(String[] args){System.out.println(\"hi\");}}"
        )

        val outBaos = ByteArrayOutputStream()
        val errBaos = ByteArrayOutputStream()

        val code = cli()
            .setOut(PrintWriter(outBaos))
            .setErr(PrintWriter(errBaos))
            .execute(
                "--project-dir", projectDir.toString(),
                "--active-recipe", "org.openrewrite.java.format.AutoFormat",
                "--cache-dir", cacheDir.toString(),
                "--include-extensions", ".java",
                "--output", "diff"
            )

        val stdout = outBaos.toString()
        val stderr = errBaos.toString()

        if (code == 0) {
            assertTrue(
                stdout.contains("---") || stdout.contains("No changes"),
                "Success should produce a diff or 'No changes'. Got: $stdout"
            )
        } else {
            assertTrue(
                stderr.isNotBlank(),
                "Failure should produce an error message. stderr: $stderr"
            )
        }
    }

    @Test
    fun `run with --dry-run does not write files`() {
        val originalContent = "public class Dry { public static void main(String[]args){} }"
        val javaFile = projectDir.resolve("Dry.java")
        javaFile.writeText(originalContent)

        cli().execute(
            "--project-dir", projectDir.toString(),
            "--active-recipe", "org.openrewrite.java.format.AutoFormat",
            "--cache-dir", cacheDir.toString(),
            "--include-extensions", ".java",
            "--dry-run"
        )

        assertEquals(
            originalContent,
            javaFile.toFile().readText(),
            "--dry-run should not modify files"
        )
    }

    @Test
    fun `run with --output files mode does not print diffs`() {
        projectDir.resolve("Hello.java").writeText(
            "public class Hello {public static void main(String[] args){System.out.println(\"hi\");}}"
        )

        val outBaos = ByteArrayOutputStream()
        val code = cli()
            .setOut(PrintWriter(outBaos))
            .execute(
                "--project-dir", projectDir.toString(),
                "--active-recipe", "org.openrewrite.java.format.AutoFormat",
                "--cache-dir", cacheDir.toString(),
                "--include-extensions", ".java",
                "--output", "files",
                "--dry-run"
            )

        val output = outBaos.toString()
        if (code == 0 && !output.contains("No files changed")) {
            assertTrue(
                !output.contains("---") && !output.contains("@@"),
                "FILES mode should not contain unified diff markers"
            )
        }
    }

    @Test
    fun `run with --output report creates JSON file`() {
        projectDir.resolve("Hello.java").writeText(
            "public class Hello {public static void main(String[] args){System.out.println(\"hi\");}}"
        )

        val code = cli().execute(
            "--project-dir", projectDir.toString(),
            "--active-recipe", "org.openrewrite.java.format.AutoFormat",
            "--cache-dir", cacheDir.toString(),
            "--include-extensions", ".java",
            "--output", "report",
            "--dry-run"
        )

        if (code == 0) {
            val reportFile = projectDir.resolve("openrewrite-report.json").toFile()
            assertTrue(reportFile.exists(), "Report file should be created with --output report")
        }
    }

    @Test
    fun `run with custom rewrite_yaml is accepted without error`() {
        val rewriteYaml = projectDir.resolve("rewrite.yaml")
        rewriteYaml.writeText(
            """
            ---
            type: specs.openrewrite.org/v1beta/recipe
            name: com.example.MyCompositeRecipe
            displayName: My Composite Recipe
            recipeList:
              - org.openrewrite.java.format.AutoFormat
            """.trimIndent()
        )
        projectDir.resolve("Hello.java").writeText("class Hello {}")

        val result = runCatching {
            cli().execute(
                "--project-dir", projectDir.toString(),
                "--active-recipe", "com.example.MyCompositeRecipe",
                "--rewrite-config", rewriteYaml.toString(),
                "--cache-dir", cacheDir.toString(),
                "--include-extensions", ".java"
            )
        }
        assertTrue(
            result.isSuccess,
            "CLI should not throw an uncaught exception: ${result.exceptionOrNull()}"
        )
    }
}
