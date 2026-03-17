package io.github.skhokhlov.rewriterunner.integration

import io.kotest.core.spec.style.FunSpec
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Integration tests for Maven POM projects.
 *
 * Verifies that `pom.xml` files are correctly parsed by [org.openrewrite.maven.MavenParser]
 * and can be modified by both generic recipes (via [org.openrewrite.text.FindAndReplace]) and
 * Maven-specific recipes (via `org.openrewrite.maven.ChangeParentPomVersion` etc.).
 *
 * All tests use `--include-extensions .xml` to exercise the MavenParser routing path.
 */
class MavenProjectIntegrationTest :
    FunSpec({
        var projectDir: Path = Path.of("")
        var cacheDir: Path = Path.of("")

        beforeEach {
            projectDir = Files.createTempDirectory("maven-it-project-")
            cacheDir = Files.createTempDirectory("maven-it-cache-")
        }

        afterEach {
            projectDir.toFile().deleteRecursively()
            cacheDir.toFile().deleteRecursively()
        }

        // ─── FindAndReplace (text-level, confirms pom.xml is parsed) ─────────────

        test("FindAndReplace modifies pom.xml content") {
            val pomFile = projectDir.resolve("pom.xml")
            pomFile.writeText(
                """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>my-app</artifactId>
                  <version>PLACEHOLDER</version>
                </project>
                """.trimIndent()
            )
            projectDir.writeFindAndReplaceRecipe(find = "PLACEHOLDER", replace = "1.0.0")

            val result =
                runCli(
                    "--project-dir", projectDir.toString(),
                    "--active-recipe", "com.example.integration.FindAndReplace",
                    "--rewrite-config", projectDir.resolve("rewrite.yaml").toString(),
                    "--cache-dir", cacheDir.toString(),
                    "--include-extensions", ".xml"
                )

            assertEquals(0, result.exitCode, "stderr: ${result.stderr}")
            val content = pomFile.readText()
            assertTrue(content.contains("1.0.0"), "PLACEHOLDER should be replaced with 1.0.0")
            assertFalse(content.contains("PLACEHOLDER"), "Original placeholder should be removed")
        }

        test("FindAndReplace with --dry-run does not modify pom.xml") {
            val pomFile = projectDir.resolve("pom.xml")
            val original =
                "<project><modelVersion>4.0.0</modelVersion>" +
                    "<groupId>com.example</groupId>" +
                    "<artifactId>app</artifactId>" +
                    "<version>PLACEHOLDER</version></project>"
            pomFile.writeText(original)
            projectDir.writeFindAndReplaceRecipe(find = "PLACEHOLDER", replace = "2.0.0")

            runCli(
                "--project-dir", projectDir.toString(),
                "--active-recipe", "com.example.integration.FindAndReplace",
                "--rewrite-config", projectDir.resolve("rewrite.yaml").toString(),
                "--cache-dir", cacheDir.toString(),
                "--include-extensions", ".xml",
                "--dry-run"
            )

            assertEquals(original, pomFile.readText(), "--dry-run must not write pom.xml changes")
        }

        // ─── Diff output ──────────────────────────────────────────────────────────

        test("FindAndReplace produces unified diff for pom.xml") {
            projectDir.resolve("pom.xml").writeText(
                "<project><modelVersion>4.0.0</modelVersion>" +
                    "<groupId>com.example</groupId>" +
                    "<artifactId>app</artifactId>" +
                    "<version>PLACEHOLDER</version></project>"
            )
            projectDir.writeFindAndReplaceRecipe(find = "PLACEHOLDER", replace = "3.0.0")

            val result =
                runCli(
                    "--project-dir", projectDir.toString(),
                    "--active-recipe", "com.example.integration.FindAndReplace",
                    "--rewrite-config", projectDir.resolve("rewrite.yaml").toString(),
                    "--cache-dir", cacheDir.toString(),
                    "--include-extensions", ".xml",
                    "--dry-run"
                )

            assertEquals(0, result.exitCode, "stderr: ${result.stderr}")
            assertTrue(result.stdout.contains("---"), "Expected unified diff:\n${result.stdout}")
            assertTrue(
                result.stdout.contains("PLACEHOLDER"),
                "Diff should show removed text:\n${result.stdout}"
            )
            assertTrue(
                result.stdout.contains("3.0.0"),
                "Diff should show added text:\n${result.stdout}"
            )
        }

        // ─── pom.xml alongside other xml files ────────────────────────────────────

        test("pom.xml and other xml files are both modified") {
            projectDir.resolve("pom.xml").writeText(
                "<project><modelVersion>4.0.0</modelVersion>" +
                    "<groupId>com.example</groupId>" +
                    "<artifactId>app</artifactId>" +
                    "<version>PLACEHOLDER</version></project>"
            )
            val logbackFile = projectDir.resolve("logback.xml")
            logbackFile.writeText("<configuration><appender name=\"PLACEHOLDER\"/></configuration>")
            projectDir.writeFindAndReplaceRecipe(find = "PLACEHOLDER", replace = "REPLACED")

            val result =
                runCli(
                    "--project-dir", projectDir.toString(),
                    "--active-recipe", "com.example.integration.FindAndReplace",
                    "--rewrite-config", projectDir.resolve("rewrite.yaml").toString(),
                    "--cache-dir", cacheDir.toString(),
                    "--include-extensions", ".xml",
                    "--output", "files",
                    "--dry-run"
                )

            assertEquals(0, result.exitCode, "stderr: ${result.stderr}")
            assertTrue(
                result.stdout.contains("pom.xml") && result.stdout.contains("logback.xml"),
                "Both xml files should be reported as changed: ${result.stdout}"
            )
        }

        // ─── Multi-module Maven project ───────────────────────────────────────────

        test("pom.xml files in submodules are all parsed and modified") {
            projectDir.resolve("pom.xml").writeText(
                "<project><modelVersion>4.0.0</modelVersion>" +
                    "<groupId>com.example</groupId>" +
                    "<artifactId>parent</artifactId>" +
                    "<version>PLACEHOLDER</version>" +
                    "<packaging>pom</packaging></project>"
            )
            val moduleDir = projectDir.resolve("module-a")
            moduleDir.toFile().mkdirs()
            moduleDir.resolve("pom.xml").writeText(
                "<project><modelVersion>4.0.0</modelVersion>" +
                    "<parent><groupId>com.example</groupId>" +
                    "<artifactId>parent</artifactId>" +
                    "<version>PLACEHOLDER</version></parent>" +
                    "<artifactId>module-a</artifactId></project>"
            )
            projectDir.writeFindAndReplaceRecipe(find = "PLACEHOLDER", replace = "1.2.3")

            val result =
                runCli(
                    "--project-dir", projectDir.toString(),
                    "--active-recipe", "com.example.integration.FindAndReplace",
                    "--rewrite-config", projectDir.resolve("rewrite.yaml").toString(),
                    "--cache-dir", cacheDir.toString(),
                    "--include-extensions", ".xml",
                    "--output", "files",
                    "--dry-run"
                )

            assertEquals(0, result.exitCode, "stderr: ${result.stderr}")
            assertTrue(
                result.stdout.contains("pom.xml"),
                "At least one pom.xml should be reported as changed: ${result.stdout}"
            )
        }
    })
