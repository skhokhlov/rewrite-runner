package org.example.recipe

import org.openrewrite.Recipe
import org.openrewrite.config.ClasspathScanningLoader
import org.openrewrite.config.Environment
import org.openrewrite.config.YamlResourceLoader
import java.io.FileInputStream
import java.net.URLClassLoader
import java.nio.file.Path
import java.util.Properties
import java.util.logging.Logger
import kotlin.io.path.exists

/**
 * Loads OpenRewrite recipes from recipe JARs and/or a `rewrite.yaml` declarative config.
 *
 * Internally builds an OpenRewrite [org.openrewrite.config.Environment] that scans the
 * provided JARs (and, when no JARs are given, the tool's own classpath) for recipes,
 * styles, and categories. The [load] method returns the activated [org.openrewrite.Recipe]
 * ready for execution by [RecipeRunner].
 */
class RecipeLoader {
    private val log = Logger.getLogger(RecipeLoader::class.java.name)

    /**
     * Build an OpenRewrite [Environment] from the given recipe JARs and optional rewrite.yaml.
     * Returns the activated [Recipe] ready for execution.
     */
    fun load(
        recipeJars: List<Path>,
        activeRecipeName: String,
        rewriteYaml: Path?,
    ): Recipe {
        val props = Properties()
        val parentLoader = Thread.currentThread().contextClassLoader

        // Build a class loader that includes all recipe JARs
        val recipeClassLoader = if (recipeJars.isNotEmpty()) {
            URLClassLoader(
                recipeJars.map { it.toUri().toURL() }.toTypedArray(),
                parentLoader,
            )
        } else {
            parentLoader
        }

        val builder = Environment.builder()

        // Scan each recipe JAR for OpenRewrite recipes/styles/categories
        for (jar in recipeJars) {
            log.info("Scanning recipe JAR: $jar")
            try {
                builder.load(ClasspathScanningLoader(jar, props, emptyList(), recipeClassLoader))
            } catch (e: Exception) {
                log.warning("Failed to scan recipe JAR $jar (skipping): ${e.message}")
            }
        }

        // Scan the tool's own classpath for built-in recipes only when no recipe JARs are provided.
        // When recipe JARs are present their transitive deps already include all OpenRewrite core
        // jars, so a blanket classpath scan would register the same recipes twice and cause
        // duplicate-key errors in Environment.activateRecipes().
        if (recipeJars.isEmpty()) {
            try {
                builder.load(ClasspathScanningLoader(props, recipeClassLoader))
            } catch (e: Exception) {
                log.warning("Failed to scan tool classpath (skipping): ${e.message}")
            }
        }

        // Load rewrite.yaml if present
        if (rewriteYaml != null && rewriteYaml.exists()) {
            log.info("Loading rewrite.yaml: $rewriteYaml")
            FileInputStream(rewriteYaml.toFile()).use { stream ->
                builder.load(YamlResourceLoader(stream, rewriteYaml.toUri(), props, recipeClassLoader))
            }
        }

        val env = builder.build()
        val recipe = env.activateRecipes(activeRecipeName)
        require(recipe.recipeList.isNotEmpty() || recipe.name == activeRecipeName) {
            "Recipe '$activeRecipeName' not found. Available recipes can be listed with --list-recipes."
        }
        return recipe
    }
}
