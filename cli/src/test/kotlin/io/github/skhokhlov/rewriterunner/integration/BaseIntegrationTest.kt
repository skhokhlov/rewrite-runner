package io.github.skhokhlov.rewriterunner.integration

import io.github.skhokhlov.rewriterunner.cli.RunCommand
import java.io.ByteArrayOutputStream
import java.io.PrintWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.writeText
import picocli.CommandLine

val isWindows: Boolean = System.getProperty("os.name", "").lowercase().contains("windows")

// Kotlin raw strings interpolate $identifier; embed a literal `$` for shell vars via `${D}`.
const val D = "$"

val posixExecutable: Set<PosixFilePermission> =
    setOf(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.OWNER_EXECUTE
    )

/**
 * Shared helpers for CLI integration tests.
 *
 * [runCli] captures stdout/stderr and returns the exit code, mirroring how the
 * OpenRewrite test framework separates "before" input from "after" assertions —
 * but at the CLI boundary instead of the recipe API boundary.
 *
 * [Path.writeFindAndReplaceRecipe] and [Path.writeRewriteYaml] write composite
 * recipe YAML files into a project directory for use in tests.
 */

data class CliResult(val exitCode: Int, val stdout: String, val stderr: String)

fun runCli(vararg args: String): CliResult {
    val out = ByteArrayOutputStream()
    val err = ByteArrayOutputStream()
    val exitCode =
        CommandLine(RunCommand())
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
fun Path.writeFindAndReplaceRecipe(
    name: String = "com.example.integration.FindAndReplace",
    find: String,
    replace: String
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
fun Path.writeRewriteYaml(name: String, body: String) {
    val indented = body.trimIndent().lines().joinToString("\n") { "  $it" }
    resolve("rewrite.yaml").writeText(
        "---\ntype: specs.openrewrite.org/v1beta/recipe\nname: $name\nrecipeList:\n$indented\n"
    )
}

/**
 * Writes a fake `gradlew` that simulates the OpenRewrite Gradle plugin for a single-line change.
 *
 * On `rewriteDryRun` → writes a unified-diff patch at `build/reports/rewrite/rewrite.patch`.
 * On `rewriteRun` → overwrites [targetFile] with [newContent].
 * Every invocation appends the task name (`$1`) to `wrapper-calls.log`.
 * Exits 1 for any other argument.
 *
 * [oldLine] / [newLine] are the single changed lines in the diff (no leading `-`/`+`).
 * [newContent] is the file content to write on `rewriteRun`; newlines are converted to `\n`
 * escape sequences for the shell `printf` call.
 */
fun Path.writeFakeGradlew(
    targetFile: String,
    oldLine: String,
    newLine: String,
    newContent: String
) {
    val printfContent = newContent.replace("\n", "\\n")
    val gradlew = resolve("gradlew")
    gradlew.writeText(
        """
        #!/bin/sh
        LOG="$D(cd "$D(dirname "${D}0")" && pwd)/wrapper-calls.log"
        if [ "${D}1" = "rewriteDryRun" ]; then
          echo "${D}1" >> "${D}LOG"
          mkdir -p build/reports/rewrite
          cat > build/reports/rewrite/rewrite.patch <<'PATCH'
        diff --git a/$targetFile b/$targetFile
        --- a/$targetFile
        +++ b/$targetFile
        @@ -1 +1 @@
        -$oldLine
        +$newLine
        PATCH
          exit 0
        fi
        if [ "${D}1" = "rewriteRun" ]; then
          echo "${D}1" >> "${D}LOG"
          printf '$printfContent' > $targetFile
          exit 0
        fi
        exit 1
        """.trimIndent()
    )
    Files.setPosixFilePermissions(gradlew, posixExecutable)
}

/**
 * Writes a simplified fake `mvnw` for non-JVM plugin-first tests.
 *
 * Detects the goal suffix (`:dryRun` / `:run`), logs `dryRun` or `run` to `wrapper-calls.log`,
 * extracts `-DreportOutputDirectory=<dir>`, and on `dryRun` writes a unified-diff patch there;
 * on `run` overwrites [targetFile] with [newContent].
 *
 * Does **not** validate Maven flag details — see `PluginFirstIntegrationTest.writeFakeMvnw` for
 * that level of protocol verification.
 */
fun Path.writeFakeMvnwSimple(
    targetFile: String,
    oldLine: String,
    newLine: String,
    newContent: String
) {
    val printfContent = newContent.replace("\n", "\\n")
    val mvnw = resolve("mvnw")
    mvnw.writeText(
        """
        #!/bin/sh
        LOG="$D(cd "$D(dirname "${D}0")" && pwd)/wrapper-calls.log"

        goal=""
        report_dir=""

        for arg in "$D@"; do
          case "${D}arg" in
            *:dryRun) goal=dryRun ;;
            *:run)    goal=run ;;
            -DreportOutputDirectory=*) report_dir="$D{arg#-DreportOutputDirectory=}" ;;
          esac
        done

        if [ -n "${D}goal" ]; then
          echo "${D}goal" >> "${D}LOG"
        fi

        if [ "${D}goal" = "dryRun" ] && [ -n "${D}report_dir" ]; then
          mkdir -p "${D}report_dir"
          cat > "${D}report_dir/rewrite.patch" <<'PATCH'
        diff --git a/$targetFile b/$targetFile
        --- a/$targetFile
        +++ b/$targetFile
        @@ -1 +1 @@
        -$oldLine
        +$newLine
        PATCH
          exit 0
        fi

        if [ "${D}goal" = "run" ]; then
          printf '$printfContent' > $targetFile
          exit 0
        fi

        exit 1
        """.trimIndent()
    )
    Files.setPosixFilePermissions(mvnw, posixExecutable)
}

/** Retained for source compatibility; all helpers are now top-level functions. */
abstract class BaseIntegrationTest
