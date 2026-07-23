package io.github.skhokhlov.rewriterunner.integration

import io.github.skhokhlov.rewriterunner.ExecutorOutcome
import io.github.skhokhlov.rewriterunner.ExecutorPhase
import io.github.skhokhlov.rewriterunner.LogicalExecutor
import io.github.skhokhlov.rewriterunner.RewriteRunner
import io.github.skhokhlov.rewriterunner.RunResult
import io.github.skhokhlov.rewriterunner.RunnerLogger
import io.github.skhokhlov.rewriterunner.UsedExecutionStage
import io.kotest.core.spec.style.FunSpec
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.CopyOnWriteArrayList
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.openrewrite.InMemoryExecutionContext
import org.openrewrite.Result
import org.openrewrite.internal.InMemoryLargeSourceSet
import org.openrewrite.java.ChangeType
import org.openrewrite.java.JavaParser

class FallbackTypeAttributionIntegrationTest :
    FunSpec({
        test("ChangeType requires the external type to be attributed") {
            ExternalDependencyFixture.create().use { fixture ->
                assertTrue(
                    changeTypeResults(emptyList()).isEmpty(),
                    "ChangeType must not match an unattributed external type"
                )

                val attributedResults = changeTypeResults(listOf(fixture.jar))
                assertEquals(1, attributedResults.size)
                assertEquals(EXPECTED_SOURCE, attributedResults.single().after?.printAll())
            }
        }

        test("Stage 1 build-tool classpath attributes the external type after Stage 0 fails") {
            FallbackScenarioFixture.create(forceStage2 = false).use { fixture ->
                val result = fixture.run()

                assertRecovery(result, fixture, UsedExecutionStage.BUILD_TOOL)
            }
        }

        test("Stage 2 dependency resolution attributes the type when Stage 1 fails") {
            FallbackScenarioFixture.create(forceStage2 = true).use { fixture ->
                val result = fixture.run()

                assertNotEquals(
                    0,
                    fixture.exitCodeFor("printClasspathForOpenRewrite"),
                    "The unresolved runtime dependency must make Stage 1 fail"
                )
                assertEquals(
                    0,
                    fixture.exitCodeFor("dependencies"),
                    "Gradle dependency reporting must remain usable for Stage 2"
                )
                assertRecovery(result, fixture, UsedExecutionStage.DEPENDENCY_RESOLUTION)
            }
        }
    })

private fun changeTypeResults(classpath: List<Path>): List<Result> {
    val ctx = InMemoryExecutionContext { failure ->
        throw AssertionError("OpenRewrite failed while exercising ChangeType", failure)
    }
    val parser = JavaParser.fromJavaVersion().classpath(classpath).build()
    val sourceFiles = parser.parse(ctx, ORIGINAL_SOURCE).toList()
    return ChangeType(EXTERNAL_TYPE, REPLACEMENT_TYPE, true)
        .run(InMemoryLargeSourceSet(sourceFiles), ctx)
        .changeset
        .allResults
}

private data class ExternalDependencyFixture(val root: Path, val jar: Path) : AutoCloseable {
    override fun close() {
        root.toFile().deleteRecursively()
    }

    companion object {
        fun create(): ExternalDependencyFixture {
            val root = Files.createTempDirectory("fallback-type-attribution-dependency-")
            return ExternalDependencyFixture(root, compileExternalDependency(root))
        }
    }
}

