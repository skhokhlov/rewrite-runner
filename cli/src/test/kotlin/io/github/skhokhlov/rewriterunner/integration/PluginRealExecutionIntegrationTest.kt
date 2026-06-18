package io.github.skhokhlov.rewriterunner.integration

import io.github.skhokhlov.rewriterunner.RewriteRunner
import io.github.skhokhlov.rewriterunner.RunResult
import io.github.skhokhlov.rewriterunner.UsedExecutionStage
import io.kotest.core.spec.style.FunSpec
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions

/**
 * Stage 0 integration tests that exercise the REAL OpenRewrite Gradle/Maven plugins.
 *
 * Selected by Gradle test class-name filter (`./gradlew :cli:testRealPlugin`); excluded from the
 * default `:cli:test` lane via the same filter. There is no Kotest tag.
 *
 * Each scenario:
 * 1. Lays out a project on disk via [PluginScenario.setUpProject].
 * 2. Installs a thin wrapper shim that exec's the real Maven/Gradle distribution from
 *    [ToolchainCache] (downloaded once, cached in `cli/build/test-cache/toolchains/`).
 * 3. Invokes [RewriteRunner] directly (not the CLI) so we can assert on
 *    [RunResult.executionDiagnostics] — specifically that
 *    [UsedExecutionStage.PLUGIN] produced the change. This is the strong signal that
 *    Stage 0 actually ran; without it, an LST-fallback success would pass the file-content
 *    assertions and silently mask Stage 0 regressions.
 *
 * Skipped (not failed) when Maven Central is unreachable at test start or on Windows.
 */
class PluginRealExecutionIntegrationTest :
    FunSpec({
        listOf(
            PluginScenarios.gradleSingleFile,
            PluginScenarios.mavenSingleFile,
            PluginScenarios.mavenMultiModule,
            PluginScenarios.gradleMultiProject
        ).forEach { scenario ->
            test("real plugin: ${scenario.name} — happy path").config(enabled = !isWindows) {
                runRealPluginScenario(scenario, dryRun = false)
            }
        }

        test("real plugin: Maven dry-run does not mutate sources").config(enabled = !isWindows) {
            runRealPluginScenario(PluginScenarios.mavenSingleFile, dryRun = true)
        }

        test(
            "real plugin: orphan Gradle subdir unit runs Stage 0 and rebases the diff"
        ).config(enabled = !isWindows) {
            assumeMavenCentralReachable()
            val projectDir = Files.createTempDirectory("real-plugin-orphan-")
            val cacheDir = Files.createTempDirectory("real-plugin-orphan-cache-")
            try {
                // Root-less monorepo: no build descriptor at the root, one self-contained Gradle
                // unit in svc-a/. Stage 0 must discover svc-a, run the real plugin there, and
                // rebase the diff to svc-a/ at the repository root.
                val unit = projectDir.resolve("svc-a")
                unit.resolve("src/main/java").toFile().mkdirs()
                unit.resolve("settings.gradle.kts").writeText("rootProject.name = \"svc-a\"\n")
                unit.resolve("build.gradle.kts").writeText("plugins { java }\n")
                unit.resolve("src/main/java/App.java").writeText("class App{}\n")
                projectDir.writeFindAndReplaceRecipe(
                    find = "class App{}",
                    replace = "class App { }"
                )
                val gradleBin = ToolchainCache.gradleHome().resolve("bin/gradle").toAbsolutePath()
                val gradlew = unit.resolve("gradlew")
                gradlew.toFile().writeText("#!/bin/sh\nexec \"$gradleBin\" \"\$@\"\n")
                Files.setPosixFilePermissions(gradlew, posixExecutable)

                val result =
                    RewriteRunner.builder()
                        .projectDir(projectDir)
                        .activeRecipe("com.example.integration.FindAndReplace")
                        .cacheDir(cacheDir)
                        .rewriteConfig(projectDir.resolve("rewrite.yaml"))
                        .build()
                        .run()

                assertEquals(
                    UsedExecutionStage.PLUGIN,
                    result.executionDiagnostics.stageUsed,
                    "Expected Stage 0 to produce the orphan-unit run, " +
                        "got ${result.executionDiagnostics.stageUsed}"
                )
                assertEquals(
                    setOf(Path.of("svc-a/src/main/java/App.java")),
                    result.rawDiffs.keys
                )
                assertEquals(
                    "class App { }\n",
                    unit.resolve("src/main/java/App.java").readText()
                )
                val estimatedTimeSaved = assertNotNull(
                    result.executionDiagnostics.estimatedTimeSaved
                )
                assertTrue(estimatedTimeSaved > Duration.ZERO)
            } finally {
                projectDir.toFile().deleteRecursively()
                cacheDir.toFile().deleteRecursively()
            }
        }
    })

