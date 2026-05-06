package io.github.skhokhlov.rewriterunner

import io.github.skhokhlov.rewriterunner.config.RepositoryConfig
import io.kotest.core.spec.style.FunSpec
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val isWindows = System.getProperty("os.name", "").lowercase().contains("windows")

class RewriteRunnerTest :
    FunSpec({
        var projectDir: Path = Path.of("")
        var cacheDir: Path = Path.of("")

        beforeEach {
            projectDir = Files.createTempDirectory("orrt-project-")
            cacheDir = Files.createTempDirectory("orrt-cache-")
        }

        afterEach {
            projectDir.toFile().deleteRecursively()
            cacheDir.toFile().deleteRecursively()
        }

        /**
         * Builds a rewrite.yaml that uses DeleteSourceFiles to remove all .properties files,
         * then runs RewriteRunner against a project containing one such file.
         */
        fun runDeletePropertiesRecipe(dryRun: Boolean = false) {
            projectDir.resolve("rewrite.yaml").writeText(
                """
                ---
                type: specs.openrewrite.org/v1beta/recipe
                name: com.test.DeleteProperties
                recipeList:
                  - org.openrewrite.DeleteSourceFiles:
                      filePattern: "**/*.properties"
                """.trimIndent()
            )

            RewriteRunner.builder()
                .projectDir(projectDir)
                .activeRecipe("com.test.DeleteProperties")
                .cacheDir(cacheDir)
                .dryRun(dryRun)
                .build()
                .run()
        }

        // ─── File deletion ────────────────────────────────────────────────────────

        test("recipe-deleted files are removed from disk") {
            // Put the file in a subdirectory so "**/*.properties" glob matches unambiguously
            projectDir.resolve("config").toFile().mkdirs()
            val propsFile = projectDir.resolve("config/app.properties")
            propsFile.writeText("key=value\n")
            assertTrue(propsFile.exists(), "File should exist before run")

            runDeletePropertiesRecipe()

            assertFalse(propsFile.exists(), "Recipe-deleted file should be removed from disk")
        }

        test("dry-run does not delete recipe-deleted files") {
            projectDir.resolve("config").toFile().mkdirs()
            val propsFile = projectDir.resolve("config/app.properties")
            propsFile.writeText("key=value\n")

            runDeletePropertiesRecipe(dryRun = true)

            assertTrue(propsFile.exists(), "Dry-run must not delete files from disk")
        }

        // ─── excludePaths builder property ───────────────────────────────────────

        test("builder excludePaths skips matching files") {
            projectDir.resolve("excluded").toFile().mkdirs()
            projectDir.resolve("included").toFile().mkdirs()
            val excludedFile = projectDir.resolve("excluded/app.properties")
            val includedFile = projectDir.resolve("included/app.properties")
            excludedFile.writeText("key=value\n")
            includedFile.writeText("key=value\n")

            projectDir.resolve("rewrite.yaml").writeText(
                """
                ---
                type: specs.openrewrite.org/v1beta/recipe
                name: com.test.DeleteProperties
                recipeList:
                  - org.openrewrite.DeleteSourceFiles:
                      filePattern: "**/*.properties"
                """.trimIndent()
            )

            RewriteRunner.builder()
                .projectDir(projectDir)
                .activeRecipe("com.test.DeleteProperties")
                .cacheDir(cacheDir)
                .excludePaths(listOf("excluded/**"))
                .build()
                .run()

            assertTrue(excludedFile.exists(), "File in excluded path should not be deleted")
            assertFalse(includedFile.exists(), "File in included path should be deleted")
        }

        // ─── repositories builder property ───────────────────────────────────────

        test("builder repositories are accepted without error") {
            // Verify that supplying a RepositoryConfig programmatically does not throw.
            // Full resolution behaviour is covered by integration tests.
            projectDir.resolve("rewrite.yaml").writeText(
                """
                ---
                type: specs.openrewrite.org/v1beta/recipe
                name: com.test.DeleteProperties
                recipeList:
                  - org.openrewrite.DeleteSourceFiles:
                      filePattern: "**/*.properties"
                """.trimIndent()
            )

            RewriteRunner.builder()
                .projectDir(projectDir)
                .activeRecipe("com.test.DeleteProperties")
                .cacheDir(cacheDir)
                .artifactRepository(RepositoryConfig(url = "https://repo.example.com/maven"))
                .artifactRepositories(
                    listOf(RepositoryConfig(url = "https://other.example.com/maven"))
                )
                .build()
                .run()
            // No assertion needed — the test passes if no exception is thrown
        }

        // ─── timeout propagation ────────────────────────────────────────────────

        test("builder process timeout override reaches fallback ToolConfig").config(
            enabled = !isWindows
        ) {
            projectDir.resolve("pom.xml").writeText("<project/>")
            projectDir.resolve("Hello.java").writeText("class Hello {}")
            val completedMarker = projectDir.resolve("mvnw-completed")
            val mvnw = projectDir.resolve("mvnw").toFile()
            mvnw.writeText(
                """
                #!/bin/sh
                sleep 2
                touch "${completedMarker.toAbsolutePath()}"
                exit 0
                """.trimIndent()
            )
            mvnw.setExecutable(true)

            RewriteRunner.builder()
                .projectDir(projectDir)
                .activeRecipe("org.openrewrite.FindSourceFiles")
                .cacheDir(cacheDir)
                .skipPluginRun(true)
                .subprocessRunTimeout(Duration.ofMillis(100))
                .build()
                .run()

            assertFalse(
                completedMarker.exists(),
                "Configured process timeout should stop the wrapper before it completes"
            )
        }

        // ─── plugin version config ──────────────────────────────────────────────

        test("plugin-first Gradle run uses configured rewrite plugin version").config(
            enabled = !isWindows
        ) {
            val marker = projectDir.resolve("plugin-version-used.txt")
            val configFile = projectDir.resolve("rewriterunner.yml")
            configFile.writeText("rewriteGradlePluginVersion: 7.20.0")
            projectDir.resolve("build.gradle.kts").writeText("")
            val gradlew = projectDir.resolve("gradlew").toFile()
            gradlew.writeText(
                """
                #!/bin/sh
                marker="${marker.toAbsolutePath()}"
                init_script=""
                previous=""
                for arg in "${'$'}@"; do
                    if [ "${'$'}previous" = "--init-script" ]; then
                        init_script="${'$'}arg"
                    fi
                    previous="${'$'}arg"
                done
                if grep -q 'org.openrewrite:plugin:7.20.0' "${'$'}init_script"; then
                    echo "${'$'}1" > "${'$'}marker"
                    exit 0
                fi
                echo "configured Gradle plugin version was not used" > "${'$'}marker"
                exit 1
                """.trimIndent()
            )
            gradlew.setExecutable(true)

            RewriteRunner.builder()
                .projectDir(projectDir)
                .activeRecipe("com.example.Recipe")
                .configFile(configFile)
                .dryRun(true)
                .build()
                .run()

            assertEquals("rewriteDryRun", marker.readText().trim())
        }

        test("plugin-first Maven run uses configured rewrite plugin version").config(
            enabled = !isWindows
        ) {
            val marker = projectDir.resolve("plugin-version-used.txt")
            val configFile = projectDir.resolve("rewriterunner.yml")
            configFile.writeText("rewriteMavenPluginVersion: 6.23.0")
            projectDir.resolve("pom.xml").writeText("<project/>")
            val mvnw = projectDir.resolve("mvnw").toFile()
            mvnw.writeText(
                """
                #!/bin/sh
                marker="${marker.toAbsolutePath()}"
                for arg in "${'$'}@"; do
                    if [ "${'$'}arg" = "org.openrewrite.maven:rewrite-maven-plugin:6.23.0:dryRun" ]; then
                        echo "${'$'}arg" > "${'$'}marker"
                        exit 0
                    fi
                done
                echo "configured Maven plugin version was not used" > "${'$'}marker"
                exit 1
                """.trimIndent()
            )
            mvnw.setExecutable(true)

            RewriteRunner.builder()
                .projectDir(projectDir)
                .activeRecipe("com.example.Recipe")
                .configFile(configFile)
                .dryRun(true)
                .build()
                .run()

            assertEquals(
                "org.openrewrite.maven:rewrite-maven-plugin:6.23.0:dryRun",
                marker.readText().trim()
            )
        }
    })
