package io.github.skhokhlov.rewriterunner

import io.github.skhokhlov.rewriterunner.config.RepositoryConfig
import io.github.skhokhlov.rewriterunner.config.ToolConfig
import io.github.skhokhlov.rewriterunner.lst.DependencyResolutionStage
import io.github.skhokhlov.rewriterunner.lst.LstBuilder
import io.github.skhokhlov.rewriterunner.recipe.RecipeArtifactResolver
import io.github.skhokhlov.rewriterunner.recipe.RecipeLoader
import io.github.skhokhlov.rewriterunner.recipe.RecipeRunner
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists

/**
 * Programmatic entry point for running OpenRewrite recipes from library code.
 *
 * Encapsulates the same orchestration pipeline that the CLI uses:
 * 1. Load [ToolConfig] (from [Builder.configFile] if supplied, otherwise defaults).
 * 2. Resolve recipe JARs from Maven coordinates via [RecipeArtifactResolver].
 * 3. Load the requested recipe via [RecipeLoader].
 * 4. Build the Lossless Semantic Tree (LST) for the project via [LstBuilder].
 * 5. Execute the recipe via [RecipeRunner].
 * 6. Optionally write changed files to disk (controlled by [Builder.dryRun]).
 *
 * Obtain an instance through the [Builder]:
 * ```kotlin
 * val runner = RewriteRunner.builder()
 *     .projectDir(Paths.get("/path/to/project"))
 *     .activeRecipe("org.openrewrite.java.format.AutoFormat")
 *     .build()
 * val result = runner.run()
 * ```
 *
 * Java usage:
 * ```java
 * RunResult result = RewriteRunner.builder()
 *     .projectDir(Paths.get("/path/to/project"))
 *     .activeRecipe("org.openrewrite.java.format.AutoFormat")
 *     .build()
 *     .run();
 * ```
 *
 * This class is thread-safe for concurrent [run] calls only when each call operates on a
 * different [Builder.projectDir]. Sharing the same project directory across concurrent
 * runs is not supported.
 */
class RewriteRunner private constructor(private val config: Builder) {

