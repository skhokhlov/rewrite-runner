package io.github.skhokhlov.rewriterunner.integration

import io.kotest.core.spec.style.FunSpec
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val isWindows = System.getProperty("os.name", "").lowercase().contains("windows")

// Kotlin raw strings interpolate $identifier; embed a literal `$` for shell vars via `${d}`.
private const val D = "$"

private val posixExecutable =
    setOf(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.OWNER_EXECUTE
    )

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

        fun setUpGradleProject() {
            projectDir.resolve("build.gradle.kts").writeText("")
            projectDir.resolve("src").toFile().mkdirs()
            projectDir.resolve("src/App.java").writeText("class App{}\n")
        }

        fun setUpMavenProject() {
            projectDir.resolve("pom.xml").writeText("<project/>")
            projectDir.resolve("src").toFile().mkdirs()
            projectDir.resolve("src/App.java").writeText("class App{}\n")
        }

        fun writeFakeGradlew() {
            val gradlew = projectDir.resolve("gradlew")
            gradlew.writeText(
                """
                #!/bin/sh
                LOG="$D(cd "$D(dirname "${D}0")" && pwd)/wrapper-calls.log"
                echo "${D}1" >> "${D}LOG"
                if [ "${D}1" = "rewriteDryRun" ]; then
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
                if [ "${D}1" = "rewriteRun" ]; then
                  printf 'class App { }\n' > src/App.java
                  exit 0
                fi
                exit 1
                """.trimIndent()
            )
            Files.setPosixFilePermissions(gradlew, posixExecutable)
        }

        // Fake `mvnw` that exercises the full RunCommand → MavenPluginStrategy →
        // DirectPluginExecutor wiring without invoking real Maven.
        //
        // The script:
        //   1. detects the plugin goal (suffix `:dryRun` or `:run`),
        //   2. extracts `-DreportOutputDirectory=<dir>`,
        //   3. fails fast (exit 2) if the prefixed `-Drewrite.reportOutputDirectory=`
        //      shows up, if the unprefixed flag is missing, or if
        //      `-Drewrite.runPerSubmodule=false` is missing,
        //   4. appends the goal to `wrapper-calls.log` for ordering assertions,
        //   5. on `:dryRun` writes a rewrite.patch into the report directory,
        //   6. on `:run` mutates `src/App.java`.
        //
        // Validation uses literal documented flag strings (not constants shared with
        // production) so a typo in MavenPluginStrategy is a true regression here.
        fun writeFakeMvnw() {
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
            setUpGradleProject()
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
            // Ordering: dry-run must precede apply, and apply must run exactly once.
            assertEquals(
                "rewriteDryRun\nrewriteRun\n",
                projectDir.resolve("wrapper-calls.log").readText()
            )
        }

        test("--skip-plugin-run bypasses fake plugin path").config(enabled = !isWindows) {
            setUpGradleProject()
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

        test(
            "plugin-first Maven path applies patch via reportOutputDirectory and mutates source"
        ).config(enabled = !isWindows) {
            setUpMavenProject()
            writeFakeMvnw()

            // End-to-end: drives the real RunCommand → RewriteRunner → PluginRecipeRunner →
            // MavenPluginStrategy → DirectPluginExecutor path. The wrapper writes a patch only
            // when the unprefixed -DreportOutputDirectory= is present, so a regression to
            // -Drewrite.reportOutputDirectory= in production would make this test fail.
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

            assertEquals(0, result.exitCode, "stderr=${result.stderr}\nstdout=${result.stdout}")
            assertEquals("src/App.java", result.stdout.trim())
            assertEquals(
                "class App { }\n",
                projectDir.resolve("src/App.java").toFile().readText()
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
            setUpMavenProject()
            writeFakeMvnw()

            val result =
                runCli(
                    "--project-dir",
                    projectDir.toString(),
                    "--active-recipe",
                    "com.example.Recipe",
                    "--cache-dir",
                    cacheDir.toString(),
                    "--dry-run",
                    "--output",
                    "diff"
                )

            assertEquals(0, result.exitCode, "stderr=${result.stderr}\nstdout=${result.stdout}")
            // Source must be untouched on dry-run.
            assertEquals("class App{}\n", projectDir.resolve("src/App.java").toFile().readText())
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
    })
