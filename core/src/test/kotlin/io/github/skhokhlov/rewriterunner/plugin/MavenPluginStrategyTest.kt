package io.github.skhokhlov.rewriterunner.plugin

import io.github.skhokhlov.rewriterunner.NoOpRunnerLogger
import io.github.skhokhlov.rewriterunner.config.ToolConfigDefaults
import io.kotest.core.spec.style.FunSpec
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// These must match the documented user-property names on the rewrite-maven-plugin mojos
// (https://openrewrite.github.io/rewrite-maven-plugin/dryRun-mojo.html). They are pinned as
// literal strings here — *not* shared with production code — so a typo in the production
// flag fails this test rather than silently being ignored by Maven and skipping rewrite:run.
private const val REPORT_OUTPUT_FLAG = "-DreportOutputDirectory="
private const val RUN_PER_SUBMODULE_FLAG = "-Drewrite.runPerSubmodule=false"
private const val EXPORT_DATATABLES_FLAG = "-Drewrite.exportDatatables=true"

private fun extractReportDir(command: List<String>): Path? =
    command.firstOrNull { it.startsWith(REPORT_OUTPUT_FLAG) }
        ?.removePrefix(REPORT_OUTPUT_FLAG)
        ?.let(Path::of)

private fun writeMavenDataTable(reportDir: Path, timestamp: String, seconds: Long) {
    val dataTableDir = reportDir.resolve("datatables/$timestamp").createDirectories()
    dataTableDir.resolve("org.openrewrite.table.SourcesFileResults.csv").writeText(
        """
        sourcePath,estimatedTimeSaving
        pom.xml,$seconds
        """.trimIndent()
    )
}

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
            val reportDir = Files.createTempDirectory("report-")
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
                    rewriteConfig = config,
                    reportOutputDirectory = reportDir
                )

            assertEquals("mvn", command.first())
            assertTrue(
                command.any {
                    it.contains("rewrite-maven-plugin") && it.endsWith(":dryRun")
                }
            )
            assertTrue(command.contains("-Drewrite.activeRecipes=com.example.Recipe"))
            assertTrue(command.contains(EXPORT_DATATABLES_FLAG))
            assertTrue(
                command.contains(
                    "-Drewrite.recipeArtifactCoordinates=" +
                        "com.example:recipes:1.0.0,com.example:more:2.0.0"
                )
            )
            assertTrue(command.contains("-Drewrite.configLocation=${config.toAbsolutePath()}"))
        }

        test("buildCommand emits -Drewrite.exclusions when excludePaths non-empty") {
            val reportDir = Files.createTempDirectory("report-")
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
                    reportOutputDirectory = reportDir,
                    excludePaths = listOf("src/test/**")
                )

            assertTrue(command.contains("-Drewrite.exclusions=src/test/**"))
        }

        test("buildCommand emits -Drewrite.plainTextMasks when plainTextMasks non-empty") {
            val reportDir = Files.createTempDirectory("report-")
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
                    reportOutputDirectory = reportDir,
                    plainTextMasks = listOf("**/CODEOWNERS", "**/*.txt")
                )

            assertTrue(command.contains("-Drewrite.plainTextMasks=**/CODEOWNERS,**/*.txt"))
        }

        test("buildCommand omits -Drewrite.exclusions when excludePaths empty") {
            val reportDir = Files.createTempDirectory("report-")
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
                    reportOutputDirectory = reportDir,
                    excludePaths = emptyList()
                )

            assertTrue(command.none { it.startsWith("-Drewrite.exclusions") })
        }

        test("buildCommand omits -Drewrite.plainTextMasks when plainTextMasks empty") {
            val reportDir = Files.createTempDirectory("report-")
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
                    reportOutputDirectory = reportDir,
                    plainTextMasks = emptyList()
                )

            assertTrue(command.none { it.startsWith("-Drewrite.plainTextMasks") })
        }

        test("buildCommand joins multiple globs with comma") {
            val reportDir = Files.createTempDirectory("report-")
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
                    reportOutputDirectory = reportDir,
                    excludePaths = listOf("**/generated/**", "**/*.md", "src/test/**")
                )

            assertTrue(
                command.contains(
                    "-Drewrite.exclusions=**/generated/**,**/*.md,src/test/**"
                )
            )
        }

        test("buildCommand uses configured rewrite Maven plugin version") {
            val reportDir = Files.createTempDirectory("report-")
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
                    rewriteConfig = null,
                    reportOutputDirectory = reportDir
                )

            assertTrue(
                command.contains(
                    "org.openrewrite.maven:rewrite-maven-plugin:" +
                        ToolConfigDefaults.REWRITE_MAVEN_PLUGIN_VERSION + ":dryRun"
                )
            )
        }

        test("buildCommand pins reportOutputDirectory to an absolute path") {
            val reportDir = Files.createTempDirectory("report-")
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
                    reportOutputDirectory = reportDir
                )

            val flag = command.firstOrNull { it.startsWith(REPORT_OUTPUT_FLAG) }
            assertNotNull(flag, "expected $REPORT_OUTPUT_FLAG in $command")
            val path = Path.of(flag.removePrefix(REPORT_OUTPUT_FLAG))
            assertTrue(path.isAbsolute, "expected absolute path, got $path")
            assertEquals(reportDir.toAbsolutePath(), path)
        }

        test("buildCommand pins runPerSubmodule=false to defend against user overrides") {
            val reportDir = Files.createTempDirectory("report-")
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
                    reportOutputDirectory = reportDir
                )

            assertTrue(
                command.contains(RUN_PER_SUBMODULE_FLAG),
                "expected $RUN_PER_SUBMODULE_FLAG in $command"
            )
        }

        test("buildCommand enables data table export") {
            val reportDir = Files.createTempDirectory("report-")
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
                    reportOutputDirectory = reportDir
                )

            assertTrue(
                command.contains(EXPORT_DATATABLES_FLAG),
                "expected $EXPORT_DATATABLES_FLAG in $command"
            )
        }

        test("buildEnv returns empty map when no plugin JVM args configured") {
            val strategy =
                MavenPluginStrategy(
                    NoOpRunnerLogger,
                    ToolConfigDefaults.PLUGIN_RUN_TIMEOUT,
                    ToolConfigDefaults.REWRITE_MAVEN_PLUGIN_VERSION
                )

            assertTrue(strategy.buildEnv(existingMavenOpts = "-Xms1g").isEmpty())
        }

        test("buildEnv appends plugin JVM args after existing MAVEN_OPTS so ours wins") {
            val strategy =
                MavenPluginStrategy(
                    NoOpRunnerLogger,
                    ToolConfigDefaults.PLUGIN_RUN_TIMEOUT,
                    ToolConfigDefaults.REWRITE_MAVEN_PLUGIN_VERSION,
                    pluginJvmArgs = listOf("-Xmx2g", "-XX:+UseG1GC")
                )

            assertEquals(
                mapOf("MAVEN_OPTS" to "-Xms1g -Xmx1g -Xmx2g -XX:+UseG1GC"),
                strategy.buildEnv(existingMavenOpts = "-Xms1g -Xmx1g")
            )
        }

        test("buildEnv uses plugin JVM args alone when MAVEN_OPTS unset") {
            val strategy =
                MavenPluginStrategy(
                    NoOpRunnerLogger,
                    ToolConfigDefaults.PLUGIN_RUN_TIMEOUT,
                    ToolConfigDefaults.REWRITE_MAVEN_PLUGIN_VERSION,
                    pluginJvmArgs = listOf("-Xmx2g")
                )

            assertEquals(
                mapOf("MAVEN_OPTS" to "-Xmx2g"),
                strategy.buildEnv(existingMavenOpts = null)
            )
            assertEquals(
                mapOf("MAVEN_OPTS" to "-Xmx2g"),
                strategy.buildEnv(existingMavenOpts = "   ")
            )
        }

        test("run returns failed when dry run command exits non-zero") {
            val strategy =
                object : MavenPluginStrategy(
                    NoOpRunnerLogger,
                    ToolConfigDefaults.PLUGIN_RUN_TIMEOUT,
                    ToolConfigDefaults.REWRITE_MAVEN_PLUGIN_VERSION
                ) {
                    override fun execute(
                        projectDir: Path,
                        command: List<String>,
                        output: StringBuilder?
                    ): Int? = 1
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

        test("run picks up patch written to the configured reportOutputDirectory") {
            val commands = mutableListOf<List<String>>()
            val strategy =
                object : MavenPluginStrategy(
                    NoOpRunnerLogger,
                    ToolConfigDefaults.PLUGIN_RUN_TIMEOUT,
                    ToolConfigDefaults.REWRITE_MAVEN_PLUGIN_VERSION
                ) {
                    override fun execute(
                        projectDir: Path,
                        command: List<String>,
                        output: StringBuilder?
                    ): Int? {
                        commands.add(command)
                        val reportDir = extractReportDir(command)!!
                        reportDir.resolve("rewrite.patch").writeText(
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

        test("run reads estimated time saved from exported data tables") {
            val strategy =
                object : MavenPluginStrategy(
                    NoOpRunnerLogger,
                    ToolConfigDefaults.PLUGIN_RUN_TIMEOUT,
                    ToolConfigDefaults.REWRITE_MAVEN_PLUGIN_VERSION
                ) {
                    override fun execute(
                        projectDir: Path,
                        command: List<String>,
                        output: StringBuilder?
                    ): Int? {
                        val reportDir = extractReportDir(command)!!
                        reportDir.resolve("rewrite.patch").writeText(
                            """
                            diff --git a/pom.xml b/pom.xml
                            --- a/pom.xml
                            +++ b/pom.xml
                            @@ -1 +1 @@
                            -<project/>
                            +<project></project>
                            """.trimIndent()
                        )
                        writeMavenDataTable(reportDir, "2024-01-01T00-00-00Z", 75)
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
            assertEquals(Duration.ofSeconds(75), result.estimatedTimeSaved)
        }

        test("run reads Maven plugin data tables from target rewrite directory") {
            val strategy =
                object : MavenPluginStrategy(
                    NoOpRunnerLogger,
                    ToolConfigDefaults.PLUGIN_RUN_TIMEOUT,
                    ToolConfigDefaults.REWRITE_MAVEN_PLUGIN_VERSION
                ) {
                    override fun execute(
                        projectDir: Path,
                        command: List<String>,
                        output: StringBuilder?
                    ): Int? {
                        val reportDir = extractReportDir(command)!!
                        reportDir.resolve("rewrite.patch").writeText(
                            """
                            diff --git a/pom.xml b/pom.xml
                            --- a/pom.xml
                            +++ b/pom.xml
                            @@ -1 +1 @@
                            -<project/>
                            +<project></project>
                            """.trimIndent()
                        )
                        writeMavenDataTable(
                            projectDir.resolve("target/rewrite"),
                            "2024-01-01T00-00-00Z",
                            95
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
            assertEquals(Duration.ofSeconds(95), result.estimatedTimeSaved)
        }

        test("run falls back to Maven plugin estimate output when data table is absent") {
            val strategy =
                object : MavenPluginStrategy(
                    NoOpRunnerLogger,
                    ToolConfigDefaults.PLUGIN_RUN_TIMEOUT,
                    ToolConfigDefaults.REWRITE_MAVEN_PLUGIN_VERSION
                ) {
                    override fun execute(
                        projectDir: Path,
                        command: List<String>,
                        output: StringBuilder?
                    ): Int? {
                        val reportDir = extractReportDir(command)!!
                        reportDir.resolve("rewrite.patch").writeText(
                            """
                            diff --git a/pom.xml b/pom.xml
                            --- a/pom.xml
                            +++ b/pom.xml
                            @@ -1 +1 @@
                            -<project/>
                            +<project></project>
                            """.trimIndent()
                        )
                        output?.append("[WARNING] Estimate time saved: 5m\n")
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
            assertEquals(Duration.ofMinutes(5), result.estimatedTimeSaved)
        }

        test("run aggregates diffs across submodules from a single patch file") {
            // Multi-module: parent declares modules, but the plugin (with runPerSubmodule=false)
            // emits one aggregated patch at the configured reportOutputDirectory.
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
                    <module>module-b</module>
                  </modules>
                </project>
                """.trimIndent()
            )

            val strategy =
                object : MavenPluginStrategy(
                    NoOpRunnerLogger,
                    ToolConfigDefaults.PLUGIN_RUN_TIMEOUT,
                    ToolConfigDefaults.REWRITE_MAVEN_PLUGIN_VERSION
                ) {
                    override fun execute(
                        projectDir: Path,
                        command: List<String>,
                        output: StringBuilder?
                    ): Int? {
                        if (command.any { it.endsWith(":dryRun") }) {
                            val reportDir = extractReportDir(command)!!
                            reportDir.resolve("rewrite.patch").writeText(
                                """
                                diff --git a/module-a/src/main/java/A.java b/module-a/src/main/java/A.java
                                --- a/module-a/src/main/java/A.java
                                +++ b/module-a/src/main/java/A.java
                                @@ -1 +1 @@
                                -class A {}
                                +class A { }
                                diff --git a/module-b/src/main/java/B.java b/module-b/src/main/java/B.java
                                --- a/module-b/src/main/java/B.java
                                +++ b/module-b/src/main/java/B.java
                                @@ -1 +1 @@
                                -class B {}
                                +class B { }
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
            assertEquals(
                setOf(
                    Path.of("module-a/src/main/java/A.java"),
                    Path.of("module-b/src/main/java/B.java")
                ),
                result.diffs.keys
            )
        }

        test("run surfaces submodule pom.xml diffs in the aggregated patch") {
            // Pins that aggregated mode (runPerSubmodule=false) covers dependency-style recipe
            // changes to submodule POMs, not just sources.
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
                    <module>module-b</module>
                  </modules>
                </project>
                """.trimIndent()
            )

            val strategy =
                object : MavenPluginStrategy(
                    NoOpRunnerLogger,
                    ToolConfigDefaults.PLUGIN_RUN_TIMEOUT,
                    ToolConfigDefaults.REWRITE_MAVEN_PLUGIN_VERSION
                ) {
                    override fun execute(
                        projectDir: Path,
                        command: List<String>,
                        output: StringBuilder?
                    ): Int? {
                        if (command.any { it.endsWith(":dryRun") }) {
                            val reportDir = extractReportDir(command)!!
                            reportDir.resolve("rewrite.patch").writeText(
                                """
                                diff --git a/module-a/pom.xml b/module-a/pom.xml
                                --- a/module-a/pom.xml
                                +++ b/module-a/pom.xml
                                @@ -1 +1 @@
                                -<project/>
                                +<project></project>
                                diff --git a/module-b/pom.xml b/module-b/pom.xml
                                --- a/module-b/pom.xml
                                +++ b/module-b/pom.xml
                                @@ -1 +1 @@
                                -<project/>
                                +<project></project>
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
            assertEquals(
                setOf(Path.of("module-a/pom.xml"), Path.of("module-b/pom.xml")),
                result.diffs.keys
            )
        }

        test("run returns NoChanges when the report directory contains no patch") {
            val strategy =
                object : MavenPluginStrategy(
                    NoOpRunnerLogger,
                    ToolConfigDefaults.PLUGIN_RUN_TIMEOUT,
                    ToolConfigDefaults.REWRITE_MAVEN_PLUGIN_VERSION
                ) {
                    override fun execute(
                        projectDir: Path,
                        command: List<String>,
                        output: StringBuilder?
                    ): Int? = 0
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

            assertIs<PluginRunResult.NoChanges>(result)
        }

        test("run deletes the temp report directory after success") {
            val capturedReportDirs = mutableListOf<Path>()
            val strategy =
                object : MavenPluginStrategy(
                    NoOpRunnerLogger,
                    ToolConfigDefaults.PLUGIN_RUN_TIMEOUT,
                    ToolConfigDefaults.REWRITE_MAVEN_PLUGIN_VERSION
                ) {
                    override fun execute(
                        projectDir: Path,
                        command: List<String>,
                        output: StringBuilder?
                    ): Int? {
                        extractReportDir(command)?.let(capturedReportDirs::add)
                        return 0
                    }
                }

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

            assertTrue(capturedReportDirs.isNotEmpty(), "expected at least one report dir captured")
            capturedReportDirs.forEach { dir ->
                assertTrue(!dir.exists(), "expected temp report dir $dir to be cleaned up")
            }
        }

        test("run deletes the temp report directory after failure") {
            val capturedReportDirs = mutableListOf<Path>()
            val strategy =
                object : MavenPluginStrategy(
                    NoOpRunnerLogger,
                    ToolConfigDefaults.PLUGIN_RUN_TIMEOUT,
                    ToolConfigDefaults.REWRITE_MAVEN_PLUGIN_VERSION
                ) {
                    override fun execute(
                        projectDir: Path,
                        command: List<String>,
                        output: StringBuilder?
                    ): Int? {
                        extractReportDir(command)?.let(capturedReportDirs::add)
                        return 1
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

            assertIs<PluginRunResult.Failed>(result)
            assertTrue(capturedReportDirs.isNotEmpty(), "expected at least one report dir captured")
            capturedReportDirs.forEach { dir ->
                assertTrue(!dir.exists(), "expected temp report dir $dir to be cleaned up")
            }
        }
    })
