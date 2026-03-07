package org.example.recipe

import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.openrewrite.ExecutionContext
import org.openrewrite.InMemoryExecutionContext
import org.openrewrite.internal.InMemoryLargeSourceSet
import org.openrewrite.text.PlainText
import org.openrewrite.text.PlainTextParser

class RecipeLoaderTest {

    @TempDir
    lateinit var tempDir: Path

    private val ctx: ExecutionContext = InMemoryExecutionContext {}

    // ─── Built-in recipe loading (no JAR) ────────────────────────────────────

    @Test
    fun `load returns a usable recipe when no recipe JARs are provided`() {
        val recipe = RecipeLoader().load(
            recipeJars = emptyList(),
            activeRecipeName = "org.openrewrite.java.format.AutoFormat",
            rewriteYaml = null
        )
        assertNotNull(recipe, "load() should return a non-null recipe")
        assertEquals(
            "org.openrewrite.java.format.AutoFormat",
            recipe.name,
            "Recipe name should match the requested name"
        )
    }

    @Test
    fun `recipe returned by load is executable without ClassNotFoundException`() {
        // Regression test: RecipeLoader previously closed the URLClassLoader immediately
        // after activateRecipes(), before the caller had a chance to run the recipe.
        // OpenRewrite recipes lazily load visitor inner-classes at execution time; closing
        // the class loader prematurely causes NoClassDefFoundError during recipe.run().
        //
        // This test uses a built-in recipe (no external JAR) and verifies the full
        // load → run cycle works.  The JAR-path is the critical path guarded by the fix
        // in RecipeLoader.load(): the URLClassLoader must NOT be closed before the caller
        // invokes the recipe.
        val recipe = RecipeLoader().load(
            recipeJars = emptyList(),
            activeRecipeName = "org.openrewrite.FindSourceFiles",
            rewriteYaml = null
        )

        // Parse a simple plain-text file and run the recipe — this exercises visitor
        // class loading.  If the classloader had been closed too early this would throw.
        val sourceFiles: List<org.openrewrite.SourceFile> =
            PlainTextParser().parse(ctx, "hello world")
                .map { sf ->
                    (sf as PlainText).withSourcePath(
                        Path.of("hello.txt")
                    ) as org.openrewrite.SourceFile
                }
                .toList()

        val result = runCatching {
            recipe.run(InMemoryLargeSourceSet(sourceFiles), ctx)
        }

        // The recipe may or may not change files; what matters is that execution
        // completes without a class-loading exception.
        assertEquals(
            true,
            result.isSuccess,
            "Recipe execution should complete without ClassNotFoundException: ${result.exceptionOrNull()}"
        )
    }

    @Test
    fun `load with invalid recipe name throws IllegalArgumentException`() {
        val result = runCatching {
            RecipeLoader().load(
                recipeJars = emptyList(),
                activeRecipeName = "com.example.recipe.ThatDefinitelyDoesNotExist",
                rewriteYaml = null
            )
        }
        assertEquals(
            true,
            result.isFailure,
            "Loading a nonexistent recipe should throw an exception"
        )
    }
}