    /**
     * Execute the configured recipe against the project directory.
     *
     * @return A [RunResult] containing the raw OpenRewrite results, the list of files
     *   written to disk (empty when [Builder.dryRun] is `true`), and the project directory.
     * @throws IllegalArgumentException if the recipe name is not found in the loaded JARs
     *   or in the classpath.
     * @throws Exception if any unrecoverable error occurs during LST building or recipe
     *   execution. Errors during individual file parsing are logged as warnings and do not
     *   abort the run.
     */
    fun run(): RunResult {
        val logger = config.logger

        require(config.projectDir.toFile().isDirectory) {
            "projectDir does not exist or is not a directory: ${config.projectDir}"
        }

        // 1. Load tool config
        logger.lifecycle("[1/6] Loading configuration")
        val effectiveConfigFile = config.configFile
            ?: findConfigCaseInsensitive(config.projectDir)
            ?: findConfigCaseInsensitive(
                Paths.get(System.getProperty("user.home"), ".rewriterunner")
            )
        val toolConfig = ToolConfig.load(effectiveConfigFile, logger)
        val effectiveCacheDir = (config.cacheDir ?: toolConfig.resolvedCacheDir()).also {
            logger.info("      Cache dir: $it")
        }
        val effectiveIncludeMavenCentral =
            config.includeMavenCentral ?: toolConfig.includeMavenCentral
        val effectiveDownloadThreads = config.downloadThreads ?: toolConfig.downloadThreads
        val effectiveRepositories = toolConfig.resolvedRepositories() + config.repositories
        // Recipe artifacts are isolated in the tool's own cache so they never mix with
        // the project's build artifacts in the user's Maven local repository.
        val recipeLocalRepoDir = effectiveCacheDir.resolve("repository")
        Files.createDirectories(recipeLocalRepoDir)
        val recipeContext = AetherContext.build(
            localRepoDir = recipeLocalRepoDir,
            extraRepositories = effectiveRepositories,
            downloadThreads = effectiveDownloadThreads,
            // Prune test/provided/system scope nodes from the graph during collection so
            // their POMs are never fetched. Recipes only need compile/runtime JARs to run.
            excludeScopesFromGraph = listOf("test", "provided", "system"),
            includeMavenCentral = effectiveIncludeMavenCentral,
            logger = logger
        )
        // Project dependencies use the Maven default local repository so already-cached
        // artifacts from the project's own build are reused without re-downloading.
        val mavenLocalRepoDir = Paths.get(System.getProperty("user.home"), ".m2", "repository")
        val projectContext = AetherContext.build(
            localRepoDir = mavenLocalRepoDir,
            extraRepositories = effectiveRepositories,
            downloadThreads = effectiveDownloadThreads,
            includeMavenCentral = effectiveIncludeMavenCentral,
            logger = logger
        )

        // 2. Resolve recipe JARs
        val recipeJars = if (config.recipeArtifacts.isNotEmpty()) {
            logger.lifecycle("[2/6] Resolving ${config.recipeArtifacts.size} recipe artifact(s)")
            RecipeArtifactResolver(recipeContext, logger).resolveAll(config.recipeArtifacts)
        } else {
            logger.lifecycle("[2/6] No recipe artifacts specified — using classpath recipes only")
            emptyList()
        }

        // 3. Load recipe (precedence: string content > explicit path > implicit projectDir/rewrite.yaml)
        logger.lifecycle("[3/6] Loading recipe '${config.activeRecipe}'")
        val recipe = if (config.rewriteConfigContent != null) {
            RecipeLoader(logger).load(
                recipeJars = recipeJars,
                activeRecipeName = config.activeRecipe,
                rewriteYamlContent = config.rewriteConfigContent
            )
        } else {
            val effectiveRewriteConfig =
                config.rewriteConfig
                    ?: config.projectDir.resolve("rewrite.yaml").takeIf { it.exists() }
                    ?: config.projectDir.resolve("rewrite.yml")
            RecipeLoader(logger).load(
                recipeJars = recipeJars,
                activeRecipeName = config.activeRecipe,
                rewriteYaml = effectiveRewriteConfig
            )
        }
        logger.info("      Recipe ready: ${recipe.name}")

        // 4. Build LST (3-stage pipeline)
        // OpenRewrite requires all source files in memory simultaneously to support
        // cross-file analysis. For large projects set -Xmx accordingly, e.g.:
        //   java -Xmx6g -jar rewrite-runner-all.jar …
        logger.lifecycle("[4/6] Building LST for ${config.projectDir}")
        val lstBuilder = LstBuilder(
            logger = logger,
            cacheDir = effectiveCacheDir,
            toolConfig = toolConfig,
            depResolutionStage = DependencyResolutionStage(projectContext, logger)
        )
        val effectiveParseConfig = if (config.excludePaths.isNotEmpty()) {
            toolConfig.parse.copy(excludePaths = config.excludePaths)
        } else {
            toolConfig.parse
        }
        val lstStart = System.currentTimeMillis()
        val sourceFiles = lstBuilder.build(
            projectDir = config.projectDir,
            parseConfig = effectiveParseConfig,
            includeExtensionsCli = config.includeExtensions,
            excludeExtensionsCli = config.excludeExtensions
        )
        logger.lifecycle(
            "      LST built: ${sourceFiles.size} file(s) in ${System.currentTimeMillis() - lstStart}ms"
        )

        // 5. Run recipe
        logger.lifecycle(
            "[5/6] Running recipe '${recipe.name}' against ${sourceFiles.size} file(s)"
        )
        val recipeStart = System.currentTimeMillis()
        val results = RecipeRunner(logger).run(recipe, sourceFiles)
        logger.lifecycle(
            "      Recipe complete: ${results.size} file(s) changed" +
                " in ${System.currentTimeMillis() - recipeStart}ms"
        )

        // 6. Apply changes (unless dryRun)
        val writtenFiles = mutableListOf<Path>()
        if (!config.dryRun) {
            logger.lifecycle("[6/6] Writing changes to disk")
            for (result in results) {
                if (result.after == null) {
                    // Recipe deleted this file — remove it from disk
                    val beforePath = result.before?.let { config.projectDir.resolve(it.sourcePath) }
                    if (beforePath != null) {
                        try {
                            Files.deleteIfExists(beforePath)
                            logger.info("      Deleted ${result.before!!.sourcePath}")
                        } catch (e: Exception) {
                            logger.warn("Failed to delete file $beforePath: ${e.message}")
                        }
                    }
                } else {
                    val after = result.after!!
                    val target = config.projectDir.resolve(after.sourcePath)
                    Files.createDirectories(target.parent)
                    target.toFile().writeText(after.printAll(), Charsets.UTF_8)
                    writtenFiles.add(target)
                    logger.info("      Wrote ${after.sourcePath}")
                }
            }
            if (results.isEmpty()) {
                logger.lifecycle("      No changes — nothing to write")
            } else {
                logger.lifecycle("      Done: ${writtenFiles.size} file(s) written")
            }
        } else {
            logger.lifecycle(
                "[6/6] Dry-run mode: skipping disk writes (${results.size} file(s) would change)"
            )
        }

        return RunResult(
            results = results,
            changedFiles = writtenFiles,
            projectDir = config.projectDir
        )
    }

