package io.github.skhokhlov.rewriterunner.recipe

import io.github.skhokhlov.rewriterunner.RunnerLogger
import org.openrewrite.ExecutionContext
import org.openrewrite.InMemoryExecutionContext
import org.openrewrite.LargeSourceSet
import org.openrewrite.Recipe
import org.openrewrite.RecipeRun
import org.openrewrite.Result
import org.openrewrite.SourceFile
import org.openrewrite.internal.InMemoryLargeSourceSet

/**
 * Executes an OpenRewrite [org.openrewrite.Recipe] against a list of parsed source files
 * and returns the [org.openrewrite.Result] changeset.
 *
 * Uses [org.openrewrite.internal.InMemoryLargeSourceSet] to hold all source files in memory
 * simultaneously, which is required for cross-file analysis (e.g., renaming a type used
 * across multiple files). For large projects, increase the JVM heap with `-Xmx`.
 */
open class RecipeRunner(val logger: RunnerLogger) {
    /**
     * Run [recipe] against [sourceFiles] and return all [org.openrewrite.Result]s.
     *
     * @param recipe The activated recipe to execute.
     * @param sourceFiles The full LST (Lossless Semantic Tree) for the project, as produced by
     *   [io.github.skhokhlov.rewriterunner.lst.LstBuilder].
     * @param ctx Execution context for collecting parse/recipe errors. Defaults to an
     *   [org.openrewrite.InMemoryExecutionContext] that logs warnings.
     * @return All results that represent a change (before != after). An empty list means the
     *   recipe made no modifications to any file.
     */
    fun run(
        recipe: Recipe,
        sourceFiles: List<SourceFile>,
        ctx: ExecutionContext = defaultContext()
    ): List<Result> {
        logger.info("Running recipe '${recipe.name}' against ${sourceFiles.size} source files")

        val largeSourceSet: LargeSourceSet = InMemoryLargeSourceSet(sourceFiles)
        val recipeRun =
            try {
                executeRecipe(recipe, largeSourceSet, ctx)
            } catch (e: LinkageError) {
                throw IllegalStateException(
                    "Failed to load recipe class — a required dependency may not have been " +
                        "downloaded. Try re-running with --debug to see which artifacts failed " +
                        "to resolve.",
                    e
                )
            }
        val results = recipeRun.changeset.allResults

        logger.info("Recipe produced ${results.size} result(s)")
        return results
    }

    /**
     * Calls [recipe].run against [sourceSet]. Extracted as a protected open method so that
     * tests can subclass [RecipeRunner] and simulate failure scenarios (e.g. [LinkageError]
     * caused by a missing transitive dependency JAR) without needing a real broken classpath.
     */
    protected open fun executeRecipe(
        recipe: Recipe,
        sourceSet: LargeSourceSet,
        ctx: ExecutionContext
    ): RecipeRun = recipe.run(sourceSet, ctx)

    private fun defaultContext(): ExecutionContext = InMemoryExecutionContext { throwable ->
        logger.warn("Recipe execution error: ${throwable.message}")
    }
}
