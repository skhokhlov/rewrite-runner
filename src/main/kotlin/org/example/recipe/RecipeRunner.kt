package org.example.recipe

import org.openrewrite.ExecutionContext
import org.openrewrite.InMemoryExecutionContext
import org.openrewrite.LargeSourceSet
import org.openrewrite.Recipe
import org.openrewrite.Result
import org.openrewrite.SourceFile
import org.openrewrite.internal.InMemoryLargeSourceSet
import java.util.logging.Logger

class RecipeRunner {
    private val log = Logger.getLogger(RecipeRunner::class.java.name)

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
