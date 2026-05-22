package io.github.skhokhlov.rewriterunner.integration

import io.kotest.core.spec.style.FunSpec
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PluginFirstIntegrationTest :
    FunSpec({
        var projectDir: Path = Path.of("")
        var cacheDir: Path = Path.of("")

        beforeEach {
            projectDir = Files.createTempDirectory("pfit-project-")
            cacheDir = Files.createTempDirectory("pfit-cache-")
        }

        afterEach {
            projectDir.toFile().deleteRecursively()
            cacheDir.toFile().deleteRecursively()
        }

        // Installs a fake gradlew whose before/after content is derived from the scenario.
        // Assumes exactly one entry in scenario.expectedAfterFiles (single-file scenarios only).
        fun setupFakeGradlew(scenario: PluginScenario) {
            scenario.setUpProject(projectDir)
            val (relPath, afterContent) = scenario.expectedAfterFiles.entries.single()
            val beforeContent = projectDir.resolve(relPath).readText()
            projectDir.writeFakeGradlew(
                targetFile = relPath,
                oldLine = beforeContent.trimEnd('\n'),
                newLine = afterContent.trimEnd('\n'),
                newContent = afterContent
            )
        }

        // Installs a simple fake mvnw (no flag protocol validation) derived from the scenario.
        // Assumes exactly one entry in scenario.expectedAfterFiles (single-file scenarios only).
        fun setupFakeMvnwSimple(scenario: PluginScenario) {
            scenario.setUpProject(projectDir)
            val (relPath, afterContent) = scenario.expectedAfterFiles.entries.single()
            val beforeContent = projectDir.resolve(relPath).readText()
            projectDir.writeFakeMvnwSimple(
                targetFile = relPath,
                oldLine = beforeContent.trimEnd('\n'),
                newLine = afterContent.trimEnd('\n'),
                newContent = afterContent
            )
        }

        // Full protocol-validation fake mvnw, kept for the "flag protocol" test below.
        // Validates that MavenPluginStrategy sends the unprefixed -DreportOutputDirectory=
        // flag (not -Drewrite.reportOutputDirectory=) and -Drewrite.runPerSubmodule=false.
        fun writeFakeMvnwWithProtocolChecks() {
            val mvnw = projectDir.resolve("mvnw")
            mvnw.writeText(
                """
                #!/bin/sh
                LOG="$D(cd "$D(dirname "${D}0")" && pwd)/wrapper-calls.log"

                goal=""
                report_dir=""
                has_run_per_submodule=0
                has_wrong_prefix=0
                has_unprefixed=0

                for arg in "$D@"; do
                  case "${D}arg" in
                    *:dryRun)
                      if [ -z "${D}goal" ]; then goal=dryRun; fi
                      ;;
                    *:run)
                      if [ -z "${D}goal" ]; then goal=run; fi
                      ;;
                    -DreportOutputDirectory=*)
                      report_dir="$D{arg#-DreportOutputDirectory=}"
                      has_unprefixed=1
                      ;;
                    -Drewrite.reportOutputDirectory=*)
                      has_wrong_prefix=1
                      ;;
                    -Drewrite.runPerSubmodule=false)
                      has_run_per_submodule=1
                      ;;
                  esac
                done

                if [ -n "${D}goal" ]; then
                  echo "${D}goal" >> "${D}LOG"
                fi

                if [ "${D}has_wrong_prefix" = "1" ]; then
                  echo "FAIL: prefixed -Drewrite.reportOutputDirectory must not be used" 1>&2
                  exit 2
                fi
                if [ "${D}has_unprefixed" = "0" ]; then
                  echo "FAIL: -DreportOutputDirectory missing" 1>&2
                  exit 2
                fi
                if [ "${D}has_run_per_submodule" = "0" ]; then
                  echo "FAIL: -Drewrite.runPerSubmodule=false missing" 1>&2
                  exit 2
                fi

                if [ "${D}goal" = "dryRun" ]; then
                  mkdir -p "${D}report_dir"
                  cat > "${D}report_dir/rewrite.patch" <<'PATCH'
                diff --git a/src/App.java b/src/App.java
                --- a/src/App.java
                +++ b/src/App.java
                @@ -1 +1 @@
                -class App{}
                +class App { }
                PATCH
                  exit 0
                fi

                if [ "${D}goal" = "run" ]; then
                  printf 'class App { }\n' > src/App.java
                  exit 0
                fi

                exit 1
                """.trimIndent()
            )
            Files.setPosixFilePermissions(mvnw, posixExecutable)
        }

        test(
            "plugin-first Gradle path formats raw diffs and applies changes"
        ).config(enabled = !isWindows) {
            setupFakeGradlew(PluginScenarios.gradleSingleFile)

            val result =
                runCli(
                    "--project-dir",
                    projectDir.toString(),
                    "--active-recipe",
                    PluginScenarios.gradleSingleFile.activeRecipe,
                    "--cache-dir",
                    cacheDir.toString(),
                    "--output",
                    "files"
                )

            assertEquals(0, result.exitCode)
            assertEquals("src/main/java/App.java", result.stdout.trim())
            assertEquals(
                PluginScenarios.gradleSingleFile.expectedAfterFiles["src/main/java/App.java"],
                projectDir.resolve("src/main/java/App.java").toFile().readText()
            )
            // Ordering: dry-run must precede apply, and apply must run exactly once.
            assertEquals(
                "rewriteDryRun\nrewriteRun\n",
                projectDir.resolve("wrapper-calls.log").readText()
            )
        }

        // This test specifically tests CLI bypass behavior, not recipe execution.
        // It intentionally sets up a project WITHOUT a rewrite.yaml so the LST pipeline
        // cannot find `com.example.Recipe`, producing a non-zero exit code.
        test("--skip-plugin-run bypasses fake plugin path").config(enabled = !isWindows) {
            projectDir.resolve("build.gradle.kts").writeText("")
            projectDir.resolve("src").toFile().mkdirs()
            projectDir.resolve("src/App.java").writeText("class App{}\n")
            projectDir.writeFakeGradlew(
                targetFile = "src/App.java",
                oldLine = "class App{}",
                newLine = "class App { }",
                newContent = "class App { }\n"
            )

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

        test(
            "plugin-first Maven path applies patch via reportOutputDirectory and mutates source"
        ).config(enabled = !isWindows) {
            setupFakeMvnwSimple(PluginScenarios.mavenSingleFile)

            val result =
                runCli(
                    "--project-dir",
                    projectDir.toString(),
                    "--active-recipe",
                    PluginScenarios.mavenSingleFile.activeRecipe,
                    "--cache-dir",
                    cacheDir.toString(),
                    "--output",
                    "files"
                )

            assertEquals(0, result.exitCode, "stderr=${result.stderr}\nstdout=${result.stdout}")
            assertEquals("src/main/java/App.java", result.stdout.trim())
            assertEquals(
                PluginScenarios.mavenSingleFile.expectedAfterFiles["src/main/java/App.java"],
                projectDir.resolve("src/main/java/App.java").toFile().readText()
            )
            // Order: dryRun discovers the patch, then run applies it. Exactly one of each.
            assertEquals(
                "dryRun\nrun\n",
                projectDir.resolve("wrapper-calls.log").readText()
            )
        }

        test(
            "plugin-first Maven dry-run produces diff without invoking rewrite:run"
        ).config(enabled = !isWindows) {
            setupFakeMvnwSimple(PluginScenarios.mavenSingleFile)

            val result =
                runCli(
                    "--project-dir",
                    projectDir.toString(),
                    "--active-recipe",
                    PluginScenarios.mavenSingleFile.activeRecipe,
                    "--cache-dir",
                    cacheDir.toString(),
                    "--dry-run",
                    "--output",
                    "diff"
                )

            assertEquals(0, result.exitCode, "stderr=${result.stderr}\nstdout=${result.stdout}")
            // Source must be untouched on dry-run.
            assertEquals(
                "class App{}\n",
                projectDir.resolve("src/main/java/App.java").toFile().readText()
            )
            assertTrue(
                "-class App{}" in result.stdout && "+class App { }" in result.stdout,
                "expected dry-run diff in stdout, got:\n${result.stdout}"
            )
            // rewrite:run must not be invoked under --dry-run.
            assertEquals(
                "dryRun\n",
                projectDir.resolve("wrapper-calls.log").readText()
            )
        }

        // Validates that MavenPluginStrategy sends the correct flag format to the Maven wrapper:
        // - unprefixed `-DreportOutputDirectory=` (not `-Drewrite.reportOutputDirectory=`)
        // - `-Drewrite.runPerSubmodule=false`
        // A regression to the wrong flag format causes the fake wrapper to exit 2 here.
        test(
            "maven fake-wrapper flag protocol: unprefixed -DreportOutputDirectory and runPerSubmodule=false"
        ).config(enabled = !isWindows) {
            PluginScenarios.mavenSingleFile.setUpProject(projectDir)
            writeFakeMvnwWithProtocolChecks()

            val result =
                runCli(
                    "--project-dir",
                    projectDir.toString(),
                    "--active-recipe",
                    PluginScenarios.mavenSingleFile.activeRecipe,
                    "--cache-dir",
                    cacheDir.toString(),
                    "--output",
                    "files"
                )

            assertEquals(0, result.exitCode, "stderr=${result.stderr}\nstdout=${result.stdout}")
            assertEquals("src/App.java", result.stdout.trim())
            // Ordering: dryRun must precede run, and run must run exactly once.
            assertEquals(
                "dryRun\nrun\n",
                projectDir.resolve("wrapper-calls.log").readText()
            )
        }
    })
