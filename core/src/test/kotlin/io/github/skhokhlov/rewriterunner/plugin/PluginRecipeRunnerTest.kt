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
                artifactRepositories: List<RepositoryConfig>
            ): PluginRunResult = result
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
    })
