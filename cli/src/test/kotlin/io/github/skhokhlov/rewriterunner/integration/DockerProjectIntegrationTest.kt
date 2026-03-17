package io.github.skhokhlov.rewriterunner.integration

import io.kotest.core.spec.style.FunSpec
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests for Docker/container projects.
 *
 * Verifies that Dockerfile and Containerfile variants are correctly parsed and
 * modified by the CLI using the DockerParser. Files are matched both by extension
 * (`.dockerfile`, `.containerfile`) and by filename prefix (`Dockerfile*`,
 * `Containerfile*`) when `.dockerfile` is in the effective extension set.
 */
class DockerProjectIntegrationTest :
    FunSpec({
        var projectDir: Path = Path.of("")
        var cacheDir: Path = Path.of("")

        beforeEach {
            projectDir = Files.createTempDirectory("docker-it-project-")
            cacheDir = Files.createTempDirectory("docker-it-cache-")
        }

        afterEach {
            projectDir.toFile().deleteRecursively()
            cacheDir.toFile().deleteRecursively()
        }

        // ─── Dockerfile without extension (name-based detection) ─────────────────

        test("FindAndReplace modifies Dockerfile (no extension) content") {
            val dockerfile = projectDir.resolve("Dockerfile")
            dockerfile.writeText(
                """
                FROM ubuntu:PLACEHOLDER
                RUN apt-get update && apt-get install -y curl
                CMD ["bash"]
                """.trimIndent()
            )
            projectDir.writeFindAndReplaceRecipe(find = "PLACEHOLDER", replace = "22.04")

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
                    ".dockerfile"
                )

            assertEquals(0, result.exitCode, "stderr: ${result.stderr}")
            val content = dockerfile.readText()
            assertTrue(
                content.contains("ubuntu:22.04"),
                "PLACEHOLDER should be replaced in Dockerfile"
            )
            assertTrue(!content.contains("PLACEHOLDER"), "Original placeholder should be removed")
        }

        test("FindAndReplace with --dry-run does not modify Dockerfile") {
            val dockerfile = projectDir.resolve("Dockerfile")
            val original = "FROM ubuntu:PLACEHOLDER\nRUN echo hello\n"
            dockerfile.writeText(original)
            projectDir.writeFindAndReplaceRecipe(find = "PLACEHOLDER", replace = "20.04")

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
                ".dockerfile",
                "--dry-run"
            )

            assertEquals(
                original,
                dockerfile.readText(),
                "--dry-run must not write Dockerfile changes to disk"
            )
        }

        // ─── Dockerfile variants (name prefix matching) ───────────────────────────

        test("FindAndReplace modifies Dockerfile.dev (name prefix match)") {
            val devDockerfile = projectDir.resolve("Dockerfile.dev")
            devDockerfile.writeText("FROM node:PLACEHOLDER-alpine\nWORKDIR /app\n")
            projectDir.writeFindAndReplaceRecipe(find = "PLACEHOLDER", replace = "20")

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
                    ".dockerfile"
                )

            assertEquals(0, result.exitCode, "stderr: ${result.stderr}")
            assertTrue(
                devDockerfile.readText().contains("node:20-alpine"),
                "PLACEHOLDER should be replaced in Dockerfile.dev"
            )
        }

        test("FindAndReplace modifies Containerfile (no extension, name prefix match)") {
            val containerfile = projectDir.resolve("Containerfile")
            containerfile.writeText("FROM fedora:PLACEHOLDER\nRUN dnf -y update\n")
            projectDir.writeFindAndReplaceRecipe(find = "PLACEHOLDER", replace = "39")

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
                    ".dockerfile"
                )

            assertEquals(0, result.exitCode, "stderr: ${result.stderr}")
            assertTrue(
                containerfile.readText().contains("fedora:39"),
                "PLACEHOLDER should be replaced in Containerfile"
            )
        }

        // ─── .dockerfile extension ────────────────────────────────────────────────

        test("FindAndReplace modifies file with .dockerfile extension") {
            val dockerfileExt = projectDir.resolve("service.dockerfile")
            dockerfileExt.writeText("FROM python:PLACEHOLDER-slim\nWORKDIR /app\n")
            projectDir.writeFindAndReplaceRecipe(find = "PLACEHOLDER", replace = "3.12")

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
                    ".dockerfile"
                )

            assertEquals(0, result.exitCode, "stderr: ${result.stderr}")
            assertTrue(
                dockerfileExt.readText().contains("python:3.12-slim"),
                "PLACEHOLDER should be replaced in .dockerfile file"
            )
        }

        // ─── Diff output ──────────────────────────────────────────────────────────

        test("FindAndReplace produces unified diff for Dockerfile") {
            projectDir.resolve("Dockerfile").writeText(
                "FROM ubuntu:PLACEHOLDER\nRUN apt-get update\n"
            )
            projectDir.writeFindAndReplaceRecipe(find = "PLACEHOLDER", replace = "24.04")

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
                    ".dockerfile",
                    "--dry-run"
                )

            assertEquals(0, result.exitCode, "stderr: ${result.stderr}")
            assertTrue(result.stdout.contains("---"), "Expected unified diff:\n${result.stdout}")
            assertTrue(
                result.stdout.contains("PLACEHOLDER"),
                "Diff should show removed text:\n${result.stdout}"
            )
            assertTrue(
                result.stdout.contains("24.04"),
                "Diff should show added text:\n${result.stdout}"
            )
        }

        // ─── Multiple Dockerfile variants ─────────────────────────────────────────

        test("FindAndReplace updates multiple Docker files across naming conventions") {
            projectDir.resolve("Dockerfile").writeText("FROM ubuntu:PLACEHOLDER\n")
            projectDir.resolve("Dockerfile.prod").writeText("FROM alpine:PLACEHOLDER\n")
            projectDir.resolve("service.dockerfile").writeText("FROM debian:PLACEHOLDER\n")
            projectDir.writeFindAndReplaceRecipe(find = "PLACEHOLDER", replace = "latest")

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
                    ".dockerfile",
                    "--output",
                    "files",
                    "--dry-run"
                )

            assertEquals(0, result.exitCode, "stderr: ${result.stderr}")
            assertTrue(
                result.stdout.contains("Dockerfile") &&
                    result.stdout.contains("Dockerfile.prod") &&
                    result.stdout.contains("service.dockerfile"),
                "All Docker files should be reported as changed: ${result.stdout}"
            )
        }
    })