private data class FallbackScenarioFixture(
    val root: Path,
    val projectDir: Path,
    val cacheDir: Path,
    val runnerUserHome: Path,
    val wrapperLog: Path,
    val sourceFile: Path,
    val logger: RecordingRunnerLogger
) : AutoCloseable {
    fun exitCodeFor(argument: String): Int {
        val exit = wrapperLog.readLines().single {
            it.startsWith("EXIT|") && it.substringAfter('|').substringAfter('|').contains(argument)
        }
        return exit.substringAfter('|').substringBefore('|').toInt()
    }

    fun run(): RunResult = RewriteRunner.builder()
        .projectDir(projectDir)
        .activeRecipe(CHANGE_EXTERNAL_TYPE_RECIPE)
        .rewriteConfig(projectDir.resolve("rewrite.yaml"))
        .configFile(projectDir.resolve("rewriterunner.yml"))
        .cacheDir(cacheDir)
        .lstWorkerJvmArgs(
            listOf(
                "-Xmx1g",
                "-Duser.home=${runnerUserHome.toAbsolutePath()}"
            )
        )
        .logger(logger)
        .build()
        .run()

    override fun close() {
        root.toFile().deleteRecursively()
    }

    companion object {
        fun create(forceStage2: Boolean): FallbackScenarioFixture {
            val root = Files.createTempDirectory("fallback-type-attribution-")
            val projectDir = root.resolve("project").createDirectories()
            val cacheDir = root.resolve("cache").createDirectories()
            val runnerUserHome = root.resolve("runner-user-home").createDirectories()
            val gradleUserHome = root.resolve("gradle-user-home").createDirectories()
            val repository = root.resolve("maven-repository").createDirectories()
            val wrapperLog = root.resolve("gradle-invocations.log")

            val dependencyRoot = root.resolve("dependency").createDirectories()
            val dependency = compileExternalDependency(dependencyRoot)
            publishExternalDependency(dependency, repository)

            projectDir.resolve("settings.gradle.kts").writeText(
                "rootProject.name = \"type-attribution-fixture\"\n"
            )
            projectDir.resolve("build.gradle.kts").writeText(
                buildString {
                    appendLine("plugins { java }")
                    appendLine("repositories {")
                    appendLine("    maven { url = uri(\"${repository.toUri()}\") }")
                    appendLine("}")
                    appendLine("dependencies {")
                    appendLine("    implementation(\"$EXTERNAL_COORDINATE\")")
                    if (forceStage2) {
                        appendLine("    runtimeOnly(\"$MISSING_RUNTIME_COORDINATE\")")
                    }
                    appendLine("}")
                }
            )
            projectDir.resolve("rewrite.yaml").writeText(
                """
                ---
                type: specs.openrewrite.org/v1beta/recipe
                name: $CHANGE_EXTERNAL_TYPE_RECIPE
                recipeList:
                  - org.openrewrite.java.ChangeType:
                      oldFullyQualifiedTypeName: $EXTERNAL_TYPE
                      newFullyQualifiedTypeName: $REPLACEMENT_TYPE
                      ignoreDefinition: true
                """.trimIndent() + "\n"
            )
            projectDir.resolve("rewriterunner.yml").writeText(
                """
                includeMavenCentral: false
                rewriteGradlePluginVersion: $UNAVAILABLE_PLUGIN_VERSION
                artifactRepositories:
                  - url: "${repository.toUri()}"
                """.trimIndent() + "\n"
            )
            val sourceFile = projectDir.resolve(SOURCE_PATH).also {
                it.parent.createDirectories()
                it.writeText(ORIGINAL_SOURCE)
            }
            installGradleWrapper(projectDir, gradleUserHome, wrapperLog)

            return FallbackScenarioFixture(
                root = root,
                projectDir = projectDir,
                cacheDir = cacheDir,
                runnerUserHome = runnerUserHome,
                wrapperLog = wrapperLog,
                sourceFile = sourceFile,
                logger = RecordingRunnerLogger()
            )
        }
    }
}

private fun assertRecovery(
    result: RunResult,
    fixture: FallbackScenarioFixture,
    expectedStage: UsedExecutionStage
) {
    val pluginAttempt = result.executionDiagnostics.executorAttempts.single {
        it.executor == LogicalExecutor.GRADLE_PLUGIN
    }
    assertEquals(ExecutorPhase.PLUGIN_DRY_RUN, pluginAttempt.phase)
    assertEquals(ExecutorOutcome.FAILED, pluginAttempt.outcome)
    val pluginExitCode = assertNotNull(pluginAttempt.exitCode)
    assertNotEquals(0, pluginExitCode)
    assertEquals(pluginExitCode, fixture.exitCodeFor("rewriteDryRun"))
    assertTrue(
        fixture.wrapperLog.readLines().first().contains("rewriteDryRun"),
        "The first real Gradle invocation must be the Stage 0 plugin attempt"
    )
    assertTrue(
        fixture.logger.debugMessages.any {
            it.contains("Could not find org.openrewrite:plugin:$UNAVAILABLE_PLUGIN_VERSION")
        },
        "Stage 0 did not fail for the deliberately unavailable OpenRewrite plugin:\n" +
            fixture.logger.debugMessages.joinToString("\n")
    )

    assertEquals(expectedStage, result.executionDiagnostics.stageUsed)
    assertEquals(1, result.executionDiagnostics.resolvedJarCount)
    assertEquals(setOf(Path.of(SOURCE_PATH)), result.rawDiffs.keys)
    assertEquals(EXPECTED_SOURCE, fixture.sourceFile.readText())
    assertEquals(
        EXPECTED_SEMANTIC_DIFF,
        result.rawDiffs.getValue(Path.of(SOURCE_PATH)).semanticChangeLines()
    )

    val workerAttempt = result.executionDiagnostics.executorAttempts.single {
        it.executor == LogicalExecutor.LST_WORKER
    }
    assertEquals(ExecutorOutcome.SUCCESS, workerAttempt.outcome)
    assertNotEquals(ProcessHandle.current().pid(), assertNotNull(workerAttempt.processId))
}

