package io.github.skhokhlov.rewriterunner.integration

import io.github.skhokhlov.rewriterunner.RewriteRunner
import io.github.skhokhlov.rewriterunner.UsedExecutionStage
import io.github.skhokhlov.rewriterunner.lst.SpecializedOwnership
import io.kotest.core.spec.style.FunSpec
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
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

        test(
            "plugin-first Gradle path populates estimated time saved"
        ).config(enabled = !isWindows) {
            setupFakeGradlew(PluginScenarios.gradleSingleFile)

            val result =
                RewriteRunner.builder()
                    .projectDir(projectDir)
                    .activeRecipe(PluginScenarios.gradleSingleFile.activeRecipe)
                    .cacheDir(cacheDir)
                    .build()
                    .run()

            assertEquals(UsedExecutionStage.PLUGIN, result.executionDiagnostics.stageUsed)
            assertEquals(
                Duration.ofSeconds(420),
                result.executionDiagnostics.estimatedTimeSaved
            )
        }

        test(
            "plugin-first Gradle path falls back when data table estimate is zero"
        ).config(enabled = !isWindows) {
            val scenario = PluginScenarios.gradleSingleFile
            scenario.setUpProject(projectDir)
            val (relPath, afterContent) = scenario.expectedAfterFiles.entries.single()
            val beforeContent = projectDir.resolve(relPath).readText()
            projectDir.writeFakeGradlewWithHeaderOnlyDataTablesAndEstimate(
                targetFile = relPath,
                oldLine = beforeContent.trimEnd('\n'),
                newLine = afterContent.trimEnd('\n'),
                newContent = afterContent,
                estimate = "7m"
            )

            val result =
                RewriteRunner.builder()
                    .projectDir(projectDir)
                    .activeRecipe(scenario.activeRecipe)
                    .cacheDir(cacheDir)
                    .build()
                    .run()

            assertEquals(UsedExecutionStage.PLUGIN, result.executionDiagnostics.stageUsed)
            assertEquals(
                Duration.ofMinutes(7),
                result.executionDiagnostics.estimatedTimeSaved
            )
        }

        test(
            "plugin-first Gradle path merges raw plugin diffs with specialized Docker results"
        ).config(enabled = !isWindows) {
            projectDir.resolve("settings.gradle.kts").writeText("rootProject.name = \"test\"\n")
            projectDir.resolve("build.gradle.kts").writeText("plugins { java }\n")
            projectDir.resolve("src/main/java").toFile().mkdirs()
            val javaFile = projectDir.resolve("src/main/java/App.java")
            javaFile.writeText("class App{}\n")
            val dockerfile = projectDir.resolve("Dockerfile")
            dockerfile.writeText("FROM ubuntu:PLACEHOLDER\n")
            projectDir.writeFindAndReplaceRecipe(find = "PLACEHOLDER", replace = "22.04")
            projectDir.writeFakeGradlewWithExclusionChecks(
                targetFile = "src/main/java/App.java",
                oldLine = "class App{}",
                newLine = "class App { }",
                newContent = "class App { }\n",
                requiredExclusions = SpecializedOwnership.stage0ExcludeGlobs
            )

            val runResult =
                RewriteRunner.builder()
                    .projectDir(projectDir)
                    .activeRecipe("com.example.integration.FindAndReplace")
                    .cacheDir(cacheDir)
                    .build()
                    .run()

            assertEquals(
                UsedExecutionStage.PLUGIN,
                runResult.executionDiagnostics.stageUsed,
                "runResult=$runResult"
            )
            assertTrue((runResult.executionDiagnostics.parsedFileCount ?: 0) > 0)
            assertEquals(setOf(Path.of("src/main/java/App.java")), runResult.rawDiffs.keys)
            assertEquals(
                setOf("Dockerfile"),
                runResult.results.map {
                    (it.after?.sourcePath ?: it.before?.sourcePath).toString()
                }.toSet()
            )
            assertEquals("class App { }\n", javaFile.readText())
            assertEquals("FROM ubuntu:22.04\n", dockerfile.readText())
            assertEquals(setOf(javaFile, dockerfile), runResult.changedFiles.toSet())
            assertEquals(
                "rewriteDryRun\nrewriteRun\n",
                projectDir.resolve("wrapper-calls.log").readText()
            )
        }

        test(
            "specialized pass recipe-load failure does not fail a successful plugin run"
        ).config(enabled = !isWindows) {
            // Stage 0 succeeds (the fake plugin patches the Java file) and the project
            // contains an owned Dockerfile, but the active recipe is known ONLY to the
            // project's own build — there is no rewrite.yaml and no recipe artifact, so the
            // in-process specialized pass cannot resolve it. The pass must degrade to
            // plugin-only results rather than throwing and failing an already-successful run.
            projectDir.resolve("settings.gradle.kts").writeText("rootProject.name = \"test\"\n")
            projectDir.resolve("build.gradle.kts").writeText("plugins { java }\n")
            projectDir.resolve("src/main/java").toFile().mkdirs()
            val javaFile = projectDir.resolve("src/main/java/App.java")
            javaFile.writeText("class App{}\n")
            val dockerfile = projectDir.resolve("Dockerfile")
            dockerfile.writeText("FROM ubuntu:PLACEHOLDER\n")
            projectDir.writeFakeGradlew(
                targetFile = "src/main/java/App.java",
                oldLine = "class App{}",
                newLine = "class App { }",
                newContent = "class App { }\n"
            )

            val runResult =
                RewriteRunner.builder()
                    .projectDir(projectDir)
                    .activeRecipe("com.example.only.known.to.plugin.Recipe")
                    .cacheDir(cacheDir)
                    .build()
                    .run()

            assertEquals(
                UsedExecutionStage.PLUGIN,
                runResult.executionDiagnostics.stageUsed,
                "runResult=$runResult"
            )
            assertEquals(setOf(Path.of("src/main/java/App.java")), runResult.rawDiffs.keys)
            assertTrue(runResult.results.isEmpty())
            // Dockerfile untouched because the specialized recipe never ran.
            assertEquals("FROM ubuntu:PLACEHOLDER\n", dockerfile.readText())
            assertEquals("class App { }\n", javaFile.readText())
        }

        test(
            "plugin-first Maven path with no owned files keeps plugin-only diagnostics"
        ).config(enabled = !isWindows) {
            projectDir.resolve("pom.xml").writeText(
                """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>test</artifactId>
                  <version>1.0-SNAPSHOT</version>
                </project>
                """.trimIndent()
            )
            projectDir.resolve("src/main/java").toFile().mkdirs()
            val javaFile = projectDir.resolve("src/main/java/App.java")
            javaFile.writeText("class App{}\n")
            projectDir.writeFakeMvnwWithExclusionChecks(
                targetFile = "src/main/java/App.java",
                oldLine = "class App{}",
                newLine = "class App { }",
                newContent = "class App { }\n",
                requiredExclusions = SpecializedOwnership.stage0ExcludeGlobs
            )

            val runResult =
                RewriteRunner.builder()
                    .projectDir(projectDir)
                    .activeRecipe("com.example.integration.FindAndReplace")
                    .cacheDir(cacheDir)
                    .build()
                    .run()

            assertEquals(
                UsedExecutionStage.PLUGIN,
                runResult.executionDiagnostics.stageUsed,
                "runResult=$runResult"
            )
            assertEquals(null, runResult.executionDiagnostics.parsedFileCount)
            assertTrue(runResult.results.isEmpty())
            assertEquals(setOf(Path.of("src/main/java/App.java")), runResult.rawDiffs.keys)
            assertEquals("class App { }\n", javaFile.readText())
            assertEquals(listOf(javaFile), runResult.changedFiles)
            assertEquals(
                "dryRun\nrun\n",
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
            "plugin-first Maven path populates estimated time saved"
        ).config(enabled = !isWindows) {
            setupFakeMvnwSimple(PluginScenarios.mavenSingleFile)

            val result =
                RewriteRunner.builder()
                    .projectDir(projectDir)
                    .activeRecipe(PluginScenarios.mavenSingleFile.activeRecipe)
                    .cacheDir(cacheDir)
                    .build()
                    .run()

            assertEquals(UsedExecutionStage.PLUGIN, result.executionDiagnostics.stageUsed)
            assertEquals(
                Duration.ofSeconds(420),
                result.executionDiagnostics.estimatedTimeSaved
            )
        }

        test(
            "plugin-first Maven path falls back when data table estimates are zero"
        ).config(enabled = !isWindows) {
            val scenario = PluginScenarios.mavenSingleFile
            scenario.setUpProject(projectDir)
            val (relPath, afterContent) = scenario.expectedAfterFiles.entries.single()
            val beforeContent = projectDir.resolve(relPath).readText()
            projectDir.writeFakeMvnwWithHeaderOnlyDataTablesAndEstimate(
                targetFile = relPath,
                oldLine = beforeContent.trimEnd('\n'),
                newLine = afterContent.trimEnd('\n'),
                newContent = afterContent,
                estimate = "9m"
            )

            val result =
                RewriteRunner.builder()
                    .projectDir(projectDir)
                    .activeRecipe(scenario.activeRecipe)
                    .cacheDir(cacheDir)
                    .build()
                    .run()

            assertEquals(UsedExecutionStage.PLUGIN, result.executionDiagnostics.stageUsed)
            assertEquals(
                Duration.ofMinutes(9),
                result.executionDiagnostics.estimatedTimeSaved
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
        // - `-Drewrite.exportDatatables=true`
        // A regression to the wrong flag format causes the fake wrapper to exit 2 here.
        test(
            "maven fake-wrapper flag protocol: report directory, runPerSubmodule, and datatables"
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
