package io.github.skhokhlov.rewriterunner.plugin

import io.github.skhokhlov.rewriterunner.config.RepositoryConfig
import io.kotest.core.spec.style.FunSpec
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PluginRecipeRunnerTest :
    FunSpec({
        var projectDir: Path = Path.of("")

        beforeEach { projectDir = Files.createTempDirectory("prr-") }

        afterEach { projectDir.toFile().deleteRecursively() }

        fun strategy(result: PluginRunResult): PluginBuildStrategy = object : PluginBuildStrategy {
            override fun run(
                projectDir: Path,
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

        /** Records the parameters each strategy invocation received. */
        class CapturingStrategy(private val result: PluginRunResult) : PluginBuildStrategy {
            var lastExcludePaths: List<String>? = null
                private set
            var lastPlainTextMasks: List<String>? = null
                private set
            var callCount: Int = 0
                private set

            override fun run(
                projectDir: Path,
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
    })
