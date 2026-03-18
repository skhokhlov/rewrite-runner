package io.github.skhokhlov.rewriterunner.recipe

import io.github.skhokhlov.rewriterunner.NoOpRunnerLogger
import io.kotest.core.spec.style.FunSpec
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.openrewrite.ExecutionContext
import org.openrewrite.LargeSourceSet
import org.openrewrite.Recipe
import org.openrewrite.RecipeRun

/**
 * Creates a [RecipeRunner] whose [RecipeRunner.executeRecipe] always throws [error].
 * This simulates a [LinkageError] caused by a missing transitive dependency JAR
 * without needing a real broken classpath — the same subclass pattern used for
 * [io.github.skhokhlov.rewriterunner.lst.ProjectBuildStage] and
 * [io.github.skhokhlov.rewriterunner.lst.DependencyResolutionStage].
 */
private fun brokenRunner(error: LinkageError): RecipeRunner =
    object : RecipeRunner(NoOpRunnerLogger) {
        override fun executeRecipe(
            recipe: Recipe,
            sourceSet: LargeSourceSet,
            ctx: ExecutionContext
        ): RecipeRun = throw error
    }

class RecipeRunnerTest :
    FunSpec({
        // ─── LinkageError handling ────────────────────────────────────────────────

        test("run wraps NoClassDefFoundError in IllegalStateException with actionable message") {
            // Regression: when a transitive dependency JAR is absent, the JVM throws
            // NoClassDefFoundError at execution time.  RecipeRunner must wrap it as
            // IllegalStateException with a message that tells the user what went wrong.
            val runner = brokenRunner(NoClassDefFoundError("com/example/missing/SomeClass"))

            val result = runCatching { runner.run(Recipe.noop(), emptyList()) }

            assertTrue(
                result.isFailure,
                "Should throw when executeRecipe throws NoClassDefFoundError"
            )
            val ex = result.exceptionOrNull()
            assertTrue(
                ex is IllegalStateException,
                "Should rethrow as IllegalStateException; got ${ex?.javaClass?.name}: ${ex?.message}"
            )
            val msg = ex.message ?: ""
            assertTrue(
                msg.contains("dependency", ignoreCase = true),
                "Message should mention missing dependency: $msg"
            )
            assertTrue(
                msg.contains("--debug", ignoreCase = true),
                "Message should suggest --debug flag: $msg"
            )
            val cause = ex.cause
            assertNotNull(cause, "IllegalStateException should have LinkageError as cause")
            assertTrue(
                cause is LinkageError,
                "Cause should be a LinkageError; got ${cause.javaClass.name}"
            )
        }

        test("run wraps generic LinkageError in IllegalStateException") {
            val runner =
                brokenRunner(LinkageError("incompatible class version for com/example/Other"))

            val result = runCatching { runner.run(Recipe.noop(), emptyList()) }

            val ex = result.exceptionOrNull()
            assertTrue(
                ex is IllegalStateException,
                "LinkageError must be wrapped in IllegalStateException; got ${ex?.javaClass?.name}"
            )
        }

        test("run preserves original LinkageError as the cause") {
            val originalError = NoClassDefFoundError("com/example/Missing")
            val runner = brokenRunner(originalError)

            val result = runCatching { runner.run(Recipe.noop(), emptyList()) }

            val ex = result.exceptionOrNull() as? IllegalStateException
            assertEquals(originalError, ex?.cause, "Original LinkageError must be the cause")
        }
    })
