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

        // Installs the protocol-validating fake mvnw derived from the scenario (see
        // Path.writeFakeMvnwWithProtocolChecks). Assumes exactly one entry in
        // scenario.expectedAfterFiles (single-file scenarios only).
        fun setupFakeMvnwWithProtocolChecks(scenario: PluginScenario) {
            scenario.setUpProject(projectDir)
            val (relPath, afterContent) = scenario.expectedAfterFiles.entries.single()
            val beforeContent = projectDir.resolve(relPath).readText()
            projectDir.writeFakeMvnwWithProtocolChecks(
                targetFile = relPath,
                oldLine = beforeContent.trimEnd('\n'),
                newLine = afterContent.trimEnd('\n'),
                newContent = afterContent
            )
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
            setupFakeMvnwWithProtocolChecks(PluginScenarios.mavenSingleFile)

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
            // Ordering: dryRun must precede run, and run must run exactly once.
            assertEquals(
                "dryRun\nrun\n",
                projectDir.resolve("wrapper-calls.log").readText()
            )
        }
    })
