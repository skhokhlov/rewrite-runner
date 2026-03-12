package io.github.skhokhlov.rewriterunner.recipe

import io.github.skhokhlov.rewriterunner.NoOpRunnerLogger
import io.github.skhokhlov.rewriterunner.RunnerLogger
import java.io.ByteArrayInputStream
import java.io.FileInputStream
import java.io.InputStream
import java.net.URI
import java.net.URLClassLoader
import java.nio.file.Path
import java.util.Properties
import kotlin.io.path.exists
import org.openrewrite.Recipe
import org.openrewrite.RecipeException
import org.openrewrite.config.ClasspathScanningLoader
import org.openrewrite.config.Environment
import org.openrewrite.config.YamlResourceLoader

/**
 * Loads OpenRewrite recipes from recipe JARs and/or a `rewrite.yaml` declarative config.
 *
 * Internally builds an OpenRewrite [org.openrewrite.config.Environment] that scans the
 * provided JARs (and, when no JARs are given, the tool's own classpath) for recipes,
 * styles, and categories. The [load] method returns the activated [org.openrewrite.Recipe]
 * ready for execution by [RecipeRunner].
 */
class RecipeLoader(val logger: RunnerLogger = NoOpRunnerLogger) {
    /**
     * Build an OpenRewrite [Environment] from the given recipe JARs and optional rewrite.yaml.
     * Returns the activated [Recipe] ready for execution.
     *
     * @param rewriteYaml Path to the rewrite.yaml file, or `null` to rely solely on
     *   classpath-scanned recipes.
     */
    fun load(recipeJars: List<Path>, activeRecipeName: String, rewriteYaml: Path?): Recipe {
        val yamlSource: YamlSource? =
            if (rewriteYaml != null && rewriteYaml.exists()) {
                logger.info("Loading rewrite.yaml: $rewriteYaml")
                YamlSource(
                    stream = { FileInputStream(rewriteYaml.toFile()) },
                    uri = rewriteYaml.toUri()
                )
            } else {
                null
            }
        return buildAndActivate(recipeJars, activeRecipeName, yamlSource)
    }

    /**
     * Overload that accepts raw YAML content as a [String] instead of a file path.
     * Use this when the `rewrite.yaml` content is already in memory and writing it to
     * disk would be unnecessary I/O.
     *
     * @param rewriteYamlContent Raw YAML string, or `null` to rely solely on
     *   classpath-scanned recipes.
     */
    fun load(
        recipeJars: List<Path>,
        activeRecipeName: String,
        rewriteYamlContent: String?
    ): Recipe {
        val yamlSource: YamlSource? =
            if (rewriteYamlContent != null) {
                logger.info("Loading rewrite.yaml from string content")
                YamlSource(
                    stream = {
                        ByteArrayInputStream(rewriteYamlContent.toByteArray(Charsets.UTF_8))
                    },
                    uri = URI("string:rewrite.yaml")
                )
            } else {
                null
            }
        return buildAndActivate(recipeJars, activeRecipeName, yamlSource)
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private data class YamlSource(val stream: () -> InputStream, val uri: URI)

    private fun buildAndActivate(
        recipeJars: List<Path>,
        activeRecipeName: String,
        yamlSource: YamlSource?
    ): Recipe {
        val props = Properties()
        val parentLoader = Thread.currentThread().contextClassLoader

        // Build a class loader that includes all recipe JARs
        val recipeClassLoader =
            if (recipeJars.isNotEmpty()) {
                URLClassLoader(
                    recipeJars.map { it.toUri().toURL() }.toTypedArray(),
                    parentLoader
                )
            } else {
                parentLoader
            }

        val builder = Environment.builder()

        // Scan each recipe JAR for OpenRewrite recipes/styles/categories
        for (jar in recipeJars) {
            logger.debug("Scanning recipe JAR: $jar")
            try {
                builder.load(ClasspathScanningLoader(jar, props, emptyList(), recipeClassLoader))
            } catch (e: Exception) {
                logger.warn("Failed to scan recipe JAR $jar (skipping): ${e.message}")
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
                logger.warn("Failed to scan tool classpath (skipping): ${e.message}")
            }
        }

        // Load rewrite.yaml if provided
        if (yamlSource != null) {
            yamlSource.stream().use { stream ->
                builder.load(YamlResourceLoader(stream, yamlSource.uri, props, recipeClassLoader))
            }
        }

        val env = builder.build()
        val recipe =
            try {
                env.activateRecipes(activeRecipeName)
            } catch (e: RecipeException) {
                throw IllegalArgumentException(
                    "Recipe '$activeRecipeName' not found. " +
                        "Verify the recipe name and that the correct recipe artifact is supplied via --recipe-artifact.",
                    e
                )
            }
        require(recipe.recipeList.isNotEmpty() || recipe.name == activeRecipeName) {
            "Recipe '$activeRecipeName' not found. Verify the recipe name and that the correct recipe JAR is supplied via --recipe-artifact."
        }
        // Do NOT close recipeClassLoader here.  OpenRewrite visitor classes are loaded
        // lazily at recipe.run() time; closing the URLClassLoader before the caller runs
        // the recipe causes NoClassDefFoundError when those classes are first needed.
        // The loader is a short-lived object tied to a single CLI/library invocation and
        // will be GC'd once the run completes, so explicit close() is unnecessary.
        return recipe
    }
}
