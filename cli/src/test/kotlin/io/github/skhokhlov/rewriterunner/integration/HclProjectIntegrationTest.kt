package io.github.skhokhlov.rewriterunner.integration

import io.kotest.core.spec.style.FunSpec
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests for HCL/Terraform projects.
 *
 * Verifies that `.hcl`, `.tf`, and `.tfvars` files are correctly parsed and
 * modified by the CLI using the HclParser.
 */
class HclProjectIntegrationTest :
    FunSpec({
        var projectDir: Path = Path.of("")
        var cacheDir: Path = Path.of("")

        beforeEach {
            projectDir = Files.createTempDirectory("hcl-it-project-")
            cacheDir = Files.createTempDirectory("hcl-it-cache-")
        }

        afterEach {
            projectDir.toFile().deleteRecursively()
            cacheDir.toFile().deleteRecursively()
        }

        // ─── FindAndReplace on .tf files ──────────────────────────────────────────

        test("FindAndReplace modifies tf file content") {
            val tfFile = projectDir.resolve("main.tf")
            tfFile.writeText(
                """
                provider "aws" {
                  region = "PLACEHOLDER"
                }

                resource "aws_instance" "web" {
                  ami           = "ami-12345678"
                  instance_type = "t3.micro"
                }
                """.trimIndent()
            )
            projectDir.writeFindAndReplaceRecipe(find = "PLACEHOLDER", replace = "us-east-1")

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
                    ".tf"
                )

            assertEquals(0, result.exitCode, "stderr: ${result.stderr}")
            val content = tfFile.readText()
            assertTrue(content.contains("us-east-1"), "PLACEHOLDER should be replaced with region")
            assertTrue(!content.contains("PLACEHOLDER"), "Original placeholder should be removed")
        }

        test("FindAndReplace modifies hcl file content") {
            val hclFile = projectDir.resolve("config.hcl")
            hclFile.writeText(
                """
                variable "environment" {
                  default = "PLACEHOLDER"
                }
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
                    ".hcl"
                )

            assertEquals(0, result.exitCode, "stderr: ${result.stderr}")
            assertTrue(
                hclFile.readText().contains("production"),
                "PLACEHOLDER should be replaced in .hcl file"
            )
        }

        test("FindAndReplace modifies tfvars file content") {
            val tfvarsFile = projectDir.resolve("terraform.tfvars")
            tfvarsFile.writeText("region = \"PLACEHOLDER\"\nenvironment = \"staging\"\n")
            projectDir.writeFindAndReplaceRecipe(find = "PLACEHOLDER", replace = "eu-west-1")

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
                    ".tfvars"
                )

            assertEquals(0, result.exitCode, "stderr: ${result.stderr}")
            assertTrue(
                tfvarsFile.readText().contains("eu-west-1"),
                "PLACEHOLDER should be replaced in .tfvars file"
            )
        }

        test("FindAndReplace with --dry-run does not modify tf file") {
            val tfFile = projectDir.resolve("variables.tf")
            val original = "variable \"region\" {\n  default = \"PLACEHOLDER\"\n}\n"
            tfFile.writeText(original)
            projectDir.writeFindAndReplaceRecipe(find = "PLACEHOLDER", replace = "us-west-2")

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
                ".tf",
                "--dry-run"
            )

            assertEquals(
                original,
                tfFile.readText(),
                "--dry-run must not write tf changes to disk"
            )
        }

        // ─── Diff output ──────────────────────────────────────────────────────────

        test("FindAndReplace produces unified diff for tf file") {
            projectDir.resolve("main.tf").writeText(
                "provider \"aws\" {\n  region = \"PLACEHOLDER\"\n}\n"
            )
            projectDir.writeFindAndReplaceRecipe(find = "PLACEHOLDER", replace = "ap-southeast-1")

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
                    ".tf",
                    "--dry-run"
                )

            assertEquals(0, result.exitCode, "stderr: ${result.stderr}")
            assertTrue(result.stdout.contains("---"), "Expected unified diff:\n${result.stdout}")
            assertTrue(
                result.stdout.contains("PLACEHOLDER"),
                "Diff should show removed text:\n${result.stdout}"
            )
            assertTrue(
                result.stdout.contains("ap-southeast-1"),
                "Diff should show added text:\n${result.stdout}"
            )
        }

        // ─── Multiple HCL-family files ────────────────────────────────────────────

        test("FindAndReplace updates hcl tf and tfvars files together") {
            projectDir.resolve(
                "main.tf"
            ).writeText("provider \"aws\" {\n  region = \"PLACEHOLDER\"\n}\n")
            projectDir.resolve("config.hcl").writeText(
                "variable \"region\" {\n  default = \"PLACEHOLDER\"\n}\n"
            )
            projectDir.resolve("terraform.tfvars").writeText("region = \"PLACEHOLDER\"\n")
            projectDir.writeFindAndReplaceRecipe(find = "PLACEHOLDER", replace = "us-east-2")

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
                    ".hcl,.tf,.tfvars",
                    "--output",
                    "files",
                    "--dry-run"
                )

            assertEquals(0, result.exitCode, "stderr: ${result.stderr}")
            assertTrue(
                result.stdout.contains("main.tf") &&
                    result.stdout.contains("config.hcl") &&
                    result.stdout.contains("terraform.tfvars"),
                "All three HCL-family files should be reported as changed: ${result.stdout}"
            )
        }
    })