private fun compileExternalDependency(root: Path): Path {
    val sourceDir = root.resolve("source/com/acme").createDirectories()
    val classesDir = root.resolve("classes").createDirectories()
    val source = sourceDir.resolve("External.java")
    source.writeText(
        """
        package com.acme;

        public class External {
        }
        """.trimIndent() + "\n"
    )
    val compiler = checkNotNull(ToolProvider.getSystemJavaCompiler()) {
        "A JDK compiler is required for the type-attribution integration fixture"
    }
    assertEquals(
        0,
        compiler.run(null, null, null, "-d", classesDir.toString(), source.toString()),
        "Could not compile the external dependency fixture"
    )

    val jar = root.resolve("external-1.0.jar")
    JarOutputStream(Files.newOutputStream(jar)).use { output ->
        Files.walk(classesDir).use { compiled ->
            compiled
                .filter(Files::isRegularFile)
                .forEach { classFile ->
                    val entryName = classesDir.relativize(classFile).toString().replace('\\', '/')
                    output.putNextEntry(JarEntry(entryName))
                    Files.copy(classFile, output)
                    output.closeEntry()
                }
        }
    }
    return jar
}

private fun publishExternalDependency(jar: Path, repository: Path) {
    val artifactDir = repository.resolve("com/acme/external/1.0").createDirectories()
    Files.copy(
        jar,
        artifactDir.resolve("external-1.0.jar"),
        StandardCopyOption.REPLACE_EXISTING
    )
    artifactDir.resolve("external-1.0.pom").writeText(
        """
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion>
          <groupId>com.acme</groupId>
          <artifactId>external</artifactId>
          <version>1.0</version>
        </project>
        """.trimIndent() + "\n"
    )
}

private fun installGradleWrapper(projectDir: Path, gradleUserHome: Path, log: Path) {
    val executable = Path.of(
        checkNotNull(System.getProperty("rewriterunner.test.gradleExecutable")) {
            "Run this test through the :cli:testIntegration Gradle task"
        }
    ).toAbsolutePath()

    if (isWindows) {
        projectDir.resolve("gradlew.bat").writeText(
            """
            @echo off
            echo START^|%*>>"${log.toAbsolutePath()}"
            set "GRADLE_USER_HOME=${gradleUserHome.toAbsolutePath()}"
            call "$executable" --offline %*
            set "status=%ERRORLEVEL%"
            echo EXIT^|%status%^|%*>>"${log.toAbsolutePath()}"
            exit /b %status%
            """.trimIndent() + "\r\n"
        )
    } else {
        val gradlew = projectDir.resolve("gradlew")
        gradlew.writeText(
            """
            #!/bin/sh
            set -u
            printf 'START|%s\n' "$D*" >> '${log.toAbsolutePath().shellSingleQuoted()}'
            export GRADLE_USER_HOME='${gradleUserHome.toAbsolutePath().shellSingleQuoted()}'
            '${executable.shellSingleQuoted()}' --offline "$D@"
            status="$D?"
            printf 'EXIT|%s|%s\n' "${D}status" "$D*" >> '${log.toAbsolutePath().shellSingleQuoted()}'
            exit "${D}status"
            """.trimIndent() + "\n"
        )
        Files.setPosixFilePermissions(gradlew, posixExecutable)
    }
}

private fun Path.shellSingleQuoted(): String = toString().replace("'", "'\"'\"'")

private fun String.semanticChangeLines(): List<String> = lineSequence()
    .filter {
        (it.startsWith("+") && !it.startsWith("+++")) ||
            (it.startsWith("-") && !it.startsWith("---"))
    }
    .toList()

private class RecordingRunnerLogger : RunnerLogger {
    val debugMessages = CopyOnWriteArrayList<String>()

    override fun lifecycle(message: String) = Unit

    override fun info(message: String) = Unit

    override fun debug(message: String) {
        debugMessages += message
    }

    override fun warn(message: String) = Unit

    override fun error(message: String, cause: Throwable?) = Unit
}

private const val EXTERNAL_TYPE = "com.acme.External"
private const val REPLACEMENT_TYPE = "com.acme.Replacement"
private const val EXTERNAL_COORDINATE = "com.acme:external:1.0"
private const val MISSING_RUNTIME_COORDINATE = "com.acme:missing-runtime:1.0"
private const val UNAVAILABLE_PLUGIN_VERSION = "0.0.0-stage0-must-fail"
private const val CHANGE_EXTERNAL_TYPE_RECIPE = "com.acme.ChangeExternalType"
private const val SOURCE_PATH = "src/main/java/com/example/UsesExternal.java"

private val ORIGINAL_SOURCE =
    """
    package com.example;

    import com.acme.External;

    final class UsesExternal {
        private External value;

        External getValue() {
            return value;
        }
    }
    """.trimIndent() + "\n"

private val EXPECTED_SOURCE =
    """
    package com.example;

    import com.acme.Replacement;

    final class UsesExternal {
        private Replacement value;

        Replacement getValue() {
            return value;
        }
    }
    """.trimIndent() + "\n"

private val EXPECTED_SEMANTIC_DIFF =
    listOf(
        "-import com.acme.External;",
        "+import com.acme.Replacement;",
        "-    private External value;",
        "+    private Replacement value;",
        "-    External getValue() {",
        "+    Replacement getValue() {"
    )
