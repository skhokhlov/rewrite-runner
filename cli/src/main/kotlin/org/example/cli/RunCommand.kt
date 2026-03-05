package org.example.cli

import org.example.OpenRewriteRunner
import org.example.output.OutputMode
import org.example.output.ResultFormatter
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Spec
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.logging.Logger

@Command(
    name = "run",
    description = ["Run an OpenRewrite recipe against a local project directory."],
    mixinStandardHelpOptions = true,
)
class RunCommand : Callable<Int> {
    private val log = Logger.getLogger(RunCommand::class.java.name)

    @Spec
    lateinit var spec: CommandLine.Model.CommandSpec

    @Option(
        names = ["--project-dir", "-p"],
        description = ["Path to the project directory to refactor. Defaults to current directory."],
        defaultValue = ".",
    )
    lateinit var projectDir: Path

    @Option(
        names = ["--active-recipe", "-r"],
        description = ["Fully-qualified name of the recipe to activate."],
        required = true,
    )
    lateinit var activeRecipe: String

    @Option(
        names = ["--recipe-artifact"],
        description = [
            "Maven coordinate of a recipe JAR to load (e.g. org.openrewrite.recipe:rewrite-spring:LATEST).",
            "May be specified multiple times.",
        ],
    )
    var recipeArtifacts: List<String> = emptyList()

    @Option(
        names = ["--rewrite-config"],
        description = ["Path to rewrite.yaml. Defaults to <project-dir>/rewrite.yaml."],
    )
    var rewriteConfig: Path? = null

    @Option(
        names = ["--output", "-o"],
        description = ["Output mode: diff (default), files, or report."],
        defaultValue = "diff",
    )
    lateinit var outputMode: String

    @Option(
        names = ["--cache-dir"],
        description = ["Directory for caching downloaded JARs. Defaults to ~/.openscript/cache."],
    )
    var cacheDir: Path? = null

    @Option(
        names = ["--config"],
        description = ["Path to tool config file (openrewrite-runner.yml)."],
    )
    var configFile: Path? = null

    @Option(
        names = ["--dry-run"],
        description = ["Parse and run recipe but do not write changes to disk."],
    )
    var dryRun: Boolean = false

    @Option(
        names = ["--include-extensions"],
        description = ["Comma-separated list of file extensions to include (e.g. .java,.kt)."],
        split = ",",
    )
    var includeExtensions: List<String> = emptyList()

    @Option(
        names = ["--exclude-extensions"],
        description = ["Comma-separated list of file extensions to exclude."],
        split = ",",
    )
    var excludeExtensions: List<String> = emptyList()

    override fun call(): Int {
        return try {
            val builder = OpenRewriteRunner.builder()
                .projectDir(projectDir)
                .activeRecipe(activeRecipe)
                .recipeArtifacts(recipeArtifacts)
                .dryRun(dryRun)
                .includeExtensions(includeExtensions)
                .excludeExtensions(excludeExtensions)
            rewriteConfig?.let { builder.rewriteConfig(it) }
            cacheDir?.let { builder.cacheDir(it) }
            configFile?.let { builder.configFile(it) }

            val runResult = builder.build().run()

            // Format output (CLI-only concern)
            val mode = when (outputMode.lowercase()) {
                "diff" -> OutputMode.DIFF
                "files" -> OutputMode.FILES
                "report" -> OutputMode.REPORT
                else -> {
                    System.err.println("Unknown output mode '$outputMode'. Using 'diff'.")
                    OutputMode.DIFF
                }
            }
            ResultFormatter(mode, spec.commandLine().out).format(runResult.results, runResult.projectDir)

            0
        } catch (e: Exception) {
            System.err.println("ERROR: ${e.message}")
            log.severe("Unhandled exception: ${e.stackTraceToString()}")
            1
        }
    }
}