private fun runRealPluginScenario(scenario: PluginScenario, dryRun: Boolean) {
    assumeMavenCentralReachable()
    val projectDir = Files.createTempDirectory("real-plugin-")
    val cacheDir = Files.createTempDirectory("real-plugin-cache-")
    try {
        scenario.setUpProject(projectDir)
        installRealWrapper(projectDir)

        val sourcesBefore = scenario.expectedAfterFiles.keys.associateWith { relPath ->
            projectDir.resolve(relPath).readText()
        }

        val builder = RewriteRunner.builder()
            .projectDir(projectDir)
            .activeRecipe(scenario.activeRecipe)
            .recipeArtifacts(scenario.recipeArtifacts)
            .cacheDir(cacheDir)
            .dryRun(dryRun)
        val rewriteYaml = projectDir.resolve("rewrite.yaml")
        if (rewriteYaml.exists()) builder.rewriteConfig(rewriteYaml)

        val result: RunResult = builder.build().run()

        // Core invariant for this suite: Stage 0 (the real plugin) MUST have produced the run.
        // If this ever fails, the fallback LST pipeline silently took over and the on-disk
        // content assertions below would still pass — which is exactly the gap this suite
        // exists to close.
        assertEquals(
            UsedExecutionStage.PLUGIN,
            result.executionDiagnostics.stageUsed,
            "Expected Stage 0 to produce the run for '${scenario.name}', " +
                "got ${result.executionDiagnostics.stageUsed}"
        )
        assertTrue(
            result.hasChanges,
            "Stage 0 reported no changes for scenario '${scenario.name}'; " +
                "expected ${scenario.expectedAfterFiles.size} file(s) modified"
        )
        if (!dryRun) {
            val estimatedTimeSaved = assertNotNull(
                result.executionDiagnostics.estimatedTimeSaved,
                "Expected Stage 0 to read estimated time saved for '${scenario.name}'"
            )
            assertTrue(
                estimatedTimeSaved > Duration.ZERO,
                "Expected positive estimated time saved for '${scenario.name}', " +
                    "got $estimatedTimeSaved"
            )
        }

        if (dryRun) {
            // Sources on disk must be untouched — Stage 0 dry-run only emits diffs.
            sourcesBefore.forEach { (relPath, before) ->
                assertEquals(
                    before,
                    projectDir.resolve(relPath).readText(),
                    "Source $relPath was mutated during dry-run"
                )
            }
            val joinedDiffs = result.rawDiffs.values.joinToString("\n")
            scenario.expectedDryRunDiffContains.forEach { fragment ->
                assertTrue(
                    fragment in joinedDiffs,
                    "Expected dry-run diff to contain '$fragment':\n$joinedDiffs"
                )
            }
        } else {
            scenario.expectedAfterFiles.forEach { (relPath, expected) ->
                assertEquals(
                    expected,
                    projectDir.resolve(relPath).readText(),
                    "Unexpected content for $relPath"
                )
            }
        }
    } finally {
        projectDir.toFile().deleteRecursively()
        cacheDir.toFile().deleteRecursively()
    }
}

/**
 * Writes a thin shim `gradlew` and/or `mvnw` in [projectDir] that exec's the real Maven/Gradle
 * distribution downloaded by [ToolchainCache]. The shim is sufficient for the OpenRewrite
 * plugin strategies, which only need a callable wrapper script (they do not introspect the
 * adjacent `.mvn/` or `gradle/wrapper/` directories).
 */
internal fun installRealWrapper(projectDir: Path) {
    val hasMaven = projectDir.resolve("pom.xml").toFile().exists()
    val hasGradle =
        projectDir.resolve("build.gradle.kts").toFile().exists() ||
            projectDir.resolve("build.gradle").toFile().exists() ||
            projectDir.resolve("settings.gradle.kts").toFile().exists() ||
            projectDir.resolve("settings.gradle").toFile().exists()

    if (hasMaven) {
        val mvnBin = ToolchainCache.mavenHome().resolve("bin/mvn").toAbsolutePath()
        val mvnw = projectDir.resolve("mvnw")
        mvnw.toFile().writeText("#!/bin/sh\nexec \"$mvnBin\" \"\$@\"\n")
        Files.setPosixFilePermissions(mvnw, posixExecutable)
    }
    if (hasGradle) {
        val gradleBin = ToolchainCache.gradleHome().resolve("bin/gradle").toAbsolutePath()
        val gradlew = projectDir.resolve("gradlew")
        gradlew.toFile().writeText("#!/bin/sh\nexec \"$gradleBin\" \"\$@\"\n")
        Files.setPosixFilePermissions(gradlew, posixExecutable)
    }
}

/**
 * HEAD-checks Maven Central with a 3-second timeout. Skips the calling test (JUnit assumption)
 * if unreachable so the suite does not fail on flaky network or air-gapped runs.
 */
internal fun assumeMavenCentralReachable() {
    try {
        val conn =
            URL("https://repo.maven.apache.org/maven2/").openConnection() as HttpURLConnection
        conn.connectTimeout = 3_000
        conn.readTimeout = 3_000
        conn.requestMethod = "HEAD"
        conn.connect()
        val code = conn.responseCode
        conn.disconnect()
        Assumptions.assumeTrue(code in 200..499, "Maven Central returned HTTP $code")
    } catch (e: Exception) {
        Assumptions.assumeTrue(false, "Maven Central not reachable: ${e.message}")
    }
}
