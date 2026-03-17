package io.github.skhokhlov.rewriterunner.integration

import io.kotest.core.spec.style.FunSpec
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests for Protobuf projects.
 *
 * Verifies that `.proto` files are correctly parsed and modified by the CLI
 * using the ProtoParser.
 */
class ProtoProjectIntegrationTest :
    FunSpec({
        var projectDir: Path = Path.of("")
        var cacheDir: Path = Path.of("")

        beforeEach {
            projectDir = Files.createTempDirectory("proto-it-project-")
            cacheDir = Files.createTempDirectory("proto-it-cache-")
        }

        afterEach {
            projectDir.toFile().deleteRecursively()
            cacheDir.toFile().deleteRecursively()
        }

        // ─── FindAndReplace (text-level, guaranteed change) ───────────────────────

        test("FindAndReplace modifies proto file content") {
            val protoFile = projectDir.resolve("hello.proto")
            protoFile.writeText(
                """
                syntax = "proto3";
                package PLACEHOLDER;

                message HelloRequest {
                  string name = 1;
                }

                message HelloReply {
                  string message = 1;
                }
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
                    ".proto"
                )

            assertEquals(0, result.exitCode, "stderr: ${result.stderr}")
            val content = protoFile.readText()
            assertTrue(
                content.contains("com.example"),
                "PLACEHOLDER should be replaced with package name"
            )
            assertTrue(!content.contains("PLACEHOLDER"), "Original placeholder should be removed")
        }

        test("FindAndReplace with --dry-run does not modify proto file") {
            val protoFile = projectDir.resolve("service.proto")
            val original =
                "syntax = \"proto3\";\npackage PLACEHOLDER;\nmessage Empty {}\n"
            protoFile.writeText(original)
            projectDir.writeFindAndReplaceRecipe(find = "PLACEHOLDER", replace = "example.v1")

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
                ".proto",
                "--dry-run"
            )

            assertEquals(
                original,
                protoFile.readText(),
                "--dry-run must not write proto changes to disk"
            )
        }

        // ─── Diff output ──────────────────────────────────────────────────────────

        test("FindAndReplace produces unified diff for proto file") {
            projectDir.resolve("api.proto").writeText(
                "syntax = \"proto3\";\npackage PLACEHOLDER;\nmessage Request {}\n"
            )
            projectDir.writeFindAndReplaceRecipe(find = "PLACEHOLDER", replace = "api.v2")

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
                    ".proto",
                    "--dry-run"
                )

            assertEquals(0, result.exitCode, "stderr: ${result.stderr}")
            assertTrue(result.stdout.contains("---"), "Expected unified diff:\n${result.stdout}")
            assertTrue(
                result.stdout.contains("PLACEHOLDER"),
                "Diff should show removed text:\n${result.stdout}"
            )
            assertTrue(
                result.stdout.contains("api.v2"),
                "Diff should show added text:\n${result.stdout}"
            )
        }

        // ─── Multiple proto files ─────────────────────────────────────────────────

        test("FindAndReplace updates all proto files in project") {
            projectDir.resolve("user.proto").writeText(
                "syntax = \"proto3\";\npackage PLACEHOLDER;\nmessage User {}\n"
            )
            projectDir.resolve("order.proto").writeText(
                "syntax = \"proto3\";\npackage PLACEHOLDER;\nmessage Order {}\n"
            )
            projectDir.writeFindAndReplaceRecipe(find = "PLACEHOLDER", replace = "shop.v1")

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
                    ".proto",
                    "--output",
                    "files",
                    "--dry-run"
                )

            assertEquals(0, result.exitCode, "stderr: ${result.stderr}")
            assertTrue(
                result.stdout.contains("user.proto") &&
                    result.stdout.contains("order.proto"),
                "Both proto files should be reported as changed: ${result.stdout}"
            )
        }
    })
