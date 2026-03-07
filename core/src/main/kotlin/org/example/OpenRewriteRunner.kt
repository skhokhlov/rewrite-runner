package org.example

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.logging.Logger
import org.example.config.ToolConfig
import org.example.lst.LstBuilder
import org.example.recipe.RecipeArtifactResolver
import org.example.recipe.RecipeLoader
import org.example.recipe.RecipeRunner

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
 * val runner = OpenRewriteRunner.builder()
 *     .projectDir(Paths.get("/path/to/project"))
 *     .activeRecipe("org.openrewrite.java.format.AutoFormat")
 *     .build()
 * val result = runner.run()
 * ```
 *
 * Java usage:
 * ```java
 * RunResult result = OpenRewriteRunner.builder()
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
class OpenRewriteRunner private constructor(private val config: Builder) {

    private val log = Logger.getLogger(OpenRewriteRunner::class.java.name)

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
        require(config.projectDir.toFile().isDirectory) {
            "projectDir does not exist or is not a directory: ${config.projectDir}"
        }

        // 1. Load tool config
        val toolConfig = ToolConfig.load(config.configFile)
        val effectiveCacheDir = (config.cacheDir ?: toolConfig.resolvedCacheDir()).also {
            it.toFile().mkdirs()
            log.info("Cache dir: $it")
        }

        // 2. Resolve recipe JARs
        val recipeJars = if (config.recipeArtifacts.isNotEmpty()) {
            log.info("Resolving ${config.recipeArtifacts.size} recipe artifact(s)…")
            val resolver = RecipeArtifactResolver(
                cacheDir = effectiveCacheDir,
                extraRepositories = toolConfig.resolvedRepositories()
            )
            config.recipeArtifacts.flatMap { coord ->
                log.info("  → $coord")
                resolver.resolve(coord)
            }.distinct()
        } else {
            emptyList()
        }
        log.info("Recipe JARs resolved: ${recipeJars.size} JAR(s)")

        // 3. Load recipe
        val effectiveRewriteConfig = config.rewriteConfig
            ?: config.projectDir.resolve("rewrite.yaml")
        val recipe = RecipeLoader().load(
            recipeJars = recipeJars,
            activeRecipeName = config.activeRecipe,
            rewriteYaml = effectiveRewriteConfig
        )
        log.info("Recipe loaded: ${recipe.name}")

        // 4. Build LST (3-stage pipeline)
        // OpenRewrite requires all source files in memory simultaneously to support
        // cross-file analysis. For large projects set -Xmx accordingly, e.g.:
        //   java -Xmx6g -jar openrewrite-runner-all.jar …
        log.info("Building LST for project: ${config.projectDir}")
        val lstBuilder = LstBuilder(
            cacheDir = effectiveCacheDir,
            toolConfig = toolConfig
        )
        val sourceFiles = lstBuilder.build(
            projectDir = config.projectDir,
            parseConfig = toolConfig.parse,
            includeExtensionsCli = config.includeExtensions,
            excludeExtensionsCli = config.excludeExtensions
        )
        log.info("Parsed ${sourceFiles.size} source file(s)")

        // 5. Run recipe
        val results = RecipeRunner().run(recipe, sourceFiles)
        log.info("Recipe produced ${results.size} result(s)")

        // 6. Apply changes (unless dryRun)
        val writtenFiles = mutableListOf<Path>()
        if (!config.dryRun) {
            for (result in results) {
                if (result.after == null) {
                    // Recipe deleted this file — remove it from disk
                    val beforePath = result.before?.let { config.projectDir.resolve(it.sourcePath) }
                    if (beforePath != null) {
                        try {
                            Files.deleteIfExists(beforePath)
                        } catch (e: Exception) {
                            log.warning("Failed to delete file $beforePath: ${e.message}")
                        }
                    }
                } else {
                    val after = result.after!!
                    val target = config.projectDir.resolve(after.sourcePath)
                    target.toFile().parentFile?.mkdirs()
                    target.toFile().writeText(after.printAll(), Charsets.UTF_8)
                    writtenFiles.add(target)
                }
            }
            if (results.isNotEmpty()) {
                log.info("Changes written to disk (${results.size} file(s) modified)")
            }
        } else if (results.isNotEmpty()) {
            log.info("Dry-run mode: ${results.size} file(s) would be modified")
        }

        return RunResult(
            results = results,
            changedFiles = writtenFiles,
            projectDir = config.projectDir
        )
    }

    /**
     * Builder for [OpenRewriteRunner].
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

        /**
         * The root directory of the project to analyse. Defaults to the current working
         * directory. Must be an existing directory when [OpenRewriteRunner.run] is called.
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

        /**
         * Directory used to cache downloaded recipe JARs.
         * If not set, falls back to the value from the tool config file, then to
         * `~/.openscript/cache`.
         */
        fun cacheDir(path: Path): Builder = apply { cacheDir = path }

        /**
         * Path to the `openrewrite-runner.yml` tool config file for repository and cache
         * configuration. If not set, a default [ToolConfig] is used.
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
         * Construct the [OpenRewriteRunner].
         *
         * @throws IllegalStateException if [activeRecipe] has not been set.
         */
        fun build(): OpenRewriteRunner {
            check(activeRecipe.isNotBlank()) {
                "activeRecipe must be set before calling build()"
            }
            return OpenRewriteRunner(this)
        }
    }

    companion object {
        /**
         * Create a new [Builder] to configure an [OpenRewriteRunner].
         *
         * Annotated with [@JvmStatic][JvmStatic] so Java callers can write
         * `OpenRewriteRunner.builder()` rather than `OpenRewriteRunner.Companion.builder()`.
         */
        @JvmStatic
        fun builder(): Builder = Builder()
    }
}
