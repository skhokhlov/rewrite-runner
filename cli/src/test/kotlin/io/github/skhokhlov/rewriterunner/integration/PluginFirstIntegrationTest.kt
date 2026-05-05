package io.github.skhokhlov.rewriterunner.integration

import io.kotest.core.spec.style.FunSpec
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val isWindows = System.getProperty("os.name", "").lowercase().contains("windows")

class PluginFirstIntegrationTest :
    FunSpec({
        var projectDir: Path = Path.of("")
        var cacheDir: Path = Path.of("")

        beforeEach {
            projectDir = Files.createTempDirectory("pfit-project-")
            cacheDir = Files.createTempDirectory("pfit-cache-")
            projectDir.resolve("build.gradle.kts").writeText("")
            projectDir.resolve("src").toFile().mkdirs()
            projectDir.resolve("src/App.java").writeText("class App{}\n")
        }

        afterEach {
            projectDir.toFile().deleteRecursively()
            cacheDir.toFile().deleteRecursively()
        }

        fun writeFakeGradlew() {
            val gradlew = projectDir.resolve("gradlew")
            gradlew.writeText(
                """
                #!/bin/sh
                if [ "$1" = "rewriteDryRun" ]; then
                  mkdir -p build/reports/rewrite
                  cat > build/reports/rewrite/rewrite.patch <<'PATCH'
                diff --git a/src/App.java b/src/App.java
                --- a/src/App.java
                +++ b/src/App.java
                @@ -1 +1 @@
                -class App{}
                +class App { }
                PATCH
                  exit 0
                fi
                if [ "$1" = "rewriteRun" ]; then
                  printf 'class App { }\n' > src/App.java
                  exit 0
                fi
                exit 1
                """.trimIndent()
            )
            Files.setPosixFilePermissions(
                gradlew,
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE
                )
            )
        }

        test(
            "plugin-first Gradle path formats raw diffs and applies changes"
        ).config(enabled = !isWindows) {
            writeFakeGradlew()

            // The fake wrapper validates plugin-first orchestration and raw-diff formatting.
            // Init script content is covered by GradlePluginStrategyTest.
            val result =
                runCli(
                    "--project-dir",
                    projectDir.toString(),
                    "--active-recipe",
                    "com.example.Recipe",
                    "--cache-dir",
                    cacheDir.toString(),
                    "--output",
                    "files"
                )

            assertEquals(0, result.exitCode)
            assertEquals("src/App.java", result.stdout.trim())
            assertEquals("class App { }\n", projectDir.resolve("src/App.java").toFile().readText())
        }

        test("--skip-plugin-run bypasses fake plugin path").config(enabled = !isWindows) {
            writeFakeGradlew()

            val result =
                runCli(
                    "--project-dir",
                    projectDir.toString(),
                    "--active-recipe",
                    "com.example.Recipe",
                    "--cache-dir",
                    cacheDir.toString(),
                    "--skip-plugin-run"
                )

            assertTrue(result.exitCode != 0)
            assertEquals("class App{}\n", projectDir.resolve("src/App.java").toFile().readText())
        }
    })
