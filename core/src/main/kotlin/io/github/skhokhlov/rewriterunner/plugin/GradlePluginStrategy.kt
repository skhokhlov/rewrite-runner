package io.github.skhokhlov.rewriterunner.plugin

import io.github.skhokhlov.rewriterunner.RunnerLogger
import io.github.skhokhlov.rewriterunner.config.RepositoryConfig
import io.github.skhokhlov.rewriterunner.lst.utils.resolveGradleCommand
import io.github.skhokhlov.rewriterunner.lst.utils.runProcess
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

internal open class GradlePluginStrategy(
    private val logger: RunnerLogger,
    private val timeout: Duration,
    private val rewritePluginVersion: String
) : PluginBuildStrategy {
    override fun run(
        projectDir: Path,
        activeRecipe: String,
        recipeArtifacts: List<String>,
        rewriteConfig: Path?,
        rewriteConfigContent: String?,
        dryRun: Boolean,
        includeMavenCentral: Boolean,
        artifactRepositories: List<RepositoryConfig>
    ): PluginRunResult {
        val effectiveRewriteConfig =
            createRewriteConfigFile(rewriteConfigContent)
                ?: rewriteConfig
        val initScript =
            generateInitScript(
                activeRecipe = activeRecipe,
                recipeArtifacts = recipeArtifacts,
                rewriteConfig = effectiveRewriteConfig,
                includeMavenCentral = includeMavenCentral,
                repositories = artifactRepositories
            )
        return try {
            DirectPluginExecutor(projectDir, dryRun, ::execute).run(
                DirectPluginInvocation(
                    dryRunCommand = buildCommand(projectDir, "rewriteDryRun", initScript),
                    applyCommand = buildCommand(projectDir, "rewriteRun", initScript),
                    patchFiles = { findPatchFiles(projectDir) },
                    dryRunFailureMessage = { pluginFailureMessage("Gradle rewriteDryRun", it) },
                    applyFailureMessage = { pluginFailureMessage("Gradle rewriteRun", it) }
                )
            )
        } finally {
            Files.deleteIfExists(initScript)
            if (rewriteConfigContent != null && effectiveRewriteConfig != null) {
                Files.deleteIfExists(effectiveRewriteConfig)
            }
        }
    }

    fun generateInitScript(
        activeRecipe: String,
        recipeArtifacts: List<String>,
        rewriteConfig: Path?,
        includeMavenCentral: Boolean,
        repositories: List<RepositoryConfig>
    ): Path {
        val initScript = createPrivateTempFile("rewrite-runner-plugin-", ".gradle")
        val text =
            buildString {
                appendLine("initscript {")
                appendLine("    repositories {")
                appendRepositoryDeclarations(includeMavenCentral, repositories, "        ")
                appendLine("    }")
                appendLine("    dependencies {")
                appendLine(
                    "        classpath(\"org.openrewrite:plugin:" +
                        "$rewritePluginVersion\")"
                )
                appendLine("    }")
                appendLine("}")
                appendLine("gradle.beforeSettings { settings ->")
                appendLine("    settings.pluginManagement {")
                appendLine("        repositories {")
                appendRepositoryDeclarations(includeMavenCentral, repositories, "            ")
                appendLine("        }")
                appendLine("    }")
                appendLine("    try {")
                appendLine("        settings.dependencyResolutionManagement {")
                appendLine("            repositories {")
                appendRepositoryDeclarations(includeMavenCentral, repositories, "                ")
                appendLine("            }")
                appendLine("        }")
                appendLine("    } catch (MissingMethodException ignored) {")
                appendLine(
                    "        // Gradle versions before dependencyResolutionManagement still use project repositories."
                )
                appendLine("    }")
                appendLine("}")
                appendLine("rootProject {")
                appendLine("    apply plugin: org.openrewrite.gradle.RewritePlugin")
                appendLine("    rewrite {")
                appendLine("        activeRecipe(\"${activeRecipe.groovyString()}\")")
                rewriteConfig?.let {
                    appendLine(
                        "        configFile = file(\"${it.toAbsolutePath().toString().groovyString()}\")"
                    )
                }
                appendLine("    }")
                if (recipeArtifacts.isNotEmpty()) {
                    appendLine("    dependencies {")
                    recipeArtifacts.forEach {
                        appendLine("        add(\"rewrite\", \"${it.groovyString()}\")")
                    }
                    appendLine("    }")
                }
                appendLine("}")
                appendLine("allprojects {")
                appendLine("    repositories {")
                appendRepositoryDeclarations(includeMavenCentral, repositories, "        ")
                appendLine("    }")
                appendLine("}")
            }
        initScript.toFile().writeText(text, Charsets.UTF_8)
        return initScript
    }

    private fun StringBuilder.appendRepositoryDeclarations(
        includeMavenCentral: Boolean,
        repositories: List<RepositoryConfig>,
        indent: String
    ) {
        if (includeMavenCentral) {
            appendLine("${indent}mavenCentral()")
            appendLine("${indent}gradlePluginPortal()")
        }
        repositories.forEach { repo ->
            appendLine("${indent}maven {")
            appendLine("$indent    url = uri(\"${repo.url.groovyString()}\")")
            if (repo.username != null || repo.password != null) {
                appendLine("$indent    credentials {")
                repo.username?.let {
                    appendLine("$indent        username = \"${it.groovyString()}\"")
                }
                repo.password?.let {
                    appendLine("$indent        password = \"${it.groovyString()}\"")
                }
                appendLine("$indent    }")
            }
            appendLine("$indent}")
        }
    }

    open fun execute(projectDir: Path, command: List<String>): Int? = runProcess(
        workDir = projectDir,
        command = command,
        timeout = timeout,
        timeoutName = "pluginTimeout",
        logger = logger
    )

    private fun buildCommand(projectDir: Path, task: String, initScript: Path): List<String> =
        listOf(
            resolveGradleCommand(projectDir),
            task,
            "--init-script",
            initScript.toAbsolutePath().toString(),
            "--no-daemon",
            "--no-configuration-cache",
            "--no-parallel",
            "-S",
            "-i"
        )

    private fun findPatchFiles(projectDir: Path): List<DirectPluginPatchFile> = Files.find(
        projectDir,
        Int.MAX_VALUE,
        { path, attributes ->
            attributes.isRegularFile &&
                path.fileName.toString() == "rewrite.patch" &&
                path.endsWith("build/reports/rewrite/rewrite.patch")
        }
    ).use { paths ->
        paths.toList().sorted().map { path ->
            DirectPluginPatchFile(
                file = path,
                baseDir = path.parent.parent.parent.parent
            )
        }.toList()
    }

    private fun String.groovyString(): String =
        replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")
}
