package io.github.skhokhlov.rewriterunner

import io.github.skhokhlov.rewriterunner.config.ToolConfig
import io.github.skhokhlov.rewriterunner.lst.DependencyResolutionStage
import io.github.skhokhlov.rewriterunner.lst.LstBuilder
import io.github.skhokhlov.rewriterunner.recipe.RecipeArtifactResolver
import io.github.skhokhlov.rewriterunner.recipe.RecipeLoader
import io.github.skhokhlov.rewriterunner.recipe.RecipeRunner
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.slf4j.LoggerFactory

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

    private val log = LoggerFactory.getLogger(RewriteRunner::class.java.name)

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
        log.info("[1/6] Loading configuration")
        val toolConfig = ToolConfig.load(config.configFile)
        val effectiveCacheDir = (config.cacheDir ?: toolConfig.resolvedCacheDir()).also {
            log.info("      Cache dir: $it")
        }
        val effectiveIncludeMavenCentral =
            config.includeMavenCentral ?: toolConfig.includeMavenCentral
        // Recipe artifacts are isolated in the tool's own cache so they never mix with
        // the project's build artifacts in the user's Maven local repository.
        val recipeLocalRepoDir = effectiveCacheDir.resolve("repository")
        Files.createDirectories(recipeLocalRepoDir)
        val recipeContext = AetherContext.build(
            localRepoDir = recipeLocalRepoDir,
            extraRepositories = toolConfig.resolvedRepositories(),
            includeMavenCentral = effectiveIncludeMavenCentral
        )
        // Project dependencies use the Maven default local repository so already-cached
        // artifacts from the project's own build are reused without re-downloading.
        val mavenLocalRepoDir = Paths.get(System.getProperty("user.home"), ".m2", "repository")
        val projectContext = AetherContext.build(
            localRepoDir = mavenLocalRepoDir,
            extraRepositories = toolConfig.resolvedRepositories(),
            includeMavenCentral = effectiveIncludeMavenCentral
        )

        // 2. Resolve recipe JARs
        val recipeJars = if (config.recipeArtifacts.isNotEmpty()) {
            log.info("[2/6] Resolving ${config.recipeArtifacts.size} recipe artifact(s)")
            RecipeArtifactResolver(recipeContext).resolveAll(config.recipeArtifacts)
        } else {
            log.info("[2/6] No recipe artifacts specified — using classpath recipes only")
            emptyList()
        }

        // 3. Load recipe (precedence: string content > explicit path > implicit projectDir/rewrite.yaml)
        log.info("[3/6] Loading recipe '${config.activeRecipe}'")
        val recipe = if (config.rewriteConfigContent != null) {
            RecipeLoader().load(
                recipeJars = recipeJars,
                activeRecipeName = config.activeRecipe,
                rewriteYamlContent = config.rewriteConfigContent
            )
        } else {
            val effectiveRewriteConfig =
                config.rewriteConfig ?: config.projectDir.resolve("rewrite.yaml")
            RecipeLoader().load(
                recipeJars = recipeJars,
                activeRecipeName = config.activeRecipe,
                rewriteYaml = effectiveRewriteConfig
            )
        }
        log.info("      Recipe ready: ${recipe.name}")

        // 4. Build LST (3-stage pipeline)
        // OpenRewrite requires all source files in memory simultaneously to support
        // cross-file analysis. For large projects set -Xmx accordingly, e.g.:
        //   java -Xmx6g -jar rewrite-runner-all.jar …
        log.info("[4/6] Building LST for ${config.projectDir}")
        val lstBuilder = LstBuilder(
            cacheDir = effectiveCacheDir,
            toolConfig = toolConfig,
            depResolutionStage = DependencyResolutionStage(projectContext)
        )
        val lstStart = System.currentTimeMillis()
        val sourceFiles = lstBuilder.build(
            projectDir = config.projectDir,
            parseConfig = toolConfig.parse,
            includeExtensionsCli = config.includeExtensions,
            excludeExtensionsCli = config.excludeExtensions
        )
        log.info(
            "      LST built: ${sourceFiles.size} file(s) in ${System.currentTimeMillis() - lstStart}ms"
        )

        // 5. Run recipe
        log.info("[5/6] Running recipe '${recipe.name}' against ${sourceFiles.size} file(s)")
        val recipeStart = System.currentTimeMillis()
        val results = RecipeRunner().run(recipe, sourceFiles)
        log.info(
            "      Recipe complete: ${results.size} file(s) changed" +
                " in ${System.currentTimeMillis() - recipeStart}ms"
        )

        // 6. Apply changes (unless dryRun)
        val writtenFiles = mutableListOf<Path>()
        if (!config.dryRun) {
            log.info("[6/6] Writing changes to disk")
            for (result in results) {
                if (result.after == null) {
                    // Recipe deleted this file — remove it from disk
                    val beforePath = result.before?.let { config.projectDir.resolve(it.sourcePath) }
                    if (beforePath != null) {
                        try {
                            Files.deleteIfExists(beforePath)
                            log.info("      Deleted ${result.before!!.sourcePath}")
                        } catch (e: Exception) {
                            log.warn("Failed to delete file $beforePath: ${e.message}")
                        }
                    }
                } else {
                    val after = result.after!!
                    val target = config.projectDir.resolve(after.sourcePath)
                    Files.createDirectories(target.parent)
                    target.toFile().writeText(after.printAll(), Charsets.UTF_8)
                    writtenFiles.add(target)
                    log.info("      Wrote ${after.sourcePath}")
                }
            }
            if (results.isEmpty()) {
                log.info("      No changes — nothing to write")
            } else {
                log.info("      Done: ${writtenFiles.size} file(s) written")
            }
        } else {
            log.info(
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
         * Path to the `rewrite-runner.yml` tool config file for repository and cache
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
         * Override whether Maven Central is included as a remote repository.
         * When `false`, only the repositories explicitly configured via the tool config
         * file are used. Useful in enterprise environments where Central is unreachable.
         * When not set, falls back to `includeMavenCentral` from the tool config (default `true`).
         */
        fun includeMavenCentral(value: Boolean): Builder = apply { includeMavenCentral = value }

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
    }
}
