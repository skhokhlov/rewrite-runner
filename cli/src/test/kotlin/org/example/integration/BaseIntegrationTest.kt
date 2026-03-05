package org.example.integration

import org.example.cli.RunCommand
import picocli.CommandLine
import java.io.ByteArrayOutputStream
import java.io.PrintWriter
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * Base class for CLI integration tests.
 *
 * Provides a [runCli] helper that captures stdout/stderr and returns the exit code,
 * mirroring how the OpenRewrite test framework separates "before" input from
 * "after" assertions — but at the CLI boundary instead of the recipe API boundary.
 */
abstract class BaseIntegrationTest {

    data class CliResult(val exitCode: Int, val stdout: String, val stderr: String)

    protected fun runCli(vararg args: String): CliResult {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val exitCode = CommandLine(RunCommand())
            .setOut(PrintWriter(out, true))
            .setErr(PrintWriter(err, true))
            .execute(*args)
        return CliResult(exitCode, out.toString(), err.toString())
    }

    /**
     * Writes a `FindAndReplace` composite recipe to `rewrite.yaml` in this directory.
     *
     * Uses a self-contained `trimIndent()` block so indentation is always correct
     * regardless of the surrounding call-site indentation level.
     */
    protected fun Path.writeFindAndReplaceRecipe(
        name: String = "com.example.integration.FindAndReplace",
        find: String,
        replace: String,
    ) {
        resolve("rewrite.yaml").writeText(
            """
            ---
            type: specs.openrewrite.org/v1beta/recipe
            name: $name
            recipeList:
              - org.openrewrite.text.FindAndReplace:
                  find: "$find"
                  replace: "$replace"
            """.trimIndent()
        )
    }

    /**
     * Writes a composite recipe to `rewrite.yaml` with arbitrary [body] content.
     *
     * [body] must be a YAML block sequence whose items start with `- ` at column 0
     * (call `.trimIndent()` on multi-line bodies before passing). The helper adds
     * 2 spaces of indentation so items align correctly under `recipeList:`.
     *
     * Example:
     * ```kotlin
     * projectDir.writeRewriteYaml("com.example.MyRecipe", """
     *     - org.openrewrite.properties.ChangePropertyValue:
     *         propertyKey: server.port
     *         newValue: "9090"
     * """.trimIndent())
     * ```
     */
    protected fun Path.writeRewriteYaml(name: String, body: String) {
        val indented = body.trimIndent().lines().joinToString("\n") { "  $it" }
        resolve("rewrite.yaml").writeText(
            "---\ntype: specs.openrewrite.org/v1beta/recipe\nname: $name\nrecipeList:\n$indented\n"
        )
    }
}
