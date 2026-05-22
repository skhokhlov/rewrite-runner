package io.github.skhokhlov.rewriterunner.integration

import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * A single Stage 0 plugin-execution scenario: project layout, recipe configuration,
 * and expected outcomes.
 *
 * Consumed by both [PluginFirstIntegrationTest] (fake-wrapper lane, fast/offline) and
 * [PluginRealExecutionIntegrationTest] (real plugin lane, network-required).
 *
 * Active recipes are sourced from `rewrite-core` (Apache 2.0) only — no `org.openrewrite.recipe.*`
 * artifacts. Adding a scenario that depends on a coordinate outside that license envelope is a
 * regression; vet the artifact's `LICENSE` before adding any new `recipeArtifacts` entry.
 */
data class PluginScenario(
    val name: String,
    val setUpProject: (Path) -> Unit,
    val activeRecipe: String,
    val recipeArtifacts: List<String> = emptyList(),
    /** Relative path → expected file content after the recipe apply step. */
    val expectedAfterFiles: Map<String, String>,
    /** Substrings that must appear in `--dry-run --output diff` stdout. */
    val expectedDryRunDiffContains: List<String>
)

/** Pre-defined Stage 0 scenarios shared across the fake-wrapper and real-wrapper test suites. */
object PluginScenarios {
    private const val FIND_AND_REPLACE_RECIPE = "com.example.integration.FindAndReplace"

    val gradleSingleFile: PluginScenario = PluginScenario(
        name = "Gradle single-file FindAndReplace",
        setUpProject = { dir ->
            // settings.gradle.kts is required by Gradle 9; java plugin declares src/main/java.
            dir.resolve("settings.gradle.kts").writeText("rootProject.name = \"test\"\n")
            dir.resolve("build.gradle.kts").writeText("plugins { java }\n")
            dir.resolve("src/main/java").toFile().mkdirs()
            dir.resolve("src/main/java/App.java").writeText("class App{}\n")
            dir.writeFindAndReplaceRecipe(find = "class App{}", replace = "class App { }")
        },
        activeRecipe = FIND_AND_REPLACE_RECIPE,
        expectedAfterFiles = mapOf("src/main/java/App.java" to "class App { }\n"),
        expectedDryRunDiffContains = listOf("-class App{}", "+class App { }")
    )

    val mavenSingleFile: PluginScenario = PluginScenario(
        name = "Maven single-file FindAndReplace",
        setUpProject = { dir ->
            // Minimal but valid POM; <project/> fails Maven model validation.
            dir.resolve("pom.xml").writeText(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>test</artifactId>
                  <version>1.0-SNAPSHOT</version>
                </project>
                """.trimIndent()
            )
            dir.resolve("src/main/java").toFile().mkdirs()
            dir.resolve("src/main/java/App.java").writeText("class App{}\n")
            dir.writeFindAndReplaceRecipe(find = "class App{}", replace = "class App { }")
        },
        activeRecipe = FIND_AND_REPLACE_RECIPE,
        expectedAfterFiles = mapOf("src/main/java/App.java" to "class App { }\n"),
        expectedDryRunDiffContains = listOf("-class App{}", "+class App { }")
    )

    val mavenMultiModule: PluginScenario = PluginScenario(
        name = "Maven multi-module FindAndReplace",
        setUpProject = { dir ->
            dir.resolve("pom.xml").writeText(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0-SNAPSHOT</version>
                  <packaging>pom</packaging>
                  <modules>
                    <module>module-a</module>
                    <module>module-b</module>
                  </modules>
                </project>
                """.trimIndent()
            )
            dir.resolve("module-a").toFile().mkdirs()
            dir.resolve("module-a/pom.xml").writeText(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>com.example</groupId>
                    <artifactId>parent</artifactId>
                    <version>1.0-SNAPSHOT</version>
                  </parent>
                  <artifactId>module-a</artifactId>
                </project>
                """.trimIndent()
            )
            dir.resolve("module-a/src/main/java").toFile().mkdirs()
            dir.resolve("module-a/src/main/java/AppA.java").writeText("// FINDME\nclass AppA{}\n")
            dir.resolve("module-b").toFile().mkdirs()
            dir.resolve("module-b/pom.xml").writeText(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>com.example</groupId>
                    <artifactId>parent</artifactId>
                    <version>1.0-SNAPSHOT</version>
                  </parent>
                  <artifactId>module-b</artifactId>
                </project>
                """.trimIndent()
            )
            dir.resolve("module-b/src/main/java").toFile().mkdirs()
            dir.resolve("module-b/src/main/java/AppB.java").writeText("// FINDME\nclass AppB{}\n")
            dir.writeFindAndReplaceRecipe(find = "FINDME", replace = "REPLACED")
        },
        activeRecipe = FIND_AND_REPLACE_RECIPE,
        expectedAfterFiles = mapOf(
            "module-a/src/main/java/AppA.java" to "// REPLACED\nclass AppA{}\n",
            "module-b/src/main/java/AppB.java" to "// REPLACED\nclass AppB{}\n"
        ),
        expectedDryRunDiffContains = listOf("-// FINDME", "+// REPLACED")
    )

    val gradleMultiProject: PluginScenario = PluginScenario(
        name = "Gradle multi-project FindAndReplace",
        setUpProject = { dir ->
            dir.resolve("settings.gradle.kts").writeText(
                "rootProject.name = \"multi\"\ninclude(\"module-a\", \"module-b\")\n"
            )
            dir.resolve("build.gradle.kts").writeText(
                "subprojects { apply(plugin = \"java\") }\n"
            )
            dir.resolve("module-a").toFile().mkdirs()
            dir.resolve("module-a/build.gradle.kts").writeText("")
            dir.resolve("module-a/src/main/java").toFile().mkdirs()
            dir.resolve("module-a/src/main/java/AppA.java").writeText("// FINDME\nclass AppA{}\n")
            dir.resolve("module-b").toFile().mkdirs()
            dir.resolve("module-b/build.gradle.kts").writeText("")
            dir.resolve("module-b/src/main/java").toFile().mkdirs()
            dir.resolve("module-b/src/main/java/AppB.java").writeText("// FINDME\nclass AppB{}\n")
            dir.writeFindAndReplaceRecipe(find = "FINDME", replace = "REPLACED")
        },
        activeRecipe = FIND_AND_REPLACE_RECIPE,
        expectedAfterFiles = mapOf(
            "module-a/src/main/java/AppA.java" to "// REPLACED\nclass AppA{}\n",
            "module-b/src/main/java/AppB.java" to "// REPLACED\nclass AppB{}\n"
        ),
        expectedDryRunDiffContains = listOf("-// FINDME", "+// REPLACED")
    )

    // No `--recipe-artifact` scenarios are exercised against the real plugin: every OpenRewrite
    // recipe artifact outside `rewrite-core` (e.g. `rewrite-static-analysis`) ships under the
    // Moderne Source Available license, which is incompatible with this project's distribution
    // terms. Coordinate-resolution for `--recipe-artifact` is covered by `RecipeArtifactResolver`
    // unit tests against permissive coordinates; do not add scenarios here that pull MSAL JARs.
}
