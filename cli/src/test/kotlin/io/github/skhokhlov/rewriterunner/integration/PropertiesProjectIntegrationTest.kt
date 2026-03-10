package io.github.skhokhlov.rewriterunner.integration

import io.kotest.core.spec.style.FunSpec
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests for Java .properties projects.
 *
 * Exercises [org.openrewrite.properties.ChangePropertyValue] to verify that
 * the Properties OpenRewrite parser is wired correctly and produces precise
 * key-targeted changes.
 */
class PropertiesProjectIntegrationTest :
    FunSpec({
        var projectDir: Path = Path.of("")
        var cacheDir: Path = Path.of("")

        beforeEach {
            projectDir = Files.createTempDirectory("props-it-project-")
            cacheDir = Files.createTempDirectory("props-it-cache-")
        }

        afterEach {
            projectDir.toFile().deleteRecursively()
            cacheDir.toFile().deleteRecursively()
        }

        // ─── ChangePropertyValue (structured, key-targeted) ───────────────────────

        test("ChangePropertyValue updates a single property") {
            val propsFile = projectDir.resolve("application.properties")
            propsFile.writeText(
                """
                server.port=8080
                spring.application.name=my-app
                spring.datasource.url=jdbc:h2:mem:testdb
                """.trimIndent()
            )
            projectDir.writeRewriteYaml(
                "com.example.integration.ChangePort",
                """
                  - org.openrewrite.properties.ChangePropertyValue:
                      propertyKey: server.port
                      newValue: "9090"
                """.trimIndent()
            )

            val result =
                runCli(
                    "--project-dir",
                    projectDir.toString(),
                    "--active-recipe",
                    "com.example.integration.ChangePort",
                    "--rewrite-config",
                    projectDir.resolve("rewrite.yaml").toString(),
                    "--cache-dir",
                    cacheDir.toString(),
                    "--include-extensions",
                    ".properties"
                )

            assertEquals(0, result.exitCode, "stderr: ${result.stderr}")
            val content = propsFile.readText()
            assertTrue(
                content.contains("server.port=9090"),
                "server.port should be updated to 9090"
            )
            assertTrue(
                content.contains("spring.application.name=my-app"),
                "Unrelated property should be preserved"
            )
        }

        test("ChangePropertyValue produces unified diff") {
            projectDir.resolve("application.properties").writeText(
                "server.port=8080\nlogging.level.root=INFO\n"
            )
            projectDir.writeRewriteYaml(
                "com.example.integration.ChangePort",
                """
                  - org.openrewrite.properties.ChangePropertyValue:
                      propertyKey: server.port
                      newValue: "9090"
                """.trimIndent()
            )

            val result =
                runCli(
                    "--project-dir",
                    projectDir.toString(),
                    "--active-recipe",
                    "com.example.integration.ChangePort",
                    "--rewrite-config",
                    projectDir.resolve("rewrite.yaml").toString(),
                    "--cache-dir",
                    cacheDir.toString(),
                    "--include-extensions",
                    ".properties",
                    "--dry-run"
                )

            assertEquals(0, result.exitCode, "stderr: ${result.stderr}")
            assertTrue(
                result.stdout.contains("-server.port=8080"),
                "Diff should show removed old value:\n${result.stdout}"
            )
            assertTrue(
                result.stdout.contains("+server.port=9090"),
                "Diff should show added new value:\n${result.stdout}"
            )
        }

        test("ChangePropertyValue with --dry-run does not modify properties file") {
            val propsFile = projectDir.resolve("application.properties")
            val original = "server.port=8080\n"
            propsFile.writeText(original)
            projectDir.writeRewriteYaml(
                "com.example.integration.ChangePort",
                """
                  - org.openrewrite.properties.ChangePropertyValue:
                      propertyKey: server.port
                      newValue: "9090"
                """.trimIndent()
            )

            runCli(
                "--project-dir",
                projectDir.toString(),
                "--active-recipe",
                "com.example.integration.ChangePort",
                "--rewrite-config",
                projectDir.resolve("rewrite.yaml").toString(),
                "--cache-dir",
                cacheDir.toString(),
                "--include-extensions",
                ".properties",
                "--dry-run"
            )

            assertEquals(
                original,
                propsFile.readText(),
                "--dry-run must not modify .properties files"
            )
        }

        // ─── FindAndReplace fallback ───────────────────────────────────────────────

        test("FindAndReplace also processes properties files") {
            val propsFile = projectDir.resolve("application.properties")
            propsFile.writeText("datasource.url=jdbc:postgresql://PLACEHOLDER:5432/mydb\n")
            projectDir.writeFindAndReplaceRecipe(find = "PLACEHOLDER", replace = "localhost")

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
                    ".properties"
                )

            assertEquals(0, result.exitCode, "stderr: ${result.stderr}")
            assertTrue(
                propsFile.readText().contains("localhost"),
                "Host placeholder should be replaced"
            )
        }

        // ─── Multiple .properties files ───────────────────────────────────────────

        test("ChangePropertyValue updates matching property in all properties files") {
            projectDir.resolve("application.properties").writeText("server.port=8080\n")
            projectDir.resolve("application-dev.properties").writeText(
                "server.port=8080\nlogging.level.root=DEBUG\n"
            )
            projectDir.writeRewriteYaml(
                "com.example.integration.ChangePort",
                """
                  - org.openrewrite.properties.ChangePropertyValue:
                      propertyKey: server.port
                      newValue: "9090"
                """.trimIndent()
            )

            val result =
                runCli(
                    "--project-dir",
                    projectDir.toString(),
                    "--active-recipe",
                    "com.example.integration.ChangePort",
                    "--rewrite-config",
                    projectDir.resolve("rewrite.yaml").toString(),
                    "--cache-dir",
                    cacheDir.toString(),
                    "--include-extensions",
                    ".properties",
                    "--output",
                    "files",
                    "--dry-run"
                )

            assertEquals(0, result.exitCode, "stderr: ${result.stderr}")
            assertTrue(
                result.stdout.contains("application.properties") &&
                    result.stdout.contains("application-dev.properties"),
                "Both properties files should be listed: ${result.stdout}"
            )
        }

        // ─── Spring Boot sample project structure ─────────────────────────────────

        test("ChangePropertyValue processes properties nested in src main resources") {
            val resourcesDir = projectDir.resolve("src/main/resources")
            resourcesDir.toFile().mkdirs()
            val propsFile = resourcesDir.resolve("application.properties")
            propsFile.writeText("server.port=8080\nmanagement.port=9001\n")

            projectDir.writeRewriteYaml(
                "com.example.integration.ChangePort",
                """
                  - org.openrewrite.properties.ChangePropertyValue:
                      propertyKey: server.port
                      newValue: "8443"
                """.trimIndent()
            )

            val result =
                runCli(
                    "--project-dir",
                    projectDir.toString(),
                    "--active-recipe",
                    "com.example.integration.ChangePort",
                    "--rewrite-config",
                    projectDir.resolve("rewrite.yaml").toString(),
                    "--cache-dir",
                    cacheDir.toString(),
                    "--include-extensions",
                    ".properties"
                )

            assertEquals(0, result.exitCode, "stderr: ${result.stderr}")
            assertTrue(
                propsFile.readText().contains("server.port=8443"),
                "Port in nested resources should be updated"
            )
        }
    })
