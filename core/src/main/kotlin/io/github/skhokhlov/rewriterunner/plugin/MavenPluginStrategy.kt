package io.github.skhokhlov.rewriterunner.plugin

import io.github.skhokhlov.rewriterunner.RunnerLogger
import io.github.skhokhlov.rewriterunner.config.RepositoryConfig
import io.github.skhokhlov.rewriterunner.lst.utils.resolveMavenCommand
import io.github.skhokhlov.rewriterunner.lst.utils.runProcess
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.exists
import kotlin.streams.toList
import org.apache.maven.model.io.xpp3.MavenXpp3Reader

private const val FALLBACK_POM_DISCOVERY_DEPTH = 6
private val MAVEN_PATCH_PATH: Path = Path.of("target/site/rewrite/rewrite.patch")

internal open class MavenPluginStrategy(
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
        repositories: List<RepositoryConfig>
    ): PluginRunResult {
        val effectiveRewriteConfig =
            createRewriteConfigFile(rewriteConfigContent)
                ?: rewriteConfig
        return try {
            DirectPluginExecutor(projectDir, dryRun, ::execute).run(
                DirectPluginInvocation(
                    dryRunCommand = buildCommand(
                        projectDir = projectDir,
                        goal = "dryRun",
                        activeRecipe = activeRecipe,
                        recipeArtifacts = recipeArtifacts,
                        rewriteConfig = effectiveRewriteConfig
                    ),
                    applyCommand = buildCommand(
                        projectDir = projectDir,
                        goal = "run",
                        activeRecipe = activeRecipe,
                        recipeArtifacts = recipeArtifacts,
                        rewriteConfig = effectiveRewriteConfig
                    ),
                    patchFiles = { findPatchFiles(projectDir) },
                    dryRunFailureMessage = { pluginFailureMessage("Maven rewrite:dryRun", it) },
                    applyFailureMessage = { pluginFailureMessage("Maven rewrite:run", it) }
                )
            )
        } finally {
            if (rewriteConfigContent != null && effectiveRewriteConfig != null) {
                Files.deleteIfExists(effectiveRewriteConfig)
            }
        }
    }

    fun buildCommand(
        projectDir: Path,
        goal: String,
        activeRecipe: String,
        recipeArtifacts: List<String>,
        rewriteConfig: Path?
    ): List<String> = buildList {
        add(resolveMavenCommand(projectDir))
        add("-U")
        add("--no-transfer-progress")
        add("--batch-mode")
        add(
            "org.openrewrite.maven:rewrite-maven-plugin:" +
                "$rewritePluginVersion:$goal"
        )
        add("-Drewrite.activeRecipes=$activeRecipe")
        if (recipeArtifacts.isNotEmpty()) {
            add("-Drewrite.recipeArtifactCoordinates=${recipeArtifacts.joinToString(",")}")
        }
        rewriteConfig?.let {
            add("-Drewrite.configLocation=${it.toAbsolutePath()}")
        }
    }

    open fun execute(projectDir: Path, command: List<String>): Int? = runProcess(
        workDir = projectDir,
        command = command,
        timeout = timeout,
        timeoutName = "pluginTimeout",
        logger = logger
    )

    private fun findPatchFiles(projectDir: Path): List<DirectPluginPatchFile> =
        discoverMavenRoots(projectDir).mapNotNull { moduleDir ->
            val patchFile = moduleDir.resolve(MAVEN_PATCH_PATH)
            if (!patchFile.exists()) return@mapNotNull null

            DirectPluginPatchFile(
                file = patchFile,
                baseDir = moduleDir
            )
        }.sortedBy { it.file }

    private fun discoverMavenRoots(projectDir: Path): List<Path> {
        val projectRoot = projectDir.toAbsolutePath().normalize()
        return if (projectRoot.resolve("pom.xml").exists()) {
            val roots = linkedSetOf<Path>()
            collectDeclaredMavenRoots(
                projectRoot = projectRoot,
                moduleDir = projectRoot,
                found = roots
            )
            roots.toList()
        } else {
            discoverFallbackPomRoots(projectRoot)
        }
    }

    private fun collectDeclaredMavenRoots(
        projectRoot: Path,
        moduleDir: Path,
        found: MutableSet<Path>
    ) {
        val normalizedModule = moduleDir.toAbsolutePath().normalize()
        if (!normalizedModule.startsWith(projectRoot)) return
        if (!normalizedModule.resolve("pom.xml").exists()) return
        if (!found.add(normalizedModule)) return

        for (module in readDeclaredModules(normalizedModule)) {
            if (module.isBlank()) continue
            collectDeclaredMavenRoots(
                projectRoot = projectRoot,
                moduleDir = normalizedModule.resolve(module),
                found = found
            )
        }
    }

    private fun readDeclaredModules(moduleDir: Path): List<String> = try {
        Files.newInputStream(moduleDir.resolve("pom.xml")).use { input ->
            MavenXpp3Reader().read(input).modules
        }
    } catch (e: Exception) {
        logger.warn("Maven plugin: failed to parse modules in ${moduleDir.fileName}: ${e.message}")
        emptyList()
    }

    private fun discoverFallbackPomRoots(projectRoot: Path): List<Path> = try {
        Files.find(
            projectRoot,
            FALLBACK_POM_DISCOVERY_DEPTH,
            { path, attributes ->
                attributes.isRegularFile &&
                    path.fileName.toString() == "pom.xml"
            }
        ).use { paths ->
            paths.toList()
                .map { it.parent.toAbsolutePath().normalize() }
                .distinct()
                .sorted()
        }
    } catch (e: Exception) {
        logger.warn("Maven plugin: failed to walk for pom.xml files: ${e.message}")
        emptyList()
    }
}
