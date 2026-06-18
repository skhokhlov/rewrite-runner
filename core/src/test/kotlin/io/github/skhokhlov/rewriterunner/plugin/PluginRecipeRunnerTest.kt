package io.github.skhokhlov.rewriterunner.plugin

import io.github.skhokhlov.rewriterunner.config.RepositoryConfig
import io.kotest.core.spec.style.FunSpec
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PluginRecipeRunnerTest :
    FunSpec({
        var projectDir: Path = Path.of("")

        beforeEach { projectDir = Files.createTempDirectory("prr-") }

        afterEach { projectDir.toFile().deleteRecursively() }

        fun strategy(result: PluginRunResult): PluginBuildStrategy = object : PluginBuildStrategy {
            override fun run(
                projectDir: Path,
                rootDir: Path,
                activeRecipe: String,
                recipeArtifacts: List<String>,
                rewriteConfig: Path?,
                rewriteConfigContent: String?,
                dryRun: Boolean,
                includeMavenCentral: Boolean,
                artifactRepositories: List<RepositoryConfig>,
                excludePaths: List<String>,
                plainTextMasks: List<String>
            ): PluginRunResult = result
        }

        /** Returns a per-directory result so multi-unit orphan dispatch can be asserted. */
        fun dispatchingStrategy(byDir: (Path) -> PluginRunResult): PluginBuildStrategy =
            object : PluginBuildStrategy {
                override fun run(
                    projectDir: Path,
                    rootDir: Path,
                    activeRecipe: String,
                    recipeArtifacts: List<String>,
                    rewriteConfig: Path?,
                    rewriteConfigContent: String?,
                    dryRun: Boolean,
                    includeMavenCentral: Boolean,
                    artifactRepositories: List<RepositoryConfig>,
                    excludePaths: List<String>,
                    plainTextMasks: List<String>
                ): PluginRunResult = byDir(projectDir)
            }

        /** Records the parameters each strategy invocation received. */
        class CapturingStrategy(private val result: PluginRunResult) : PluginBuildStrategy {
            var lastExcludePaths: List<String>? = null
                private set
            var lastPlainTextMasks: List<String>? = null
                private set
            var lastRewriteConfig: Path? = null
                private set
            var callCount: Int = 0
                private set

            override fun run(
                projectDir: Path,
                rootDir: Path,
                activeRecipe: String,
                recipeArtifacts: List<String>,
                rewriteConfig: Path?,
                rewriteConfigContent: String?,
                dryRun: Boolean,
                includeMavenCentral: Boolean,
                artifactRepositories: List<RepositoryConfig>,
                excludePaths: List<String>,
                plainTextMasks: List<String>
            ): PluginRunResult {
                callCount++
                lastExcludePaths = excludePaths
                lastPlainTextMasks = plainTextMasks
                lastRewriteConfig = rewriteConfig
                return result
            }
        }

        test("skips when no build tool is present") {
            val runner =
                PluginRecipeRunner(
                    gradleStrategy = strategy(PluginRunResult.NoChanges),
                    mavenStrategy = strategy(PluginRunResult.NoChanges)
                )

            val result =
                runner.run(
                    projectDir = projectDir,
                    activeRecipe = "com.example.Recipe",
                    recipeArtifacts = emptyList(),
                    rewriteConfig = null,
                    rewriteConfigContent = null,
                    dryRun = true,
                    includeMavenCentral = true,
                    artifactRepositories = emptyList()
                )

            assertIs<PluginRunResult.Skipped>(result)
        }

        test("tries maven when gradle fails") {
            projectDir.resolve("build.gradle.kts").writeText("")
            projectDir.resolve("pom.xml").writeText("<project/>")

            val result =
                PluginRecipeRunner(
                    gradleStrategy = strategy(PluginRunResult.Failed("gradle failed")),
                    mavenStrategy = strategy(PluginRunResult.NoChanges)
                ).run(
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

        test("returns combined failures when every detected tool fails") {
            projectDir.resolve("build.gradle.kts").writeText("")
            projectDir.resolve("pom.xml").writeText("<project/>")

            val result =
                PluginRecipeRunner(
                    gradleStrategy = strategy(PluginRunResult.Failed("gradle failed")),
                    mavenStrategy = strategy(PluginRunResult.Failed("maven failed"))
                ).run(
                    projectDir = projectDir,
                    activeRecipe = "com.example.Recipe",
                    recipeArtifacts = emptyList(),
                    rewriteConfig = null,
                    rewriteConfigContent = null,
                    dryRun = true,
                    includeMavenCentral = true,
                    artifactRepositories = emptyList()
                )

            assertEquals(PluginRunResult.Failed("gradle failed ; maven failed"), result)
        }

        test("forwards excludePaths to the Gradle strategy") {
            projectDir.resolve("build.gradle.kts").writeText("")

            val gradle = CapturingStrategy(PluginRunResult.NoChanges)
            val maven = CapturingStrategy(PluginRunResult.NoChanges)

            PluginRecipeRunner(gradleStrategy = gradle, mavenStrategy = maven).run(
                projectDir = projectDir,
                activeRecipe = "com.example.Recipe",
                recipeArtifacts = emptyList(),
                rewriteConfig = null,
                rewriteConfigContent = null,
                dryRun = true,
                includeMavenCentral = true,
                artifactRepositories = emptyList(),
                excludePaths = listOf("**/generated/**", "**/*.md")
            )

            assertEquals(1, gradle.callCount)
            assertEquals(listOf("**/generated/**", "**/*.md"), gradle.lastExcludePaths)
        }

        test("forwards plainTextMasks to the Gradle strategy") {
            projectDir.resolve("build.gradle.kts").writeText("")

            val gradle = CapturingStrategy(PluginRunResult.NoChanges)
            val maven = CapturingStrategy(PluginRunResult.NoChanges)

            PluginRecipeRunner(gradleStrategy = gradle, mavenStrategy = maven).run(
                projectDir = projectDir,
                activeRecipe = "com.example.Recipe",
                recipeArtifacts = emptyList(),
                rewriteConfig = null,
                rewriteConfigContent = null,
                dryRun = true,
                includeMavenCentral = true,
                artifactRepositories = emptyList(),
                plainTextMasks = listOf("**/CODEOWNERS", "**/*.txt")
            )

            assertEquals(1, gradle.callCount)
            assertEquals(listOf("**/CODEOWNERS", "**/*.txt"), gradle.lastPlainTextMasks)
        }

        test("forwards excludePaths to the Maven strategy when Gradle fails") {
            projectDir.resolve("build.gradle.kts").writeText("")
            projectDir.resolve("pom.xml").writeText("<project/>")

            val gradle = CapturingStrategy(PluginRunResult.Failed("gradle failed"))
            val maven = CapturingStrategy(PluginRunResult.NoChanges)

            PluginRecipeRunner(gradleStrategy = gradle, mavenStrategy = maven).run(
                projectDir = projectDir,
                activeRecipe = "com.example.Recipe",
                recipeArtifacts = emptyList(),
                rewriteConfig = null,
                rewriteConfigContent = null,
                dryRun = true,
                includeMavenCentral = true,
                artifactRepositories = emptyList(),
                excludePaths = listOf("src/test/**")
            )

            assertEquals(1, gradle.callCount)
            assertEquals(listOf("src/test/**"), gradle.lastExcludePaths)
            assertEquals(1, maven.callCount)
            assertEquals(listOf("src/test/**"), maven.lastExcludePaths)
        }

        test("forwards plainTextMasks to the Maven strategy when Gradle fails") {
            projectDir.resolve("build.gradle.kts").writeText("")
            projectDir.resolve("pom.xml").writeText("<project/>")

            val gradle = CapturingStrategy(PluginRunResult.Failed("gradle failed"))
            val maven = CapturingStrategy(PluginRunResult.NoChanges)

            PluginRecipeRunner(gradleStrategy = gradle, mavenStrategy = maven).run(
                projectDir = projectDir,
                activeRecipe = "com.example.Recipe",
                recipeArtifacts = emptyList(),
                rewriteConfig = null,
                rewriteConfigContent = null,
                dryRun = true,
                includeMavenCentral = true,
                artifactRepositories = emptyList(),
                plainTextMasks = listOf("**/CODEOWNERS")
            )

            assertEquals(1, gradle.callCount)
            assertEquals(listOf("**/CODEOWNERS"), gradle.lastPlainTextMasks)
            assertEquals(1, maven.callCount)
            assertEquals(listOf("**/CODEOWNERS"), maven.lastPlainTextMasks)
        }

        fun successWith(path: String): PluginRunResult = PluginRunResult.Success(
            changedFiles = listOf(Path.of(path)),
            diffs = mapOf(Path.of(path) to "diff for $path"),
            estimatedTimeSaved = null
        )

        test("runs the plugin in each orphan unit when the root has no build file") {
            Files.createDirectories(projectDir.resolve("svc-a"))
            Files.createDirectories(projectDir.resolve("svc-b"))
            projectDir.resolve("svc-a/build.gradle.kts").writeText("")
            projectDir.resolve("svc-b/pom.xml").writeText("<project/>")

            val invokedDirs = mutableListOf<Path>()
            val gradle = dispatchingStrategy { dir ->
                invokedDirs.add(dir)
                successWith("svc-a/Foo.java")
            }
            val maven = dispatchingStrategy { dir ->
                invokedDirs.add(dir)
                successWith("svc-b/Bar.java")
            }

            val result =
                PluginRecipeRunner(gradleStrategy = gradle, mavenStrategy = maven).run(
                    projectDir = projectDir,
                    activeRecipe = "com.example.Recipe",
                    recipeArtifacts = emptyList(),
                    rewriteConfig = null,
                    rewriteConfigContent = null,
                    dryRun = true,
                    includeMavenCentral = true,
                    artifactRepositories = emptyList()
                )

            val success = assertIs<PluginRunResult.Success>(result)
            assertEquals(
                setOf(Path.of("svc-a/Foo.java"), Path.of("svc-b/Bar.java")),
                success.diffs.keys
            )
            assertEquals(
                setOf(projectDir.resolve("svc-a"), projectDir.resolve("svc-b")),
                invokedDirs.toSet()
            )
        }

        test("returns Partial when one orphan unit fails and another succeeds") {
            Files.createDirectories(projectDir.resolve("svc-a"))
            Files.createDirectories(projectDir.resolve("svc-b"))
            projectDir.resolve("svc-a/build.gradle.kts").writeText("")
            projectDir.resolve("svc-b/pom.xml").writeText("<project/>")

            val gradle = dispatchingStrategy { successWith("svc-a/Foo.java") }
            val maven = dispatchingStrategy { PluginRunResult.Failed("maven blew up") }

            val result =
                PluginRecipeRunner(gradleStrategy = gradle, mavenStrategy = maven).run(
                    projectDir = projectDir,
                    activeRecipe = "com.example.Recipe",
                    recipeArtifacts = emptyList(),
                    rewriteConfig = null,
                    rewriteConfigContent = null,
                    dryRun = true,
                    includeMavenCentral = true,
                    artifactRepositories = emptyList()
                )

            val partial = assertIs<PluginRunResult.Partial>(result)
            assertEquals(setOf(Path.of("svc-a/Foo.java")), partial.diffs.keys)
            assertEquals(1, partial.failures.size)
            assertTrue(partial.failures.single().contains("maven blew up"))
        }

        test("aggregates NoChanges across orphan units as NoChanges") {
            Files.createDirectories(projectDir.resolve("svc-a"))
            Files.createDirectories(projectDir.resolve("svc-b"))
            projectDir.resolve("svc-a/build.gradle.kts").writeText("")
            projectDir.resolve("svc-b/build.gradle.kts").writeText("")

            val gradle = dispatchingStrategy { PluginRunResult.NoChanges }
            val maven = dispatchingStrategy { PluginRunResult.NoChanges }

            val result =
                PluginRecipeRunner(gradleStrategy = gradle, mavenStrategy = maven).run(
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

        test("does not discover subdirs when the root has a build file") {
            projectDir.resolve("build.gradle.kts").writeText("")
            Files.createDirectories(projectDir.resolve("svc-a"))
            projectDir.resolve("svc-a/pom.xml").writeText("<project/>")

            val invokedDirs = mutableListOf<Path>()
            val gradle = dispatchingStrategy { dir ->
                invokedDirs.add(dir)
                PluginRunResult.NoChanges
            }
            val maven = dispatchingStrategy { dir ->
                invokedDirs.add(dir)
                PluginRunResult.NoChanges
            }

            PluginRecipeRunner(gradleStrategy = gradle, mavenStrategy = maven).run(
                projectDir = projectDir,
                activeRecipe = "com.example.Recipe",
                recipeArtifacts = emptyList(),
                rewriteConfig = null,
                rewriteConfigContent = null,
                dryRun = true,
                includeMavenCentral = true,
                artifactRepositories = emptyList()
            )

            assertEquals(listOf(projectDir), invokedDirs)
        }

        test("resolves the implicit root rewrite.yaml for orphan units") {
            Files.createDirectories(projectDir.resolve("svc-a"))
            projectDir.resolve("svc-a/build.gradle.kts").writeText("")
            val rootConfig = projectDir.resolve("rewrite.yaml")
            rootConfig.writeText("type: specs.openrewrite.org/v1beta/recipe\n")

            val gradle = CapturingStrategy(PluginRunResult.NoChanges)
            val maven = CapturingStrategy(PluginRunResult.NoChanges)

            PluginRecipeRunner(gradleStrategy = gradle, mavenStrategy = maven).run(
                projectDir = projectDir,
                activeRecipe = "com.example.Recipe",
                recipeArtifacts = emptyList(),
                rewriteConfig = null,
                rewriteConfigContent = null,
                dryRun = true,
                includeMavenCentral = true,
                artifactRepositories = emptyList()
            )

            // The plugin runs from svc-a, so the repository-root rewrite.yaml must be passed
            // explicitly — otherwise the recipe defined there is never loaded.
            assertEquals(1, gradle.callCount)
            assertEquals(rootConfig, gradle.lastRewriteConfig)
        }

        test("does not override an explicit rewriteConfig for orphan units") {
            Files.createDirectories(projectDir.resolve("svc-a"))
            projectDir.resolve("svc-a/build.gradle.kts").writeText("")
            projectDir.resolve("rewrite.yaml").writeText("# implicit root config\n")
            val explicit = projectDir.resolve("custom.yaml")
            explicit.writeText("type: specs.openrewrite.org/v1beta/recipe\n")

            val gradle = CapturingStrategy(PluginRunResult.NoChanges)
            val maven = CapturingStrategy(PluginRunResult.NoChanges)

            PluginRecipeRunner(gradleStrategy = gradle, mavenStrategy = maven).run(
                projectDir = projectDir,
                activeRecipe = "com.example.Recipe",
                recipeArtifacts = emptyList(),
                rewriteConfig = explicit,
                rewriteConfigContent = null,
                dryRun = true,
                includeMavenCentral = true,
                artifactRepositories = emptyList()
            )

            assertEquals(explicit, gradle.lastRewriteConfig)
        }

        test("rebases unit-anchored exclude and plain-text globs for orphan units") {
            Files.createDirectories(projectDir.resolve("svc-a"))
            projectDir.resolve("svc-a/build.gradle.kts").writeText("")

            val gradle = CapturingStrategy(PluginRunResult.NoChanges)
            val maven = CapturingStrategy(PluginRunResult.NoChanges)

            PluginRecipeRunner(gradleStrategy = gradle, mavenStrategy = maven).run(
                projectDir = projectDir,
                activeRecipe = "com.example.Recipe",
                recipeArtifacts = emptyList(),
                rewriteConfig = null,
                rewriteConfigContent = null,
                dryRun = true,
                includeMavenCentral = true,
                artifactRepositories = emptyList(),
                excludePaths = listOf("svc-a/generated/**", "**/*.md"),
                plainTextMasks = listOf("svc-a/notes/*.txt", "**/CODEOWNERS")
            )

            // Unit-anchored patterns lose the unit prefix so they match against the paths the
            // plugin sees from inside svc-a; globs that match anywhere are left untouched.
            assertEquals(listOf("generated/**", "**/*.md"), gradle.lastExcludePaths)
            assertEquals(listOf("notes/*.txt", "**/CODEOWNERS"), gradle.lastPlainTextMasks)
        }
    })
