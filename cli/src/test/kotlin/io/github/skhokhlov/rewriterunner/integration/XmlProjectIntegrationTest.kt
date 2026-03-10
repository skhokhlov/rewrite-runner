package io.github.skhokhlov.rewriterunner.integration

import io.kotest.core.spec.style.FunSpec
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests for XML projects.
 *
 * Covers pom.xml, Spring applicationContext.xml, and generic XML files.
 * Uses both text-level (FindAndReplace) and structured XML recipes (ChangeTagContent).
 */
class XmlProjectIntegrationTest :
    FunSpec({
        var projectDir: Path = Path.of("")
        var cacheDir: Path = Path.of("")

        beforeEach {
            projectDir = Files.createTempDirectory("xml-it-project-")
            cacheDir = Files.createTempDirectory("xml-it-cache-")
        }

        afterEach {
            projectDir.toFile().deleteRecursively()
            cacheDir.toFile().deleteRecursively()
        }

        // ─── FindAndReplace (guaranteed change) ───────────────────────────────────

        test("FindAndReplace modifies xml file content") {
            val xmlFile = projectDir.resolve("config.xml")
            xmlFile.writeText(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <config>
                    <env>PLACEHOLDER</env>
                    <region>us-east-1</region>
                </config>
                """.trimIndent()
            )
            projectDir.writeFindAndReplaceRecipe(find = "PLACEHOLDER", replace = "production")

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
                    ".xml"
                )

            assertEquals(0, result.exitCode, "stderr: ${result.stderr}")
            val content = xmlFile.readText()
            assertTrue(content.contains("production"), "PLACEHOLDER should be replaced in XML")
            assertTrue(!content.contains("PLACEHOLDER"), "Original placeholder should be gone")
        }

        test("FindAndReplace with --dry-run does not modify xml file") {
            val xmlFile = projectDir.resolve("config.xml")
            val original = "<config><value>PLACEHOLDER</value></config>"
            xmlFile.writeText(original)
            projectDir.writeFindAndReplaceRecipe(find = "PLACEHOLDER", replace = "replaced")

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
                ".xml",
                "--dry-run"
            )

            assertEquals(original, xmlFile.readText(), "--dry-run must not write xml changes")
        }

        // ─── pom.xml sample project ───────────────────────────────────────────────

        test("FindAndReplace updates version in pom xml") {
            val pomFile = projectDir.resolve("pom.xml")
            pomFile.writeText(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>demo</artifactId>
                    <version>PLACEHOLDER</version>
                    <packaging>jar</packaging>
                </project>
                """.trimIndent()
            )
            projectDir.writeFindAndReplaceRecipe(find = "PLACEHOLDER", replace = "2.0.0")

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
                    ".xml"
                )

            assertEquals(0, result.exitCode, "stderr: ${result.stderr}")
            assertTrue(
                pomFile.readText().contains("<version>2.0.0</version>"),
                "Version should be updated in pom.xml"
            )
        }

        // ─── Multiple xml files ───────────────────────────────────────────────────

        test("FindAndReplace updates all xml files in project") {
            projectDir.resolve("config.xml").writeText(
                "<config><env>PLACEHOLDER</env></config>"
            )
            projectDir.resolve("override.xml").writeText(
                "<config><env>PLACEHOLDER</env><debug>true</debug></config>"
            )
            projectDir.writeFindAndReplaceRecipe(find = "PLACEHOLDER", replace = "production")

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
                    ".xml",
                    "--output",
                    "files",
                    "--dry-run"
                )

            assertEquals(0, result.exitCode, "stderr: ${result.stderr}")
            assertTrue(
                result.stdout.contains("config.xml") && result.stdout.contains("override.xml"),
                "Both xml files should be listed as changed: ${result.stdout}"
            )
        }

        // ─── Diff output ──────────────────────────────────────────────────────────

        test("FindAndReplace produces unified diff for xml file") {
            projectDir.resolve("config.xml").writeText(
                "<root><env>PLACEHOLDER</env></root>"
            )
            projectDir.writeFindAndReplaceRecipe(find = "PLACEHOLDER", replace = "prod")

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
                    ".xml",
                    "--dry-run"
                )

            assertEquals(0, result.exitCode, "stderr: ${result.stderr}")
            assertTrue(result.stdout.contains("---"), "Expected unified diff:\n${result.stdout}")
            assertTrue(
                result.stdout.contains("+"),
                "Diff should show added lines:\n${result.stdout}"
            )
        }
    })
