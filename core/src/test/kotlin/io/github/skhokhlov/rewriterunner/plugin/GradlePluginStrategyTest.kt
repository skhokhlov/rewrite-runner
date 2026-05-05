package io.github.skhokhlov.rewriterunner.plugin

import io.github.skhokhlov.rewriterunner.ExecutionTimeouts
import io.github.skhokhlov.rewriterunner.NoOpRunnerLogger
import io.github.skhokhlov.rewriterunner.config.RepositoryConfig
import io.github.skhokhlov.rewriterunner.config.ToolConfig.Companion.REWRITE_GRADLE_PLUGIN_VERSION
import io.kotest.core.spec.style.FunSpec
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GradlePluginStrategyTest :
    FunSpec({
        var projectDir: Path = Path.of("")

        beforeEach {
            projectDir = Files.createTempDirectory("gps-")
            projectDir.resolve("build.gradle.kts").writeText("")
        }

        afterEach { projectDir.toFile().deleteRecursively() }

        test("generateInitScript wires recipe, artifacts, repositories, and config") {
            val config = Files.createTempFile("rewrite", ".yml")
            val strategy =
                GradlePluginStrategy(
                    NoOpRunnerLogger,
                    ExecutionTimeouts.DEFAULT_PLUGIN_TIMEOUT_SECONDS,
                    REWRITE_GRADLE_PLUGIN_VERSION
                )

            val initScript =
                strategy.generateInitScript(
                    activeRecipe = "com.example.Recipe",
                    recipeArtifacts = listOf("com.example:recipes:1.0.0"),
                    rewriteConfig = config,
                    includeMavenCentral = false,
                    repositories = listOf(RepositoryConfig(url = "https://repo.example.com/maven"))
                )

            val text = initScript.readText()
            assertTrue(text.contains("classpath(\"org.openrewrite:plugin:"))
            assertTrue(text.contains("activeRecipe(\"com.example.Recipe\")"))
            assertTrue(text.contains("add(\"rewrite\", \"com.example:recipes:1.0.0\")"))
            assertTrue(text.contains("configFile = file("))
            assertTrue(text.contains("https://repo.example.com/maven"))
            assertTrue(!text.contains("mavenCentral()"))
            assertTrue(!text.contains("gradlePluginPortal()"))
        }

        test("generateInitScript uses configured rewrite Gradle plugin version") {
            val strategy =
                GradlePluginStrategy(
                    logger = NoOpRunnerLogger,
                    timeoutSeconds = ExecutionTimeouts.DEFAULT_PLUGIN_TIMEOUT_SECONDS,
                    rewritePluginVersion = "7.20.0"
                )

            val initScript =
                strategy.generateInitScript(
                    activeRecipe = "com.example.Recipe",
                    recipeArtifacts = emptyList(),
                    rewriteConfig = null,
                    includeMavenCentral = true,
                    repositories = emptyList()
                )

            val text = initScript.readText()
            assertTrue(text.contains("classpath(\"org.openrewrite:plugin:7.20.0\")"))
        }

        test("generateInitScript adds repositories for plugin management and all projects") {
            val strategy = GradlePluginStrategy(
                logger = NoOpRunnerLogger,
                timeoutSeconds = ExecutionTimeouts.DEFAULT_PLUGIN_TIMEOUT_SECONDS,
                rewritePluginVersion = "7.20.0"
            )

            val initScript =
                strategy.generateInitScript(
                    activeRecipe = "com.example.Recipe",
                    recipeArtifacts = emptyList(),
                    rewriteConfig = null,
                    includeMavenCentral = true,
                    repositories =
                        listOf(
                            RepositoryConfig(
                                url = "https://repo.example.com/maven",
                                username = "alice",
                                password = "secret"
                            )
                        )
                )

            val text = initScript.readText()
            assertTrue(text.contains("gradle.beforeSettings { settings ->"))
            assertTrue(text.contains("settings.pluginManagement {"))
            assertTrue(text.contains("settings.dependencyResolutionManagement {"))
            assertTrue(text.contains("allprojects {"))
            assertEquals(4, Regex("""\bmavenCentral\(\)""").findAll(text).count())
            assertEquals(4, Regex("""\bgradlePluginPortal\(\)""").findAll(text).count())
            assertEquals(
                4,
                Regex.fromLiteral("https://repo.example.com/maven").findAll(text).count()
            )
            assertEquals(4, Regex.fromLiteral("username = \"alice\"").findAll(text).count())
            assertEquals(4, Regex.fromLiteral("password = \"secret\"").findAll(text).count())
        }

        test("generateInitScript adds recipe artifacts to root rewrite configuration only") {
            val strategy = GradlePluginStrategy(
                logger = NoOpRunnerLogger,
                timeoutSeconds = ExecutionTimeouts.DEFAULT_PLUGIN_TIMEOUT_SECONDS,
                rewritePluginVersion = "7.20.0"
            )

            val initScript =
                strategy.generateInitScript(
                    activeRecipe = "com.example.Recipe",
                    recipeArtifacts = listOf("org.openrewrite.recipe:rewrite-rewrite:0.21.1"),
                    rewriteConfig = null,
                    includeMavenCentral = true,
                    repositories = emptyList()
                )

            val text = initScript.readText()
            val allprojectsStart = text.indexOf("allprojects {")
            val allprojectsBody = text.substring(allprojectsStart)
            assertTrue(text.contains("rootProject {"))
            assertEquals(
                1,
                Regex.fromLiteral("apply plugin: org.openrewrite.gradle.RewritePlugin")
                    .findAll(text)
                    .count()
            )
            assertTrue(text.indexOf("add(\"rewrite\",") < allprojectsStart)
            assertTrue(!text.contains("rewrite(\"org.openrewrite.recipe:rewrite-rewrite:0.21.1\")"))
            assertTrue(!allprojectsBody.contains("add(\"rewrite\","))
        }

        test("run returns success and runs apply when dry run patch has changes") {
            val commands = mutableListOf<List<String>>()
            val strategy =
                object : GradlePluginStrategy(
                    logger = NoOpRunnerLogger,
                    timeoutSeconds = ExecutionTimeouts.DEFAULT_PLUGIN_TIMEOUT_SECONDS,
                    rewritePluginVersion = REWRITE_GRADLE_PLUGIN_VERSION
                ) {
                    override fun execute(projectDir: Path, command: List<String>): Int? {
                        commands.add(command)
                        if ("rewriteDryRun" in command) {
                            projectDir.resolve("build/reports/rewrite").createDirectories()
                            projectDir.resolve("build/reports/rewrite/rewrite.patch").writeText(
                                """
                                diff --git a/src/A.java b/src/A.java
                                --- a/src/A.java
                                +++ b/src/A.java
                                @@ -1 +1 @@
                                -class A {}
                                +class A { }
                                """.trimIndent()
                            )
                        }
                        return 0
                    }
                }

            val result =
                strategy.run(
                    projectDir = projectDir,
                    activeRecipe = "com.example.Recipe",
                    recipeArtifacts = emptyList(),
                    rewriteConfig = null,
                    rewriteConfigContent = null,
                    dryRun = false,
                    includeMavenCentral = true,
                    repositories = emptyList()
                )

            assertIs<PluginRunResult.Success>(result)
            assertEquals(listOf("rewriteDryRun", "rewriteRun"), commands.map { it[1] })
            assertEquals(listOf(projectDir.resolve("src/A.java")), result.changedFiles)
        }

        test("run detects patch files emitted by Gradle subprojects") {
            val commands = mutableListOf<List<String>>()
            val strategy =
                object : GradlePluginStrategy(
                    logger = NoOpRunnerLogger,
                    timeoutSeconds = ExecutionTimeouts.DEFAULT_PLUGIN_TIMEOUT_SECONDS,
                    rewritePluginVersion = REWRITE_GRADLE_PLUGIN_VERSION
                ) {
                    override fun execute(projectDir: Path, command: List<String>): Int? {
                        commands.add(command)
                        if ("rewriteDryRun" in command) {
                            projectDir.resolve("module-a/build/reports/rewrite").createDirectories()
                            val patchFile =
                                projectDir.resolve(
                                    "module-a/build/reports/rewrite/rewrite.patch"
                                )
                            patchFile.writeText(
                                """
                                diff --git a/src/main/java/A.java b/src/main/java/A.java
                                --- a/src/main/java/A.java
                                +++ b/src/main/java/A.java
                                @@ -1 +1 @@
                                -class A {}
                                +class A { }
                                """.trimIndent()
                            )
                        }
                        return 0
                    }
                }

            val result =
                strategy.run(
                    projectDir = projectDir,
                    activeRecipe = "com.example.Recipe",
                    recipeArtifacts = emptyList(),
                    rewriteConfig = null,
                    rewriteConfigContent = null,
                    dryRun = false,
                    includeMavenCentral = true,
                    repositories = emptyList()
                )

            assertIs<PluginRunResult.Success>(result)
            assertEquals(listOf("rewriteDryRun", "rewriteRun"), commands.map { it[1] })
            assertEquals(
                listOf(projectDir.resolve("module-a/src/main/java/A.java")),
                result.changedFiles
            )
            assertEquals(setOf(Path.of("module-a/src/main/java/A.java")), result.diffs.keys)
            assertTrue(
                result.diffs.getValue(Path.of("module-a/src/main/java/A.java"))
                    .contains("diff --git a/module-a/src/main/java/A.java")
            )
        }

        test("run returns no changes when patch file is absent") {
            val strategy =
                object : GradlePluginStrategy(
                    logger = NoOpRunnerLogger,
                    timeoutSeconds = ExecutionTimeouts.DEFAULT_PLUGIN_TIMEOUT_SECONDS,
                    rewritePluginVersion = REWRITE_GRADLE_PLUGIN_VERSION
                ) {
                    override fun execute(projectDir: Path, command: List<String>): Int? = 0
                }

            val result =
                strategy.run(
                    projectDir = projectDir,
                    activeRecipe = "com.example.Recipe",
                    recipeArtifacts = emptyList(),
                    rewriteConfig = null,
                    rewriteConfigContent = null,
                    dryRun = false,
                    includeMavenCentral = true,
                    repositories = emptyList()
                )

            assertIs<PluginRunResult.NoChanges>(result)
        }

        test("run reports did not run when dry run execution returns null") {
            val strategy =
                object : GradlePluginStrategy(
                    logger = NoOpRunnerLogger,
                    timeoutSeconds = ExecutionTimeouts.DEFAULT_PLUGIN_TIMEOUT_SECONDS,
                    rewritePluginVersion = REWRITE_GRADLE_PLUGIN_VERSION
                ) {
                    override fun execute(projectDir: Path, command: List<String>): Int? = null
                }

            val result =
                strategy.run(
                    projectDir = projectDir,
                    activeRecipe = "com.example.Recipe",
                    recipeArtifacts = emptyList(),
                    rewriteConfig = null,
                    rewriteConfigContent = null,
                    dryRun = true,
                    includeMavenCentral = true,
                    repositories = emptyList()
                )

            assertEquals(
                PluginRunResult.Failed("Gradle rewriteDryRun did not start or timed out"),
                result
            )
        }

        test("run returns failed when apply fails after successful dry run") {
            val strategy =
                object : GradlePluginStrategy(
                    logger = NoOpRunnerLogger,
                    timeoutSeconds = ExecutionTimeouts.DEFAULT_PLUGIN_TIMEOUT_SECONDS,
                    rewritePluginVersion = REWRITE_GRADLE_PLUGIN_VERSION
                ) {
                    override fun execute(projectDir: Path, command: List<String>): Int? {
                        if ("rewriteDryRun" in command) {
                            projectDir.resolve("build/reports/rewrite").createDirectories()
                            projectDir.resolve("build/reports/rewrite/rewrite.patch").writeText(
                                """
                                diff --git a/src/A.java b/src/A.java
                                --- a/src/A.java
                                +++ b/src/A.java
                                @@ -1 +1 @@
                                -class A {}
                                +class A { }
                                """.trimIndent()
                            )
                            return 0
                        }
                        return 2
                    }
                }

            val result =
                strategy.run(
                    projectDir = projectDir,
                    activeRecipe = "com.example.Recipe",
                    recipeArtifacts = emptyList(),
                    rewriteConfig = null,
                    rewriteConfigContent = null,
                    dryRun = false,
                    includeMavenCentral = true,
                    repositories = emptyList()
                )

            assertEquals(PluginRunResult.Failed("Gradle rewriteRun exited with 2"), result)
        }

        test("run honors configured plugin timeout") {
            val gradlew = projectDir.resolve("gradlew").toFile()
            gradlew.writeText(
                """
                #!/bin/sh
                sleep 5
                exit 0
                """.trimIndent()
            )
            gradlew.setExecutable(true)

            val strategy =
                GradlePluginStrategy(
                    NoOpRunnerLogger,
                    timeoutSeconds = 1,
                    REWRITE_GRADLE_PLUGIN_VERSION
                )
            val start = System.currentTimeMillis()
            val result =
                strategy.run(
                    projectDir = projectDir,
                    activeRecipe = "com.example.Recipe",
                    recipeArtifacts = emptyList(),
                    rewriteConfig = null,
                    rewriteConfigContent = null,
                    dryRun = true,
                    includeMavenCentral = true,
                    repositories = emptyList()
                )
            val elapsed = System.currentTimeMillis() - start

            assertIs<PluginRunResult.Failed>(result)
            assertTrue(
                elapsed < 4_000,
                "Configured 1s timeout should stop rewriteDryRun promptly, took ${elapsed}ms"
            )
        }
    })
