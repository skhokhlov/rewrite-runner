package io.github.skhokhlov.rewriterunner

import io.github.skhokhlov.rewriterunner.apply.ChangeWriter
import io.github.skhokhlov.rewriterunner.config.RepositoryConfig
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration

/**
 * Programmatic entry point for running OpenRewrite recipes against a project directory.
 *
 * By default this class is a coordinator: it attempts the official Gradle/Maven plugin and runs
 * any required LST work in a short-lived worker JVM. Select [ExecutionMode.IN_PROCESS] only when
 * a caller needs rich OpenRewrite Results or an in-memory/custom [ChangeWriter].
 */
class RewriteRunner private constructor(private val config: Builder) {
    /** Execute the configured recipe and return diffs, write outcomes, and executor diagnostics. */
    fun run(): RunResult = RunCoordinator(config).run()

    /** Builder for [RewriteRunner]. */
    class Builder {
        internal var projectDir: Path = Paths.get(".")
            private set
        internal var activeRecipe: String = ""
            private set
        internal var recipeArtifacts: List<String> = emptyList()
            private set
        internal var rewriteConfig: Path? = null
            private set
        internal var rewriteConfigContent: String? = null
            private set
        internal var cacheDir: Path? = null
            private set
        internal var configFile: Path? = null
            private set
        internal var dryRun: Boolean = false
            private set
        internal var skipPluginRun: Boolean = false
            private set
        internal var includeMavenCentral: Boolean? = null
            private set
        internal var artifactDownloadThreads: Int? = null
            private set
        internal var subprocessRunTimeout: Duration? = null
            private set
        internal var pluginRunTimeout: Duration? = null
            private set
        internal var artifactResolverConnectTimeout: Duration? = null
            private set
        internal var artifactResolverRequestTimeout: Duration? = null
            private set
        internal var artifactRepositories: List<RepositoryConfig> = emptyList()
            private set
        internal var excludePaths: List<String> = emptyList()
            private set
        internal var plainTextMasks: List<String> = emptyList()
            private set
        internal var executionMode: ExecutionMode? = null
            private set
        internal var executorJvmArgs: List<String>? = null
            private set
        internal var pluginExecutorJvmArgs: List<String>? = null
            private set
        internal var lstWorkerJvmArgs: List<String>? = null
            private set
        internal var lstWorkerTimeout: Duration? = null
            private set
        internal var workerCommandFactory: WorkerCommandFactory? = null
            private set
        internal var logger: RunnerLogger = NoOpRunnerLogger
            private set
        internal var changeWriter: ChangeWriter? = null
            private set

        /** Root directory of the project to analyse. Defaults to the current directory. */
        fun projectDir(path: Path): Builder = apply { projectDir = path }

        /** Fully qualified OpenRewrite recipe name. Required before [build]. */
        fun activeRecipe(name: String): Builder = apply { activeRecipe = name }

        /** Add one Maven recipe artifact coordinate. */
        fun recipeArtifact(coordinate: String): Builder = apply {
            recipeArtifacts = recipeArtifacts + coordinate
        }

        /** Replace all recipe artifact coordinates. */
        fun recipeArtifacts(coordinates: List<String>): Builder = apply {
            recipeArtifacts =
                coordinates
        }

        /** Explicit rewrite.yaml path. */
        fun rewriteConfig(path: Path): Builder = apply { rewriteConfig = path }

        /** Inline rewrite.yaml content, which takes precedence over [rewriteConfig]. */
        fun rewriteConfigContent(content: String): Builder =
            apply { rewriteConfigContent = content }

        /** Cache root for recipe artifacts. */
        fun cacheDir(path: Path): Builder = apply { cacheDir = path }

        /** Explicit rewriterunner.yml path. */
        fun configFile(path: Path): Builder = apply { configFile = path }

        /** Run recipes without writing project files. */
        fun dryRun(value: Boolean): Builder = apply { dryRun = value }

        /** Bypass the official plugin attempt and use the selected LST execution mode directly. */
        fun skipPluginRun(value: Boolean): Builder = apply { skipPluginRun = value }

