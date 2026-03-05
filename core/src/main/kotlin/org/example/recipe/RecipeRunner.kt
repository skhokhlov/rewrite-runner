package org.example.recipe

import org.openrewrite.ExecutionContext
import org.openrewrite.InMemoryExecutionContext
import org.openrewrite.LargeSourceSet
import org.openrewrite.Recipe
import org.openrewrite.Result
import org.openrewrite.SourceFile
import org.openrewrite.internal.InMemoryLargeSourceSet
import java.util.logging.Logger

/**
 * Executes an OpenRewrite [org.openrewrite.Recipe] against a list of parsed source files
 * and returns the [org.openrewrite.Result] changeset.
 *
 * Uses [org.openrewrite.internal.InMemoryLargeSourceSet] to hold all source files in memory
 * simultaneously, which is required for cross-file analysis (e.g., renaming a type used
 * across multiple files). For large projects, increase the JVM heap with `-Xmx`.
 */
class RecipeRunner {
    private val log = Logger.getLogger(RecipeRunner::class.java.name)

    /**
     * Run [recipe] against [sourceFiles] and return all [org.openrewrite.Result]s.
     *
     * @param recipe The activated recipe to execute.
     * @param sourceFiles The full LST (Lossless Semantic Tree) for the project, as produced by
     *   [org.example.lst.LstBuilder].
     * @param ctx Execution context for collecting parse/recipe errors. Defaults to an
     *   [org.openrewrite.InMemoryExecutionContext] that logs warnings.
     * @return All results that represent a change (before != after). An empty list means the
     *   recipe made no modifications to any file.
     */
    fun run(
        recipe: Recipe,
        sourceFiles: List<SourceFile>,
        ctx: ExecutionContext = defaultContext(),
    ): List<Result> {
        log.info("Running recipe '${recipe.name}' against ${sourceFiles.size} source files")

        val largeSourceSet: LargeSourceSet = InMemoryLargeSourceSet(sourceFiles)
        val recipeRun = recipe.run(largeSourceSet, ctx)
        val results = recipeRun.changeset.allResults

        log.info("Recipe produced ${results.size} result(s)")
        return results
    }

    private fun defaultContext(): ExecutionContext =
        InMemoryExecutionContext { throwable ->
            log.warning("Recipe execution error: ${throwable.message}")
        }
}
