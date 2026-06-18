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

        // Root-less monorepos: the root has no build descriptor, every build unit lives in a
        // subdirectory and relies on the shared root wrapper (no per-unit wrapper). These exercise
        // orphan discovery, per-unit dispatch, root-wrapper fallback, diff rebasing, and implicit
        // root rewrite.yaml resolution (no --rewrite-config passed) against the REAL plugins.
        listOf(
            Triple(
                "Gradle",
                listOf("svc-a" to OrphanTool.GRADLE, "svc-b" to OrphanTool.GRADLE),
                WrapperSet(gradle = true, maven = false)
            ),
            Triple(
                "Maven",
                listOf("svc-a" to OrphanTool.MAVEN, "svc-b" to OrphanTool.MAVEN),
                WrapperSet(gradle = false, maven = true)
            ),
            Triple(
                "mixed Gradle+Maven",
                listOf("svc-gradle" to OrphanTool.GRADLE, "svc-maven" to OrphanTool.MAVEN),
                WrapperSet(gradle = true, maven = true)
            )
        ).forEach { (label, units, wrappers) ->
            test(
                "real plugin: root-less $label monorepo runs Stage 0 in every unit"
            ).config(enabled = !isWindows) {
                assumeMavenCentralReachable()
                val projectDir = Files.createTempDirectory("real-plugin-rootless-")
                val cacheDir = Files.createTempDirectory("real-plugin-rootless-cache-")
                try {
                    units.forEach { (name, tool) -> setUpOrphanUnit(projectDir, name, tool) }
                    installRootWrapper(projectDir, wrappers)
                    projectDir.writeFindAndReplaceRecipe(
                        find = "class App{}",
                        replace = "class App { }"
                    )
                    assertRootlessMonorepo(projectDir, cacheDir, units.map { it.first })
                } finally {
                    projectDir.toFile().deleteRecursively()
                    cacheDir.toFile().deleteRecursively()
                }
            }
        }

        test(
            "real plugin: root-less Gradle monorepo applies a recipe to build.gradle in every unit"
        ).config(enabled = !isWindows) {
            assumeMavenCentralReachable()
            val projectDir = Files.createTempDirectory("real-plugin-rootless-buildgradle-")
            val cacheDir = Files.createTempDirectory("real-plugin-rootless-buildgradle-cache-")
            val units = listOf("svc-a", "svc-b")
            try {
                // Each unit is an independent Groovy-DSL Gradle build; the recipe rewrites a marker
                // in build.gradle (not the .java source), so the only diffs are build-file diffs.
                units.forEach { name ->
                    val unit = projectDir.resolve(name)
                    unit.resolve("src/main/java").toFile().mkdirs()
                    unit.resolve("settings.gradle").writeText("rootProject.name = '$name'\n")
                    unit.resolve("build.gradle").writeText("plugins { id 'java' }\n// MARKER\n")
                    unit.resolve("src/main/java/App.java").writeText("class App {}\n")
                }
                installRootWrapper(projectDir, WrapperSet(gradle = true, maven = false))
                projectDir.writeFindAndReplaceRecipe(find = "// MARKER", replace = "// REPLACED")

                val result =
                    RewriteRunner.builder()
                        .projectDir(projectDir)
                        .activeRecipe("com.example.integration.FindAndReplace")
                        .cacheDir(cacheDir)
                        .build()
                        .run()

                assertEquals(
                    UsedExecutionStage.PLUGIN,
                    result.executionDiagnostics.stageUsed,
                    "Expected Stage 0 to produce the run, " +
                        "got ${result.executionDiagnostics.stageUsed}"
                )
                assertEquals(
                    units.map { Path.of("$it/build.gradle") }.toSet(),
                    result.rawDiffs.keys,
                    "Expected one rebased build.gradle diff per orphan unit"
                )
                units.forEach { name ->
                    assertEquals(
                        "plugins { id 'java' }\n// REPLACED\n",
                        projectDir.resolve("$name/build.gradle").readText(),
                        "Unexpected build.gradle content for $name"
                    )
                }
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

private enum class OrphanTool { GRADLE, MAVEN }

private data class WrapperSet(val gradle: Boolean, val maven: Boolean)

/** Lays out a single orphan build unit (Gradle or Maven) under [projectDir]/[name]. */
private fun setUpOrphanUnit(projectDir: Path, name: String, tool: OrphanTool) {
    val unit = projectDir.resolve(name)
    unit.resolve("src/main/java").toFile().mkdirs()
    unit.resolve("src/main/java/App.java").writeText("class App{}\n")
    when (tool) {
        OrphanTool.GRADLE -> {
            unit.resolve("settings.gradle.kts").writeText("rootProject.name = \"$name\"\n")
            unit.resolve("build.gradle.kts").writeText("plugins { java }\n")
        }

        OrphanTool.MAVEN ->
            unit.resolve("pom.xml").writeText(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>$name</artifactId>
                  <version>1.0-SNAPSHOT</version>
                </project>
                """.trimIndent()
            )
    }
}

/**
 * Installs shared wrapper shims at the repository [projectDir] (not inside the units). Orphan
 * units carry no wrapper, so the 2-arg `resolve{Gradle,Maven}Command` resolvers must fall back to
 * these root wrappers — the realistic root-less monorepo layout.
 */
private fun installRootWrapper(projectDir: Path, wrappers: WrapperSet) {
    if (wrappers.gradle) {
        val gradleBin = ToolchainCache.gradleHome().resolve("bin/gradle").toAbsolutePath()
        val gradlew = projectDir.resolve("gradlew")
        gradlew.toFile().writeText("#!/bin/sh\nexec \"$gradleBin\" \"\$@\"\n")
        Files.setPosixFilePermissions(gradlew, posixExecutable)
    }
    if (wrappers.maven) {
        val mvnBin = ToolchainCache.mavenHome().resolve("bin/mvn").toAbsolutePath()
        val mvnw = projectDir.resolve("mvnw")
        mvnw.toFile().writeText("#!/bin/sh\nexec \"$mvnBin\" \"\$@\"\n")
        Files.setPosixFilePermissions(mvnw, posixExecutable)
    }
}

/**
 * Runs [RewriteRunner] over a root-less monorepo and asserts Stage 0 covered every unit: the run
 * is attributed to the plugin, each unit's diff is rebased to the repo root, every source is
 * rewritten on disk, and a positive estimated-time-saved is reported. No `--rewrite-config` is
 * passed, so this also covers implicit root `rewrite.yaml` resolution for orphan units.
 */
private fun assertRootlessMonorepo(projectDir: Path, cacheDir: Path, unitDirs: List<String>) {
    val result =
        RewriteRunner.builder()
            .projectDir(projectDir)
            .activeRecipe("com.example.integration.FindAndReplace")
            .cacheDir(cacheDir)
            .build()
            .run()

    assertEquals(
        UsedExecutionStage.PLUGIN,
        result.executionDiagnostics.stageUsed,
        "Expected Stage 0 to produce the root-less monorepo run, " +
            "got ${result.executionDiagnostics.stageUsed}"
    )
    assertEquals(
        unitDirs.map { Path.of("$it/src/main/java/App.java") }.toSet(),
        result.rawDiffs.keys,
        "Expected one rebased diff per orphan unit"
    )
    unitDirs.forEach { unit ->
        assertEquals(
            "class App { }\n",
            projectDir.resolve("$unit/src/main/java/App.java").readText(),
            "Unexpected content for $unit"
        )
    }
    val estimatedTimeSaved = assertNotNull(result.executionDiagnostics.estimatedTimeSaved)
    assertTrue(estimatedTimeSaved > Duration.ZERO)
}

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
