package io.github.skhokhlov.rewriterunner.integration

import io.kotest.core.spec.style.FunSpec
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests for Groovy and Gradle Groovy DSL projects.
 *
 * Verifies that `.groovy` source files and `.gradle` Groovy DSL build scripts are
 * parsed by the Groovy OpenRewrite parser and that recipes can modify them.
 *
 * The 3-stage classpath resolution pipeline (build-tool → Maven Resolver → local cache)
 * applies to the GroovyParser the same way it does to JavaParser and KotlinParser —
 * the resolved classpath is shared across all language parsers per [build] invocation.
 * In addition, `.gradle` files receive the Gradle DSL classpath on top of the project
 * classpath, enabling Gradle API types to resolve in build scripts.
 */
class GroovyProjectIntegrationTest :
    FunSpec({
        var projectDir: Path = Path.of("")
        var cacheDir: Path = Path.of("")

        beforeEach {
            projectDir = Files.createTempDirectory("groovy-it-project-")
            cacheDir = Files.createTempDirectory("groovy-it-cache-")
        }

        afterEach {
            projectDir.toFile().deleteRecursively()
            cacheDir.toFile().deleteRecursively()
        }

        // ─── Groovy source files (.groovy) ────────────────────────────────────────

        test("FindAndReplace modifies groovy source file content") {
            val groovyFile = projectDir.resolve("Helper.groovy")
            groovyFile.writeText(
                """
                class Helper {
                    String greet() { return "PLACEHOLDER" }
                }
                """.trimIndent()
            )
            projectDir.writeFindAndReplaceRecipe(find = "PLACEHOLDER", replace = "hello")

            val result =
                runCli(
                    "--project-dir",
                    projectDir.toString(),
                    "--active-recipe",
                    "com.example.integration.FindAndReplace",
                    "--rewrite-config",
                    projectDir.resolve("rewrite.yaml").toString(),
                    "--cache-dir",
                    cacheDir.toString(),
                    "--include-extensions",
                    ".groovy"
                )

            assertEquals(0, result.exitCode, "stderr: ${result.stderr}")
            assertTrue(
                groovyFile.readText().contains("hello"),
                "PLACEHOLDER should be replaced in .groovy file"
            )
            assertTrue(
                !groovyFile.readText().contains("PLACEHOLDER"),
                "Original placeholder should not remain"
            )
        }

        test("groovy files are included by default without explicit include-extensions") {
            val groovyFile = projectDir.resolve("Service.groovy")
            groovyFile.writeText("class Service { def name = 'PLACEHOLDER' }")
            projectDir.writeFindAndReplaceRecipe(find = "PLACEHOLDER", replace = "my-service")

            val result =
                runCli(
                    "--project-dir",
                    projectDir.toString(),
                    "--active-recipe",
                    "com.example.integration.FindAndReplace",
                    "--rewrite-config",
                    projectDir.resolve("rewrite.yaml").toString(),
                    "--cache-dir",
                    cacheDir.toString()
                )

            assertEquals(0, result.exitCode, "stderr: ${result.stderr}")
            assertTrue(
                groovyFile.readText().contains("my-service"),
                ".groovy file should be processed by default"
            )
        }

        test("FindAndReplace with --dry-run does not modify groovy file") {
            val groovyFile = projectDir.resolve("App.groovy")
            val original = "class App { def version = 'PLACEHOLDER' }"
            groovyFile.writeText(original)
            projectDir.writeFindAndReplaceRecipe(find = "PLACEHOLDER", replace = "1.0.0")

            runCli(
                "--project-dir",
                projectDir.toString(),
                "--active-recipe",
                "com.example.integration.FindAndReplace",
                "--rewrite-config",
                projectDir.resolve("rewrite.yaml").toString(),
                "--cache-dir",
                cacheDir.toString(),
                "--include-extensions",
                ".groovy",
                "--dry-run"
            )

            assertEquals(
                original,
                groovyFile.readText(),
                "--dry-run must not write changes for .groovy files"
            )
        }

        // ─── Gradle Groovy DSL build scripts (.gradle) ────────────────────────────

        test("FindAndReplace modifies gradle build script content") {
            val gradleFile = projectDir.resolve("build.gradle")
            gradleFile.writeText(
                """
                plugins {
                    id 'java'
                }
                group = 'PLACEHOLDER'
                """.trimIndent()
            )
            projectDir.writeFindAndReplaceRecipe(find = "PLACEHOLDER", replace = "com.example")

            val result =
                runCli(
                    "--project-dir",
                    projectDir.toString(),
                    "--active-recipe",
                    "com.example.integration.FindAndReplace",
                    "--rewrite-config",
                    projectDir.resolve("rewrite.yaml").toString(),
                    "--cache-dir",
                    cacheDir.toString(),
                    "--include-extensions",
                    ".gradle"
                )

            assertEquals(0, result.exitCode, "stderr: ${result.stderr}")
            assertTrue(
                gradleFile.readText().contains("com.example"),
                "PLACEHOLDER should be replaced in .gradle file"
            )
            assertTrue(
                !gradleFile.readText().contains("PLACEHOLDER"),
                "Original placeholder should not remain in .gradle file"
            )
        }

        test("gradle files are included by default without explicit include-extensions") {
            val gradleFile = projectDir.resolve("settings.gradle")
            gradleFile.writeText("rootProject.name = 'PLACEHOLDER'")
            projectDir.writeFindAndReplaceRecipe(find = "PLACEHOLDER", replace = "my-project")

            val result =
                runCli(
                    "--project-dir",
                    projectDir.toString(),
                    "--active-recipe",
                    "com.example.integration.FindAndReplace",
                    "--rewrite-config",
                    projectDir.resolve("rewrite.yaml").toString(),
                    "--cache-dir",
                    cacheDir.toString()
                )

            assertEquals(0, result.exitCode, "stderr: ${result.stderr}")
            assertTrue(
                gradleFile.readText().contains("my-project"),
                ".gradle file should be processed by default"
            )
        }

        test("only gradle files are modified when include-extensions is dot-gradle") {
            val groovyFile = projectDir.resolve("Helper.groovy")
            val gradleFile = projectDir.resolve("build.gradle")
            groovyFile.writeText("class Helper { def x = 'PLACEHOLDER' }")
            gradleFile.writeText("group = 'PLACEHOLDER'")
            projectDir.writeFindAndReplaceRecipe(find = "PLACEHOLDER", replace = "REPLACED")

            runCli(
                "--project-dir",
                projectDir.toString(),
                "--active-recipe",
                "com.example.integration.FindAndReplace",
                "--rewrite-config",
                projectDir.resolve("rewrite.yaml").toString(),
                "--cache-dir",
                cacheDir.toString(),
                "--include-extensions",
                ".gradle"
            )

            assertEquals(
                "class Helper { def x = 'PLACEHOLDER' }",
                groovyFile.readText(),
                ".groovy file should not be touched when only .gradle is included"
            )
            assertTrue(
                gradleFile.readText().contains("REPLACED"),
                ".gradle file should be modified"
            )
        }

        test("both groovy and gradle files are processed when no include-extensions is set") {
            val groovyFile = projectDir.resolve("Helper.groovy")
            val gradleFile = projectDir.resolve("build.gradle")
            groovyFile.writeText("def x = 'PLACEHOLDER'")
            gradleFile.writeText("group = 'PLACEHOLDER'")
            projectDir.writeFindAndReplaceRecipe(find = "PLACEHOLDER", replace = "REPLACED")

            runCli(
                "--project-dir",
                projectDir.toString(),
                "--active-recipe",
                "com.example.integration.FindAndReplace",
                "--rewrite-config",
                projectDir.resolve("rewrite.yaml").toString(),
                "--cache-dir",
                cacheDir.toString()
            )

            assertTrue(
                groovyFile.readText().contains("REPLACED"),
                ".groovy file should be modified"
            )
            assertTrue(
                gradleFile.readText().contains("REPLACED"),
                ".gradle file should be modified"
            )
        }
    })