        /** Number of parallel artifact download threads. */
        fun artifactDownloadThreads(n: Int): Builder = apply { artifactDownloadThreads = n }

        /** Timeout for build-tool subprocesses used while resolving an LST classpath. */
        fun subprocessRunTimeout(timeout: Duration): Builder = apply {
            subprocessRunTimeout =
                timeout
        }

        /** Timeout for the official Gradle/Maven plugin invocation. */
        fun pluginRunTimeout(timeout: Duration): Builder = apply { pluginRunTimeout = timeout }

        /** JVM arguments shared by runner-owned plugin and worker executors. */
        fun executorJvmArgs(args: List<String>): Builder = apply { executorJvmArgs = args }

        /** JVM arguments appended for the official Gradle/Maven plugin executor. */
        fun pluginExecutorJvmArgs(args: List<String>): Builder = apply {
            pluginExecutorJvmArgs = args
        }

        /** JVM arguments appended for the LST worker executor. */
        fun lstWorkerJvmArgs(args: List<String>): Builder = apply { lstWorkerJvmArgs = args }

        /** Optional timeout for the complete LST worker process. `null` is unlimited. */
        fun lstWorkerTimeout(timeout: Duration?): Builder = apply { lstWorkerTimeout = timeout }

        /** Select [ExecutionMode.FORKED] (default) or explicit [ExecutionMode.IN_PROCESS]. */
        fun executionMode(mode: ExecutionMode): Builder = apply { executionMode = mode }

        /** Advanced structured override for the LST worker launcher. */
        fun workerCommandFactory(factory: WorkerCommandFactory): Builder = apply {
            workerCommandFactory = factory
        }

        /** TCP connection timeout for Maven Resolver artifact downloads. */
        fun artifactResolverConnectTimeout(timeout: Duration): Builder = apply {
            artifactResolverConnectTimeout = timeout
        }

        /** Socket read/request timeout for Maven Resolver artifact downloads. */
        fun artifactResolverRequestTimeout(timeout: Duration): Builder = apply {
            artifactResolverRequestTimeout = timeout
        }

        /** Override whether Maven Central is included for artifact resolution. */
        fun includeMavenCentral(value: Boolean): Builder = apply { includeMavenCentral = value }

        /** Add an extra Maven repository. Repeated calls accumulate. */
        fun artifactRepository(repo: RepositoryConfig): Builder = apply {
            artifactRepositories = artifactRepositories + repo
        }

        /** Replace the list of extra Maven repositories. */
        fun artifactRepositories(repos: List<RepositoryConfig>): Builder = apply {
            artifactRepositories = repos
        }

        /** Project-relative glob patterns to exclude from plugin and LST parsing. */
        fun excludePaths(paths: List<String>): Builder = apply { excludePaths = paths }

        /** Glob patterns for otherwise-unhandled files to parse as plain text. */
        fun plainTextMasks(masks: List<String>): Builder = apply { plainTextMasks = masks }

        /** Logger for lifecycle and diagnostic events. */
        fun logger(logger: RunnerLogger): Builder = apply { this.logger = logger }

        /** Internal test/application seam; forked execution deliberately rejects this writer. */
        internal fun changeWriter(changeWriter: ChangeWriter): Builder = apply {
            this.changeWriter = changeWriter
        }

        /** Construct a runner after validating the required recipe name. */
        fun build(): RewriteRunner {
            check(activeRecipe.isNotBlank()) {
                "activeRecipe must be set before calling build()"
            }
            return RewriteRunner(this)
        }
    }

    companion object {
        /** Create a new builder. */
        @JvmStatic
        fun builder(): Builder = Builder()

        /** Locate rewriterunner.yml/.yaml case-insensitively in a configuration directory. */
        internal fun findConfigCaseInsensitive(dir: Path): Path? = try {
            Files.list(dir).use { stream ->
                stream.filter {
                    val name = it.fileName.toString().lowercase()
                    name == "rewriterunner.yml" || name == "rewriterunner.yaml"
                }.findFirst().orElse(null)
            }
        } catch (_: Exception) {
            null
        }
    }
}
