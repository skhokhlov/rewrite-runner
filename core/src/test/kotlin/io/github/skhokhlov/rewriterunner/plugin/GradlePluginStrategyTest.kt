package io.github.skhokhlov.rewriterunner.plugin

import io.github.skhokhlov.rewriterunner.NoOpRunnerLogger
import io.github.skhokhlov.rewriterunner.config.RepositoryConfig
import io.github.skhokhlov.rewriterunner.config.ToolConfigDefaults
import io.kotest.core.spec.style.FunSpec
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
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
                    ToolConfigDefaults.PLUGIN_RUN_TIMEOUT,
                    ToolConfigDefaults.REWRITE_GRADLE_PLUGIN_VERSION
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
                    timeout = ToolConfigDefaults.PLUGIN_RUN_TIMEOUT,
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
                timeout = ToolConfigDefaults.PLUGIN_RUN_TIMEOUT,
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

        test("generateInitScript emits exclusion lines inside rewrite block when paths present") {
            val strategy = GradlePluginStrategy(
                logger = NoOpRunnerLogger,
                timeout = ToolConfigDefaults.PLUGIN_RUN_TIMEOUT,
                rewritePluginVersion = ToolConfigDefaults.REWRITE_GRADLE_PLUGIN_VERSION
            )

            val initScript =
                strategy.generateInitScript(
                    activeRecipe = "com.example.Recipe",
                    recipeArtifacts = emptyList(),
                    rewriteConfig = null,
                    includeMavenCentral = true,
                    repositories = emptyList(),
                    excludePaths = listOf("**/generated/**", "src/test/**")
                )

            val text = initScript.readText()
            // Locate the rewrite { } block bound to rootProject and assert exclusions live there.
            val rewriteStart = text.indexOf("rewrite {")
            assertTrue(rewriteStart >= 0, "rewrite { } block missing")
            val rewriteEnd = text.indexOf("    }", rewriteStart)
            assertTrue(rewriteEnd > rewriteStart)
            val rewriteBody = text.substring(rewriteStart, rewriteEnd)
            assertTrue(
                rewriteBody.contains("exclusion(\"**/generated/**\")"),
                "Expected exclusion line for **/generated/** inside rewrite { }: $rewriteBody"
            )
            assertTrue(
                rewriteBody.contains("exclusion(\"src/test/**\")"),
                "Expected exclusion line for src/test/** inside rewrite { }: $rewriteBody"
            )
        }

        test("generateInitScript emits no exclusion lines when paths empty") {
            val strategy = GradlePluginStrategy(
                logger = NoOpRunnerLogger,
                timeout = ToolConfigDefaults.PLUGIN_RUN_TIMEOUT,
                rewritePluginVersion = ToolConfigDefaults.REWRITE_GRADLE_PLUGIN_VERSION
            )

            val initScript =
                strategy.generateInitScript(
                    activeRecipe = "com.example.Recipe",
                    recipeArtifacts = emptyList(),
                    rewriteConfig = null,
                    includeMavenCentral = true,
                    repositories = emptyList(),
                    excludePaths = emptyList()
                )

            val text = initScript.readText()
            assertTrue(!text.contains("exclusion("), "No exclusion lines expected: $text")
        }

        test("generateInitScript replaces plainTextMasks inside rewrite block") {
            val strategy = GradlePluginStrategy(
                logger = NoOpRunnerLogger,
                timeout = ToolConfigDefaults.PLUGIN_RUN_TIMEOUT,
                rewritePluginVersion = ToolConfigDefaults.REWRITE_GRADLE_PLUGIN_VERSION
            )

            val initScript =
                strategy.generateInitScript(
                    activeRecipe = "com.example.Recipe",
                    recipeArtifacts = emptyList(),
                    rewriteConfig = null,
                    includeMavenCentral = true,
                    repositories = emptyList(),
                    plainTextMasks = listOf("**/CODEOWNERS", "**/*.txt")
                )

            val text = initScript.readText()
            val rewriteStart = text.indexOf("rewrite {")
            assertTrue(rewriteStart >= 0, "rewrite { } block missing")
            val rewriteEnd = text.indexOf("    }", rewriteStart)
            assertTrue(rewriteEnd > rewriteStart)
            val rewriteBody = text.substring(rewriteStart, rewriteEnd)
            assertTrue(
                rewriteBody.contains("plainTextMasks.clear()"),
                "Expected plainTextMasks.clear() inside rewrite { }: $rewriteBody"
            )
            assertTrue(
                rewriteBody.contains("plainTextMask(\"**/CODEOWNERS\")"),
                "Expected CODEOWNERS plainTextMask inside rewrite { }: $rewriteBody"
            )
            assertTrue(
                rewriteBody.contains("plainTextMask(\"**/*.txt\")"),
                "Expected txt plainTextMask inside rewrite { }: $rewriteBody"
            )
        }

        test("generateInitScript omits plainTextMask lines when masks empty") {
            val strategy = GradlePluginStrategy(
                logger = NoOpRunnerLogger,
                timeout = ToolConfigDefaults.PLUGIN_RUN_TIMEOUT,
                rewritePluginVersion = ToolConfigDefaults.REWRITE_GRADLE_PLUGIN_VERSION
            )

            val initScript =
                strategy.generateInitScript(
                    activeRecipe = "com.example.Recipe",
                    recipeArtifacts = emptyList(),
                    rewriteConfig = null,
                    includeMavenCentral = true,
                    repositories = emptyList(),
                    plainTextMasks = emptyList()
                )

            val text = initScript.readText()
            assertTrue(!text.contains("plainTextMasks.clear()"), "No clear expected: $text")
            assertTrue(!text.contains("plainTextMask("), "No plainTextMask lines expected: $text")
        }

        test("generateInitScript escapes Groovy specials in glob patterns") {
            val strategy = GradlePluginStrategy(
                logger = NoOpRunnerLogger,
                timeout = ToolConfigDefaults.PLUGIN_RUN_TIMEOUT,
                rewritePluginVersion = ToolConfigDefaults.REWRITE_GRADLE_PLUGIN_VERSION
            )

            val initScript =
                strategy.generateInitScript(
                    activeRecipe = "com.example.Recipe",
                    recipeArtifacts = emptyList(),
                    rewriteConfig = null,
                    includeMavenCentral = true,
                    repositories = emptyList(),
                    excludePaths = listOf("path\\with\\backslash", "has\"quote", "has\$dollar")
                )

            val text = initScript.readText()
            assertTrue(
                text.contains("exclusion(\"path\\\\with\\\\backslash\")"),
                "Backslashes must be doubled: $text"
            )
            assertTrue(
                text.contains("exclusion(\"has\\\"quote\")"),
                "Double-quotes must be backslash-escaped: $text"
            )
            assertTrue(
                text.contains("exclusion(\"has\\\$dollar\")"),
                "Groovy dollar sign must be backslash-escaped: $text"
            )
        }

        test("generateInitScript adds recipe artifacts to root rewrite configuration only") {
            val strategy = GradlePluginStrategy(
                logger = NoOpRunnerLogger,
                timeout = ToolConfigDefaults.PLUGIN_RUN_TIMEOUT,
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
                    timeout = ToolConfigDefaults.PLUGIN_RUN_TIMEOUT,
                    rewritePluginVersion = ToolConfigDefaults.REWRITE_GRADLE_PLUGIN_VERSION
                ) {
                    override fun execute(
                        projectDir: Path,
                        command: List<String>,
                        timeout: Duration
                    ): Int? {
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
                    artifactRepositories = emptyList()
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
                    timeout = ToolConfigDefaults.PLUGIN_RUN_TIMEOUT,
                    rewritePluginVersion = ToolConfigDefaults.REWRITE_GRADLE_PLUGIN_VERSION
                ) {
                    override fun execute(
                        projectDir: Path,
                        command: List<String>,
                        timeout: Duration
                    ): Int? {
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
                    artifactRepositories = emptyList()
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
                    timeout = ToolConfigDefaults.PLUGIN_RUN_TIMEOUT,
                    rewritePluginVersion = ToolConfigDefaults.REWRITE_GRADLE_PLUGIN_VERSION
                ) {
                    override fun execute(
                        projectDir: Path,
                        command: List<String>,
                        timeout: Duration
                    ): Int? = 0
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
                    artifactRepositories = emptyList()
                )

            assertIs<PluginRunResult.NoChanges>(result)
        }

        test("run reports did not run when dry run execution returns null") {
            val strategy =
                object : GradlePluginStrategy(
                    logger = NoOpRunnerLogger,
                    timeout = ToolConfigDefaults.PLUGIN_RUN_TIMEOUT,
                    rewritePluginVersion = ToolConfigDefaults.REWRITE_GRADLE_PLUGIN_VERSION
                ) {
                    override fun execute(
                        projectDir: Path,
                        command: List<String>,
                        timeout: Duration
                    ): Int? = null
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
                    artifactRepositories = emptyList()
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
                    timeout = ToolConfigDefaults.PLUGIN_RUN_TIMEOUT,
                    rewritePluginVersion = ToolConfigDefaults.REWRITE_GRADLE_PLUGIN_VERSION
                ) {
                    override fun execute(
                        projectDir: Path,
                        command: List<String>,
                        timeout: Duration
                    ): Int? {
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
                    artifactRepositories = emptyList()
                )

            assertEquals(PluginRunResult.Failed("Gradle rewriteRun exited with 2"), result)
        }

        test("run passes configured plugin timeout to execute") {
            var capturedTimeout: Duration? = null
            val configuredTimeout = Duration.ofSeconds(42)
            val strategy =
                object : GradlePluginStrategy(
                    logger = NoOpRunnerLogger,
                    timeout = configuredTimeout,
                    rewritePluginVersion = ToolConfigDefaults.REWRITE_GRADLE_PLUGIN_VERSION
                ) {
                    override fun execute(
                        projectDir: Path,
                        command: List<String>,
                        timeout: Duration
                    ): Int? {
                        capturedTimeout = timeout
                        return null
                    }
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
                    artifactRepositories = emptyList()
                )

            assertEquals(configuredTimeout, capturedTimeout)
            assertEquals(
                PluginRunResult.Failed("Gradle rewriteDryRun did not start or timed out"),
                result
            )
        }
    })
