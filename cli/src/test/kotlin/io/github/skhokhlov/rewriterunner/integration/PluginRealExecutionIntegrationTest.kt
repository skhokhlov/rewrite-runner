package io.github.skhokhlov.rewriterunner.integration

import io.github.skhokhlov.rewriterunner.RewriteRunner
import io.github.skhokhlov.rewriterunner.RunResult
import io.github.skhokhlov.rewriterunner.UsedExecutionStage
import io.github.skhokhlov.rewriterunner.config.RepositoryConfig
import io.kotest.core.spec.style.FunSpec
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

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
 * The Linux CI lane treats Maven Central and wrapper toolchains as required prerequisites. The
 * POSIX wrapper fixture is not selected on Windows.
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
            "real plugin: Gradle and Maven recipe JVMs observe configured executor heap"
        ).config(enabled = !isWindows) {
            requireMavenCentralReachable()
            val repository = Files.createTempDirectory("real-plugin-probe-repository-")
            try {
                val coordinate = publishExecutorProbeArtifact(repository)
                ProbeBuildTool.entries.forEach { tool ->
                    val projectDir = Files.createTempDirectory("real-plugin-probe-${tool.name}-")
                    val cacheDir = Files.createTempDirectory("real-plugin-probe-cache-")
                    val probeOutput = projectDir.resolve("executor-probe.csv")
                    try {
                        setUpProbeProject(projectDir, tool, repository, probeOutput)
                        installRealWrapper(projectDir)

                        val result =
                            RewriteRunner.builder()
                                .projectDir(projectDir)
                                .activeRecipe("com.example.ExecutorProbe")
                                .recipeArtifact(coordinate)
                                .artifactRepository(
                                    RepositoryConfig(url = repository.toUri().toString())
                                )
                                .cacheDir(cacheDir)
                                .executorJvmArgs(listOf("-Xmx512m"))
                                .rewriteConfig(projectDir.resolve("rewrite.yaml"))
                                .build()
                                .run()

                        assertEquals(
                            UsedExecutionStage.PLUGIN,
                            result.executionDiagnostics.stageUsed
                        )
                        val rows = readProbeRows(probeOutput)
                        assertTrue(
                            rows.isNotEmpty(),
                            "${tool.name} plugin did not write probe output"
                        )
                        rows.forEach { row ->
                            assertTrue(
                                row.processId != ProcessHandle.current().pid(),
                                "Probe ran in the coordinator instead of the ${tool.name} plugin JVM"
                            )
                            assertEquals(512L * 1024L * 1024L, row.maximumHeapBytes)
                            assertTrue(
                                "-Xmx512m" in row.inputArguments,
                                "${tool.name} recipe JVM did not receive configured heap: ${row.inputArguments}"
                            )
                        }
                    } finally {
                        projectDir.toFile().deleteRecursively()
                        cacheDir.toFile().deleteRecursively()
                    }
                }
            } finally {
                repository.toFile().deleteRecursively()
            }
        }

        listOf(MultiModuleBuildTool.GRADLE, MultiModuleBuildTool.MAVEN).forEach { tool ->
            test(
                "real plugin: ${tool.displayName} root exclusion reaches subproject files"
            ).config(enabled = !isWindows) {
                runMultiModuleExclusionScenario(
                    tool = tool,
                    excludePaths = listOf("**/Skip.java"),
                    expectedChangedPaths = setOf(rootSource, libKeepSource),
                    expectedUnchangedPaths = setOf(libSkipSource)
                )
            }
        }

        test(
            "real plugin: Gradle module-relative exclusion does not reach subproject file"
        ).config(enabled = !isWindows) {
            runMultiModuleExclusionScenario(
                tool = MultiModuleBuildTool.GRADLE,
                excludePaths = listOf("src/main/java/com/example/Skip.java"),
                expectedChangedPaths = setOf(rootSource, libKeepSource, libSkipSource),
                expectedUnchangedPaths = emptySet()
            )
        }

        test(
            "real plugin: Maven module-relative exclusion does not reach submodule file"
        ).config(enabled = !isWindows) {
            runMultiModuleExclusionScenario(
                tool = MultiModuleBuildTool.MAVEN,
                excludePaths = listOf("src/main/java/com/example/Skip.java"),
                expectedChangedPaths = setOf(rootSource, libKeepSource, libSkipSource),
                expectedUnchangedPaths = emptySet()
            )
        }

        test(
            "real plugin: orphan Gradle subdir unit runs Stage 0 and rebases the diff"
        ).config(enabled = !isWindows) {
            requireMavenCentralReachable()
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
                requireMavenCentralReachable()
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
            requireMavenCentralReachable()
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

private enum class MultiModuleBuildTool(val displayName: String) {
    GRADLE("Gradle"),
    MAVEN("Maven")
}

private data class WrapperSet(val gradle: Boolean, val maven: Boolean)

private enum class ProbeBuildTool { GRADLE, MAVEN }

private data class ExecutorProbeRow(
    val processId: Long,
    val maximumHeapBytes: Long,
    val inputArguments: String
)

private const val EXECUTOR_PROBE_GROUP = "io.github.skhokhlov.rewriterunner.integration"
private const val EXECUTOR_PROBE_ARTIFACT = "executor-probe"
private const val EXECUTOR_PROBE_VERSION = "1.0.0"
private const val EXECUTOR_PROBE_CLASS =
    "io.github.skhokhlov.rewriterunner.integration.probe.ExecutorProbeRecipe"

/** Publishes a test-only Java recipe artifact without adding it to the production build. */
private fun publishExecutorProbeArtifact(repository: Path): String {
    val artifactDirectory = repository.resolve(
        "${EXECUTOR_PROBE_GROUP.replace('.', '/')}/" +
            "$EXECUTOR_PROBE_ARTIFACT/$EXECUTOR_PROBE_VERSION"
    )
    Files.createDirectories(artifactDirectory)
    val jar = artifactDirectory.resolve(
        "$EXECUTOR_PROBE_ARTIFACT-$EXECUTOR_PROBE_VERSION.jar"
    )
    val classResource = EXECUTOR_PROBE_CLASS.replace('.', '/') + ".class"
    val classBytes = requireNotNull(
        Thread.currentThread().contextClassLoader.getResourceAsStream(classResource)
    ) {
        "Could not load compiled test probe class $classResource"
    }
    JarOutputStream(Files.newOutputStream(jar)).use { output ->
        output.putNextEntry(JarEntry(classResource))
        classBytes.use { input -> input.copyTo(output) }
        output.closeEntry()
    }
    artifactDirectory.resolve("$EXECUTOR_PROBE_ARTIFACT-$EXECUTOR_PROBE_VERSION.pom").writeText(
        """
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion>
          <groupId>$EXECUTOR_PROBE_GROUP</groupId>
          <artifactId>$EXECUTOR_PROBE_ARTIFACT</artifactId>
          <version>$EXECUTOR_PROBE_VERSION</version>
        </project>
        """.trimIndent()
    )
    return "$EXECUTOR_PROBE_GROUP:$EXECUTOR_PROBE_ARTIFACT:$EXECUTOR_PROBE_VERSION"
}

private fun setUpProbeProject(
    projectDir: Path,
    tool: ProbeBuildTool,
    repository: Path,
    probeOutput: Path
) {
    projectDir.resolve("src/main/java/com/example").toFile().mkdirs()
    projectDir.resolve("src/main/java/com/example/App.java").writeText("class App {}\n")
    projectDir.resolve("rewrite.yaml").writeText(
        """
        ---
        type: specs.openrewrite.org/v1beta/recipe
        name: com.example.ExecutorProbe
        recipeList:
          - $EXECUTOR_PROBE_CLASS:
              outputFile: "${probeOutput.toAbsolutePath()}"
        """.trimIndent()
    )
    when (tool) {
        ProbeBuildTool.GRADLE -> {
            projectDir.resolve("settings.gradle.kts").writeText("rootProject.name = \"probe\"\n")
            projectDir.resolve("build.gradle.kts").writeText("plugins { java }\n")
        }

        ProbeBuildTool.MAVEN -> {
            projectDir.resolve("pom.xml").writeText(
                """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>probe</artifactId>
                  <version>1.0.0</version>
                  <repositories>
                    <repository>
                      <id>executor-probe</id>
                      <url>${repository.toUri()}</url>
                    </repository>
                  </repositories>
                </project>
                """.trimIndent()
            )
        }
    }
}

private fun readProbeRows(file: Path): List<ExecutorProbeRow> {
    assertTrue(Files.isRegularFile(file), "Probe output was not created: $file")
    return Files.readAllLines(file).filter(String::isNotBlank).map { line ->
        val fields = line.split("|", limit = 3)
        require(fields.size == 3) { "Malformed executor probe row: $line" }
        ExecutorProbeRow(
            processId = fields[0].toLong(),
            maximumHeapBytes = fields[1].toLong(),
            inputArguments = fields[2]
        )
    }
}

private val rootSource: Path = Path.of("src/main/java/com/example/Root.java")
private val libKeepSource: Path = Path.of("lib/src/main/java/com/example/Keep.java")
private val libSkipSource: Path = Path.of("lib/src/main/java/com/example/Skip.java")

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

private fun runMultiModuleExclusionScenario(
    tool: MultiModuleBuildTool,
    excludePaths: List<String>,
    expectedChangedPaths: Set<Path>,
    expectedUnchangedPaths: Set<Path>
) {
    requireMavenCentralReachable()
    val projectDir = Files.createTempDirectory("real-plugin-exclusions-")
    val cacheDir = Files.createTempDirectory("real-plugin-exclusions-cache-")
    try {
        setUpMultiModuleExclusionProject(projectDir, tool)
        installRealWrapper(projectDir)

        val result =
            RewriteRunner.builder()
                .projectDir(projectDir)
                .activeRecipe("com.example.integration.FindAndReplace")
                .cacheDir(cacheDir)
                .rewriteConfig(projectDir.resolve("rewrite.yaml"))
                .excludePaths(excludePaths)
                .build()
                .run()

        assertEquals(
            UsedExecutionStage.PLUGIN,
            result.executionDiagnostics.stageUsed,
            "Expected Stage 0 to produce the ${tool.displayName} exclusion run, " +
                "got ${result.executionDiagnostics.stageUsed}"
        )
        assertEquals(
            expectedChangedPaths,
            result.rawDiffs.keys,
            "Unexpected changed paths for ${tool.displayName} exclusions $excludePaths"
        )
        expectedChangedPaths.forEach { path ->
            assertEquals(
                sourceAfter(path),
                projectDir.resolve(path).readText(),
                "Expected $path to be rewritten"
            )
        }
        expectedUnchangedPaths.forEach { path ->
            assertFalse(
                path in result.rawDiffs.keys,
                "Excluded path $path should not be present in raw diffs"
            )
            assertEquals(
                sourceBefore(path),
                projectDir.resolve(path).readText(),
                "Expected $path to remain untouched"
            )
        }
        val estimatedTimeSaved = assertNotNull(result.executionDiagnostics.estimatedTimeSaved)
        assertTrue(estimatedTimeSaved > Duration.ZERO)
    } finally {
        projectDir.toFile().deleteRecursively()
        cacheDir.toFile().deleteRecursively()
    }
}

private fun setUpMultiModuleExclusionProject(projectDir: Path, tool: MultiModuleBuildTool) {
    when (tool) {
        MultiModuleBuildTool.GRADLE -> setUpGradleMultiModuleExclusionProject(projectDir)
        MultiModuleBuildTool.MAVEN -> setUpMavenMultiModuleExclusionProject(projectDir)
    }
    projectDir.writeClassSpacingRecipe()
}

private fun setUpGradleMultiModuleExclusionProject(projectDir: Path) {
    projectDir.resolve("settings.gradle.kts").writeText(
        "rootProject.name = \"multi-exclusions\"\ninclude(\"lib\")\n"
    )
    projectDir.resolve("build.gradle.kts").writeText(
        "plugins { java }\nsubprojects { apply(plugin = \"java\") }\n"
    )
    projectDir.resolve("lib").toFile().mkdirs()
    projectDir.resolve("lib/build.gradle.kts").writeText("")
    writeMultiModuleSources(projectDir)
}

private fun setUpMavenMultiModuleExclusionProject(projectDir: Path) {
    projectDir.resolve("pom.xml").writeText(
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <project>
          <modelVersion>4.0.0</modelVersion>
          <groupId>com.example</groupId>
          <artifactId>parent</artifactId>
          <version>1.0-SNAPSHOT</version>
          <packaging>pom</packaging>
          <modules>
            <module>lib</module>
          </modules>
        </project>
        """.trimIndent()
    )
    projectDir.resolve("lib").toFile().mkdirs()
    projectDir.resolve("lib/pom.xml").writeText(
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <project>
          <modelVersion>4.0.0</modelVersion>
          <parent>
            <groupId>com.example</groupId>
            <artifactId>parent</artifactId>
            <version>1.0-SNAPSHOT</version>
          </parent>
          <artifactId>lib</artifactId>
        </project>
        """.trimIndent()
    )
    writeMultiModuleSources(projectDir)
}

private fun writeMultiModuleSources(projectDir: Path) {
    rootSource.parent?.let { projectDir.resolve(it).toFile().mkdirs() }
    libKeepSource.parent?.let { projectDir.resolve(it).toFile().mkdirs() }
    listOf(rootSource, libKeepSource, libSkipSource).forEach { path ->
        projectDir.resolve(path).writeText(sourceBefore(path))
    }
}

private fun sourceBefore(path: Path): String =
    "class ${path.fileName.toString().removeSuffix(".java")}{}\n"

private fun sourceAfter(path: Path): String = sourceBefore(path).replace("{}", " { }")

private fun Path.writeClassSpacingRecipe() {
    resolve("rewrite.yaml").writeText(
        """
        ---
        type: specs.openrewrite.org/v1beta/recipe
        name: com.example.integration.FindAndReplace
        recipeList:
          - org.openrewrite.text.FindAndReplace:
              find: 'class ([A-Za-z]+)\{\}'
              replace: 'class ${D}1 { }'
              regex: true
        """.trimIndent()
    )
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
    requireMavenCentralReachable()
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
 * HEAD-checks Maven Central with a 3-second timeout. The real-plugin lane is authoritative once
 * selected, so an unavailable prerequisite is a test failure rather than an assumption skip.
 */
internal fun requireMavenCentralReachable() {
    try {
        val conn =
            URL("https://repo.maven.apache.org/maven2/").openConnection() as HttpURLConnection
        conn.connectTimeout = 3_000
        conn.readTimeout = 3_000
        conn.requestMethod = "HEAD"
        conn.connect()
        val code = conn.responseCode
        conn.disconnect()
        check(code in 200..499) { "Maven Central returned HTTP $code" }
    } catch (e: Exception) {
        throw IllegalStateException("Maven Central not reachable: ${e.message}", e)
    }
}