    /**
     * Builder for [RewriteRunner].
     *
     * All setter methods return `this` for fluent chaining. The only required properties
     * are [projectDir] and [activeRecipe].
     *
     * Defaults mirror the CLI defaults:
     * - [projectDir] defaults to the current working directory.
     * - [dryRun] defaults to `false`.
     * - All list properties default to empty (meaning: use values from [configFile] if
     *   present, or built-in defaults).
     */
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
        internal var includeExtensions: List<String> = emptyList()
            private set
        internal var excludeExtensions: List<String> = emptyList()
            private set
        internal var includeMavenCentral: Boolean? = null
            private set
        internal var downloadThreads: Int? = null
            private set
        internal var repositories: List<RepositoryConfig> = emptyList()
            private set
        internal var excludePaths: List<String> = emptyList()
            private set
        internal var logger: RunnerLogger = NoOpRunnerLogger
            private set

        /**
         * The root directory of the project to analyse. Defaults to the current working
         * directory. Must be an existing directory when [RewriteRunner.run] is called.
         */
        fun projectDir(path: Path): Builder = apply { projectDir = path }

        /**
         * Fully-qualified name of the OpenRewrite recipe to activate.
         * Required — [build] throws [IllegalStateException] if not set.
         */
        fun activeRecipe(name: String): Builder = apply { activeRecipe = name }

        /**
         * Add a Maven coordinate (`groupId:artifactId:version`) whose JAR(s) will be
         * downloaded and scanned for the requested recipe. May be called multiple times;
         * all coordinates are accumulated. `LATEST` is accepted as the version token.
         */
        fun recipeArtifact(coordinate: String): Builder = apply {
            recipeArtifacts = recipeArtifacts + coordinate
        }

        /**
         * Replace the full list of recipe artifact coordinates. Useful when coordinates
         * are already collected into a [List] before building.
         */
        fun recipeArtifacts(coordinates: List<String>): Builder = apply {
            recipeArtifacts = coordinates
        }

        /** Path to a `rewrite.yaml` file for custom composite recipes. */
        fun rewriteConfig(path: Path): Builder = apply { rewriteConfig = path }

        /** Raw `rewrite.yaml` content. Takes precedence over [rewriteConfig] when both are set. */
        fun rewriteConfigContent(content: String): Builder =
            apply { rewriteConfigContent = content }

        /**
         * Cache root for downloaded recipe JARs. Recipe artifacts are stored under
         * `<path>/repository`, keeping them isolated from the user's Maven local repository.
         * Project dependencies are always resolved from `~/.m2/repository` regardless of this
         * setting.
         *
         * If not set, falls back to the value from the tool config file, then to
         * `~/.rewriterunner/cache`.
         */
        fun cacheDir(path: Path): Builder = apply { cacheDir = path }

