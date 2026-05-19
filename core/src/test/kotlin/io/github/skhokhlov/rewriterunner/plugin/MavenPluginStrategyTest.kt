package io.github.skhokhlov.rewriterunner.plugin

import io.github.skhokhlov.rewriterunner.NoOpRunnerLogger
import io.github.skhokhlov.rewriterunner.config.ToolConfigDefaults
import io.kotest.core.spec.style.FunSpec
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MavenPluginStrategyTest :
    FunSpec({
        var projectDir: Path = Path.of("")

        beforeEach {
            projectDir = Files.createTempDirectory("mps-")
            projectDir.resolve("pom.xml").writeText("<project/>")
        }

        afterEach { projectDir.toFile().deleteRecursively() }

        test("buildCommand includes active recipe, artifacts, and config location") {
            val config = Files.createTempFile("rewrite", ".yml")
            val strategy =
                MavenPluginStrategy(
                    NoOpRunnerLogger,
                    ToolConfigDefaults.PLUGIN_RUN_TIMEOUT,
                    ToolConfigDefaults.REWRITE_MAVEN_PLUGIN_VERSION
                )

            val command =
                strategy.buildCommand(
                    projectDir = projectDir,
                    goal = "dryRun",
                    activeRecipe = "com.example.Recipe",
                    recipeArtifacts = listOf("com.example:recipes:1.0.0", "com.example:more:2.0.0"),
                    rewriteConfig = config
                )

            assertEquals("mvn", command.first())
            assertTrue(
                command.any {
                    it.contains("rewrite-maven-plugin") && it.endsWith(":dryRun")
                }
            )
            assertTrue(command.contains("-Drewrite.activeRecipes=com.example.Recipe"))
            assertTrue(
                command.contains(
                    "-Drewrite.recipeArtifactCoordinates=" +
                        "com.example:recipes:1.0.0,com.example:more:2.0.0"
                )
            )
            assertTrue(command.contains("-Drewrite.configLocation=${config.toAbsolutePath()}"))
        }

        test("buildCommand emits -Drewrite.exclusions when excludePaths non-empty") {
            val strategy =
                MavenPluginStrategy(
                    NoOpRunnerLogger,
                    ToolConfigDefaults.PLUGIN_RUN_TIMEOUT,
                    ToolConfigDefaults.REWRITE_MAVEN_PLUGIN_VERSION
                )

            val command =
                strategy.buildCommand(
                    projectDir = projectDir,
                    goal = "dryRun",
                    activeRecipe = "com.example.Recipe",
                    recipeArtifacts = emptyList(),
                    rewriteConfig = null,
                    excludePaths = listOf("src/test/**")
                )

            assertTrue(command.contains("-Drewrite.exclusions=src/test/**"))
        }

        test("buildCommand omits -Drewrite.exclusions when excludePaths empty") {
            val strategy =
                MavenPluginStrategy(
                    NoOpRunnerLogger,
                    ToolConfigDefaults.PLUGIN_RUN_TIMEOUT,
                    ToolConfigDefaults.REWRITE_MAVEN_PLUGIN_VERSION
                )

            val command =
                strategy.buildCommand(
                    projectDir = projectDir,
                    goal = "dryRun",
                    activeRecipe = "com.example.Recipe",
                    recipeArtifacts = emptyList(),
                    rewriteConfig = null,
                    excludePaths = emptyList()
                )

            assertTrue(command.none { it.startsWith("-Drewrite.exclusions") })
        }

        test("buildCommand joins multiple globs with comma") {
            val strategy =
                MavenPluginStrategy(
                    NoOpRunnerLogger,
                    ToolConfigDefaults.PLUGIN_RUN_TIMEOUT,
                    ToolConfigDefaults.REWRITE_MAVEN_PLUGIN_VERSION
                )

            val command =
                strategy.buildCommand(
                    projectDir = projectDir,
                    goal = "dryRun",
                    activeRecipe = "com.example.Recipe",
                    recipeArtifacts = emptyList(),
                    rewriteConfig = null,
                    excludePaths = listOf("**/generated/**", "**/*.md", "src/test/**")
                )

            assertTrue(
                command.contains(
                    "-Drewrite.exclusions=**/generated/**,**/*.md,src/test/**"
                )
            )
        }

        test("buildCommand uses configured rewrite Maven plugin version") {
            val strategy =
                MavenPluginStrategy(
                    logger = NoOpRunnerLogger,
                    ToolConfigDefaults.PLUGIN_RUN_TIMEOUT,
                    ToolConfigDefaults.REWRITE_MAVEN_PLUGIN_VERSION
                )

            val command =
                strategy.buildCommand(
                    projectDir = projectDir,
                    goal = "dryRun",
                    activeRecipe = "com.example.Recipe",
                    recipeArtifacts = emptyList(),
                    rewriteConfig = null
                )

            assertTrue(
                command.contains(
                    "org.openrewrite.maven:rewrite-maven-plugin:" +
                        ToolConfigDefaults.REWRITE_MAVEN_PLUGIN_VERSION + ":dryRun"
                )
            )
        }

        test("run returns failed when dry run command exits non-zero") {
            val strategy =
                object : MavenPluginStrategy(
                    NoOpRunnerLogger,
                    ToolConfigDefaults.PLUGIN_RUN_TIMEOUT,
                    ToolConfigDefaults.REWRITE_MAVEN_PLUGIN_VERSION
                ) {
                    override fun execute(projectDir: Path, command: List<String>): Int = 1
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

            assertIs<PluginRunResult.Failed>(result)
        }

        test("run returns success without apply in dry-run mode") {
            val commands = mutableListOf<List<String>>()
            val strategy =
                object : MavenPluginStrategy(
                    NoOpRunnerLogger,
                    ToolConfigDefaults.PLUGIN_RUN_TIMEOUT,
                    ToolConfigDefaults.REWRITE_MAVEN_PLUGIN_VERSION
                ) {
                    override fun execute(projectDir: Path, command: List<String>): Int? {
                        commands.add(command)
                        projectDir.resolve("target/site/rewrite").createDirectories()
                        projectDir.resolve("target/site/rewrite/rewrite.patch").writeText(
                            """
                            diff --git a/pom.xml b/pom.xml
                            --- a/pom.xml
                            +++ b/pom.xml
                            @@ -1 +1 @@
                            -<project/>
                            +<project></project>
                            """.trimIndent()
                        )
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
                    dryRun = true,
                    includeMavenCentral = true,
                    artifactRepositories = emptyList()
                )

            assertIs<PluginRunResult.Success>(result)
            assertEquals(1, commands.size)
            assertEquals(emptyList(), result.changedFiles)
            assertEquals(setOf(Path.of("pom.xml")), result.diffs.keys)
        }

        test("run detects patch files emitted by Maven submodules") {
            projectDir.resolve("pom.xml").writeText(
                """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>root</artifactId>
                  <version>1.0.0</version>
                  <packaging>pom</packaging>
                  <modules>
                    <module>module-a</module>
                  </modules>
                </project>
                """.trimIndent()
            )
            projectDir.resolve("module-a").createDirectories()
            projectDir.resolve("module-a/pom.xml").writeText(
                """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>module-a</artifactId>
                  <version>1.0.0</version>
                </project>
                """.trimIndent()
            )

            val commands = mutableListOf<List<String>>()
            val strategy =
                object : MavenPluginStrategy(
                    NoOpRunnerLogger,
                    ToolConfigDefaults.PLUGIN_RUN_TIMEOUT,
                    ToolConfigDefaults.REWRITE_MAVEN_PLUGIN_VERSION
                ) {
                    override fun execute(projectDir: Path, command: List<String>): Int? {
                        commands.add(command)
                        if (command.any { it.endsWith(":dryRun") }) {
                            projectDir.resolve("module-a/target/site/rewrite").createDirectories()
                            val patchFile =
                                projectDir.resolve(
                                    "module-a/target/site/rewrite/rewrite.patch"
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
            assertEquals(2, commands.size)
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

        test("run ignores patch files outside declared Maven module roots") {
            projectDir.resolve("pom.xml").writeText(
                """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>root</artifactId>
                  <version>1.0.0</version>
                  <packaging>pom</packaging>
                  <modules>
                    <module>module-a</module>
                  </modules>
                </project>
                """.trimIndent()
            )
            projectDir.resolve("module-a").createDirectories()
            projectDir.resolve("module-a/pom.xml").writeText(
                """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>module-a</artifactId>
                  <version>1.0.0</version>
                </project>
                """.trimIndent()
            )

            val strategy =
                object : MavenPluginStrategy(
                    NoOpRunnerLogger,
                    ToolConfigDefaults.PLUGIN_RUN_TIMEOUT,
                    ToolConfigDefaults.REWRITE_MAVEN_PLUGIN_VERSION
                ) {
                    override fun execute(projectDir: Path, command: List<String>): Int? {
                        if (command.any { it.endsWith(":dryRun") }) {
                            projectDir.resolve("module-a/target/site/rewrite").createDirectories()
                            projectDir.resolve("module-a/target/site/rewrite/rewrite.patch")
                                .writeText(
                                    """
                                    diff --git a/src/main/java/A.java b/src/main/java/A.java
                                    --- a/src/main/java/A.java
                                    +++ b/src/main/java/A.java
                                    @@ -1 +1 @@
                                    -class A {}
                                    +class A { }
                                    """.trimIndent()
                                )

                            projectDir.resolve("untracked/deep/module-b/target/site/rewrite")
                                .createDirectories()
                            projectDir
                                .resolve(
                                    "untracked/deep/module-b/target/site/rewrite/rewrite.patch"
                                )
                                .writeText(
                                    """
                                    diff --git a/src/main/java/Rogue.java b/src/main/java/Rogue.java
                                    --- a/src/main/java/Rogue.java
                                    +++ b/src/main/java/Rogue.java
                                    @@ -1 +1 @@
                                    -class Rogue {}
                                    +class Rogue { }
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
            assertEquals(setOf(Path.of("module-a/src/main/java/A.java")), result.diffs.keys)
            assertEquals(
                listOf(projectDir.resolve("module-a/src/main/java/A.java")),
                result.changedFiles
            )
        }
    })
