package org.example.cli

import org.example.config.ToolConfig
import org.example.lst.LstBuilder
import org.example.output.OutputMode
import org.example.output.ResultFormatter
import org.example.recipe.RecipeArtifactResolver
import org.example.recipe.RecipeLoader
import org.example.recipe.RecipeRunner
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
        try {
            // 1. Load tool config
            val toolConfig = ToolConfig.load(configFile)
            val effectiveCacheDir = (cacheDir ?: toolConfig.resolvedCacheDir()).also {
                it.toFile().mkdirs()
                log.info("Cache dir: $it")
            }

            // 2. Resolve recipe JARs
            val recipeJars = if (recipeArtifacts.isNotEmpty()) {
                log.info("Resolving ${recipeArtifacts.size} recipe artifact(s)…")
                val resolver = RecipeArtifactResolver(
                    cacheDir = effectiveCacheDir,
                    extraRepositories = toolConfig.resolvedRepositories(),
                )
                recipeArtifacts.flatMap { coord ->
                    log.info("  → $coord")
                    resolver.resolve(coord)
                }.distinct()
            } else {
                emptyList()
            }
            log.info("Recipe JARs resolved: ${recipeJars.size} JAR(s)")

            // 3. Load recipe
            val effectiveRewriteConfig = rewriteConfig ?: projectDir.resolve("rewrite.yaml")
            val recipe = RecipeLoader().load(
                recipeJars = recipeJars,
                activeRecipeName = activeRecipe,
                rewriteYaml = effectiveRewriteConfig,
            )
            log.info("Recipe loaded: ${recipe.name}")

            // 4. Build LST (3-stage pipeline)
            // OpenRewrite requires all source files in memory simultaneously to support
            // cross-file analysis. For large projects set -Xmx accordingly, e.g.:
            //   java -Xmx6g -jar openrewrite-runner-all.jar …
            log.info("Building LST for project: $projectDir")
            val lstBuilder = LstBuilder(
                cacheDir = effectiveCacheDir,
                toolConfig = toolConfig,
            )
            val sourceFiles = lstBuilder.build(
                projectDir = projectDir,
                parseConfig = toolConfig.parse,
                includeExtensionsCli = includeExtensions,
                excludeExtensionsCli = excludeExtensions,
            )
            log.info("Parsed ${sourceFiles.size} source file(s)")

            // 5. Run recipe
            val results = RecipeRunner().run(recipe, sourceFiles)
            log.info("Recipe produced ${results.size} result(s)")

            // 6. Apply changes (unless --dry-run)
            if (!dryRun) {
                for (result in results) {
                    val after = result.after ?: continue
                    val target = projectDir.resolve(after.sourcePath)
                    target.toFile().parentFile?.mkdirs()
                    target.toFile().writeText(after.printAll())
                }
                if (results.isNotEmpty()) {
                    log.info("Changes written to disk (${results.size} file(s) modified)")
                }
            } else if (results.isNotEmpty()) {
                log.info("Dry-run mode: ${results.size} file(s) would be modified")
            }

            // 7. Format output
            val mode = when (outputMode.lowercase()) {
                "diff" -> OutputMode.DIFF
                "files" -> OutputMode.FILES
                "report" -> OutputMode.REPORT
                else -> {
                    System.err.println("Unknown output mode '$outputMode'. Using 'diff'.")
                    OutputMode.DIFF
                }
            }
            ResultFormatter(mode, spec.commandLine().out).format(results, projectDir)

            return 0
        } catch (e: Exception) {
            System.err.println("ERROR: ${e.message}")
            log.severe("Unhandled exception: ${e.stackTraceToString()}")
            return 1
        }
    }
}