        /**
         * Path to the `rewriterunner.yml` tool config file for repository and cache
         * configuration. If not set, auto-discovery checks `<projectDir>/rewriterunner.yml`
         * first, then `~/.rewriterunner/rewriterunner.yml` as a global fallback.
         * File name matching is case-insensitive. If no file is found, built-in defaults apply.
         */
        fun configFile(path: Path): Builder = apply { configFile = path }

        /**
         * When `true`, the recipe is executed and results are returned but no files are
         * written to disk. Defaults to `false`.
         */
        fun dryRun(value: Boolean): Builder = apply { dryRun = value }

        /**
         * Restrict parsing to the given file extensions (e.g. `".java"`, `".kt"`).
         * Overrides any `includeExtensions` setting from the tool config file.
         */
        fun includeExtensions(extensions: List<String>): Builder = apply {
            includeExtensions = extensions
        }

        /**
         * Skip parsing files with the given extensions.
         * Overrides any `excludeExtensions` setting from the tool config file.
         */
        fun excludeExtensions(extensions: List<String>): Builder = apply {
            excludeExtensions = extensions
        }

        /**
         * Set the number of parallel artifact download threads. Defaults to 5.
         * When not set, falls back to `downloadThreads` from the tool config.
         */
        fun downloadThreads(n: Int): Builder = apply { downloadThreads = n }

        /**
         * Override whether Maven Central is included as a remote repository.
         * When `false`, only the repositories explicitly configured via the tool config
         * file are used. Useful in enterprise environments where Central is unreachable.
         * When not set, falls back to `includeMavenCentral` from the tool config (default `true`).
         */
        fun includeMavenCentral(value: Boolean): Builder = apply { includeMavenCentral = value }

        /**
         * Add a single extra Maven repository for artifact resolution. May be called
         * multiple times; all entries accumulate and are combined with any repositories
         * declared in the tool config file.
         */
        fun repository(repo: RepositoryConfig): Builder = apply {
            repositories = repositories + repo
        }

        /**
         * Replace the full list of extra Maven repositories for artifact resolution.
         * Combined with any repositories declared in the tool config file.
         */
        fun repositories(repos: List<RepositoryConfig>): Builder = apply {
            repositories = repos
        }

        /**
         * Glob patterns (relative to the project root) for paths to skip during parsing.
         * When non-empty, overrides `parse.excludePaths` from the tool config file.
         * Supports the same glob syntax as [java.nio.file.FileSystem.getPathMatcher].
         */
        fun excludePaths(paths: List<String>): Builder = apply { excludePaths = paths }

        /**
         * Set the logger used for progress and diagnostic output.
         * Defaults to [NoOpRunnerLogger] (silent). Use [io.github.skhokhlov.rewriterunner.cli.LogbackRunnerLogger]
         * in the CLI, or provide a custom implementation for library use.
         */
        fun logger(logger: RunnerLogger): Builder = apply { this.logger = logger }

        /**
         * Construct the [RewriteRunner].
         *
         * @throws IllegalStateException if [activeRecipe] has not been set.
         */
        fun build(): RewriteRunner {
            check(activeRecipe.isNotBlank()) {
                "activeRecipe must be set before calling build()"
            }
            return RewriteRunner(this)
        }
    }

    companion object {
        /**
         * Create a new [Builder] to configure an [RewriteRunner].
         *
         * Annotated with [@JvmStatic][JvmStatic] so Java callers can write
         * `RewriteRunner.builder()` rather than `RewriteRunner.Companion.builder()`.
         */
        @JvmStatic
        fun builder(): Builder = Builder()

        /**
         * Find `rewriterunner.yml` (or `.yaml`) in [dir] using case-insensitive name matching.
         * Returns `null` if [dir] does not exist or contains no matching file.
         */
        private fun findConfigCaseInsensitive(dir: Path): Path? = try {
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
