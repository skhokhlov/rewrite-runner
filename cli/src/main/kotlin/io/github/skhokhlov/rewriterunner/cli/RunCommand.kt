package io.github.skhokhlov.rewriterunner.cli

import io.github.skhokhlov.rewriterunner.RewriteRunner
import io.github.skhokhlov.rewriterunner.config.DurationParser
import io.github.skhokhlov.rewriterunner.output.OutputMode
import io.github.skhokhlov.rewriterunner.output.ResultFormatter
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Callable
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.ITypeConverter
import picocli.CommandLine.Option
import picocli.CommandLine.Spec

@Command(
    name = "run",
    description = ["Run an OpenRewrite recipe against a local project directory."],
    mixinStandardHelpOptions = true
)
class RunCommand : Callable<Int> {
    @Spec
    lateinit var spec: CommandLine.Model.CommandSpec

    @Option(
        names = ["--project-dir", "-p"],
        description = ["Path to the project directory to refactor. Defaults to current directory."],
        defaultValue = "."
    )
    lateinit var projectDir: Path

    @Option(
        names = ["--active-recipe", "-r"],
        description = ["Fully-qualified name of the recipe to activate."],
        required = true
    )
    lateinit var activeRecipe: String

    @Option(
        names = ["--recipe-artifact"],
        description = [
            "Maven coordinate of a recipe JAR to load (e.g. org.openrewrite.recipe:rewrite-spring:LATEST).",
            "May be specified multiple times."
        ]
    )
    var recipeArtifacts: List<String> = emptyList()

    @Option(
        names = ["--rewrite-config"],
        description = ["Path to rewrite.yaml. Defaults to <project-dir>/rewrite.yaml."]
    )
    var rewriteConfig: Path? = null

    @Option(
        names = ["--output", "-o"],
        description = ["Output mode: diff (default), files, or report."],
        defaultValue = "DIFF",
        converter = [OutputModeConverter::class]
    )
    lateinit var outputMode: OutputMode

    @Option(
        names = ["--cache-dir"],
        description = ["Directory for caching downloaded JARs. Defaults to ~/.rewriterunner/cache."]
    )
    var cacheDir: Path? = null

    @Option(
        names = ["--config"],
        description = [
            "Path to tool config file (rewriterunner.yml).",
            "Defaults: <project-dir>/rewriterunner.yml, then ~/.rewriterunner/rewriterunner.yml."
        ]
    )
    var configFile: Path? = null

    @Option(
        names = ["--dry-run"],
        description = ["Parse and run recipe but do not write changes to disk."]
    )
    var dryRun: Boolean = false

    @Option(
        names = ["--skip-plugin-run"],
        description = ["Skip plugin-first execution; use full LST pipeline directly."]
    )
    var skipPluginRun: Boolean = false

    @Option(
        names = ["--info"],
        description = ["Enable INFO-level logging (default)."]
    )
    var infoLogging: Boolean = false

    @Option(
        names = ["--debug"],
        description = ["Enable DEBUG-level logging for verbose output."]
    )
    var debugLogging: Boolean = false

    @Option(
        names = ["--exclude-paths"],
        description = [
            "Comma-separated glob patterns of files to skip (e.g. '**/generated/**,**/*.md').",
            "Forwarded to the OpenRewrite Gradle/Maven plugin and to the LST fallback pipeline."
        ],
        split = ","
    )
    var excludePaths: List<String> = emptyList()

    @Option(
        names = ["--no-maven-central"],
        description = ["Disable Maven Central; use only repositories from config."]
    )
    var noMavenCentral: Boolean = false

    @Option(
        names = ["--artifact-download-threads", "--download-threads"],
        description = ["Number of parallel artifact download threads. Defaults to 5."]
    )
    var downloadThreads: Int? = null

    @Option(
        names = ["--subprocess-run-timeout"],
        description = [
            "Timeout for build-tool subprocesses in the fallback LST pipeline.",
            "Use values like 120s, 10m, or PT2M. Defaults to 120s."
        ],
        converter = [DurationConverter::class]
    )
    var processTimeout: Duration? = null

    @Option(
        names = ["--plugin-run-timeout"],
        description = [
            "Timeout for plugin-first Gradle/Maven invocations.",
            "Use values like 10m, 600s, or PT10M. Defaults to 10m."
        ],
        converter = [DurationConverter::class]
    )
    var pluginTimeout: Duration? = null

    @Option(
        names = ["--artifact-resolver-connect-timeout", "--resolver-connect-timeout"],
        description = [
            "TCP connection timeout for Maven Resolver downloads.",
            "Use values like 30s, 30000ms, or PT30S. Defaults to 30s."
        ],
        converter = [DurationConverter::class]
    )
    var resolverConnectTimeout: Duration? = null

    @Option(
        names = ["--artifact-resolver-request-timeout", "--resolver-request-timeout"],
        description = [
            "Socket read/request timeout for Maven Resolver downloads.",
            "Use values like 60s, 1m, or PT1M. Defaults to 60s."
        ],
        converter = [DurationConverter::class]
    )
    var resolverRequestTimeout: Duration? = null

    override fun call(): Int {
        val logger =
            LogbackRunnerLogger(showInfo = infoLogging || debugLogging, showDebug = debugLogging)

        return try {
            val builder = RewriteRunner.builder()
                .projectDir(projectDir)
                .activeRecipe(activeRecipe)
                .recipeArtifacts(recipeArtifacts)
                .dryRun(dryRun)
                .skipPluginRun(skipPluginRun)
                .excludePaths(excludePaths)
                .logger(logger)
            rewriteConfig?.let { builder.rewriteConfig(it) }
            cacheDir?.let { builder.cacheDir(it) }
            configFile?.let { builder.configFile(it) }
            if (noMavenCentral) builder.includeMavenCentral(false)
            downloadThreads?.let { builder.artifactDownloadThreads(it) }
            processTimeout?.let { builder.subprocessRunTimeout(it) }
            pluginTimeout?.let { builder.pluginRunTimeout(it) }
            resolverConnectTimeout?.let { builder.artifactResolverConnectTimeout(it) }
            resolverRequestTimeout?.let { builder.artifactResolverRequestTimeout(it) }

            val runResult = builder.build().run()

            ResultFormatter(
                outputMode,
                spec.commandLine().out
            ).format(runResult)

            0
        } catch (e: IllegalArgumentException) {
            // User-configuration errors (wrong recipe name, missing artifact, bad project path, …).
            // Show a plain one-line message — stack traces belong in debug logs, not on stderr.
            spec.commandLine().err.println("ERROR: ${e.message ?: e.javaClass.simpleName}")
            spec.commandLine().err.flush()
            1
        } catch (e: Throwable) {
            spec.commandLine().err.println("ERROR: ${e.message ?: e.javaClass.simpleName}")
            spec.commandLine().err.flush()
            logger.error("Unhandled exception: ${e.stackTraceToString()}")
            1
        }
    }
}

/** Case-insensitive converter so both "diff" and "DIFF" are accepted on the CLI. */
private class OutputModeConverter : ITypeConverter<OutputMode> {
    override fun convert(value: String): OutputMode =
        OutputMode.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
            ?: throw CommandLine.TypeConversionException(
                "Unknown output mode '$value'. Valid values: " +
                    OutputMode.entries.joinToString(", ") { it.name.lowercase() }
            )
}

private class DurationConverter : ITypeConverter<Duration> {
    override fun convert(value: String): Duration = try {
        DurationParser.parse(value)
    } catch (e: IllegalArgumentException) {
        throw CommandLine.TypeConversionException(e.message)
    }
}
