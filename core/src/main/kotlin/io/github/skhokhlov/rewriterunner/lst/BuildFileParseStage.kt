package io.github.skhokhlov.rewriterunner.lst

import io.github.skhokhlov.rewriterunner.AetherContext
import io.github.skhokhlov.rewriterunner.RunnerLogger
import io.github.skhokhlov.rewriterunner.lst.utils.StaticBuildFileParser
import io.github.skhokhlov.rewriterunner.lst.utils.hasBuildGradle
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import org.apache.maven.model.io.xpp3.MavenXpp3Reader
import org.eclipse.aether.artifact.DefaultArtifact
import org.eclipse.aether.collection.CollectRequest
import org.eclipse.aether.graph.Dependency
import org.eclipse.aether.resolution.DependencyRequest
import org.eclipse.aether.resolution.DependencyResolutionException
import org.eclipse.aether.util.filter.ScopeDependencyFilter

/**
 * Stage 3 of the LST classpath-resolution pipeline: parse build descriptors
 * statically (no subprocess) and resolve declared dependencies via full POM
 * traversal using Maven Resolver (Eclipse Aether).
 *
 * **Why Stage 3?**
 * Stage 2 ([DependencyResolutionStage]) requires the build tool to be available
 * as a subprocess. Stage 3 is the fallback when Stage 2 fails — for example,
 * when Maven/Gradle is not installed, when subprocess invocations time out, or
 * when the project has an incomplete build setup.
 *
 * **How it works:**
 * 1. Build files (`pom.xml`, `build.gradle`, `build.gradle.kts`) are parsed
 *    statically via [io.github.skhokhlov.rewriterunner.lst.utils.StaticBuildFileParser] to extract `groupId:artifactId:version`
 *    coordinates.
 * 2. All coordinates are passed to [resolveWithPomTraversal] for full POM
 *    traversal, applying Maven conflict resolution (nearest-wins) across the
 *    full transitive graph.
 *
 * **Maven projects:** Discovers all `pom.xml` files (root, declared `<modules>`,
 * and subdirectories up to depth 3 for projects without a root aggregator POM)
 * and collects their declared dependencies.
 *
 * **Gradle projects:** Delegates to [io.github.skhokhlov.rewriterunner.lst.utils.StaticBuildFileParser.parseGradleDependenciesStatically],
 * which handles multi-module projects via `settings.gradle` discovery and
 * version catalogs under `gradle/\*.versions.toml`.
 *
 * **Mixed Maven+Gradle projects:** Both paths are attempted and coordinates combined.
 *
 * **Failure behaviour:** When no coordinates are found, returns an empty list,
 * causing [LstBuilder] to fall through to [LocalRepositoryStage] (Stage 4).
 *
 * **Extensibility:** The class is `open` with `open` methods so tests can subclass
 * it to intercept resolution calls without triggering network access.
 */
open class BuildFileParseStage(
    private val aetherContext: AetherContext,
    private val logger: RunnerLogger
) {
    private val staticParser = StaticBuildFileParser(logger)

    /**
     * Parses build descriptors statically and resolves declared dependencies
     * via POM traversal.
     *
     * @return Resolved JAR paths, or an empty list when no build files are found
     *   or resolution produces no results.
     */
    open fun resolveClasspath(projectDir: Path): List<Path> {
        val coords = gatherAllCoordinates(projectDir)
        if (coords.isEmpty()) {
            logger.info("Stage 3: no coordinates found in build descriptors")
            return emptyList()
        }
        logger.debug("Stage 3: resolving ${coords.size} coordinate(s) via POM traversal")
        return resolveWithPomTraversal(coords).also { result ->
            if (result.isNotEmpty()) logger.info("Stage 3 succeeded: ${result.size} JAR(s)")
        }
    }

    /**
     * Collects `groupId:artifactId:version` coordinates from all build files found
     * under [projectDir] (Maven + Gradle), deduplicated.
     */
    internal fun gatherAllCoordinates(projectDir: Path): List<String> {
        val coords = mutableListOf<String>()

        // Maven: find all pom.xml files (root + modules + subdirs)
        coords += discoverAndParseMavenPoms(projectDir)

        // Gradle: static parse of root + subprojects + version catalogs
        if (hasBuildGradle(projectDir)) {
            // Root has a build file: parseGradleDependenciesStatically handles root + declared subprojects
            coords += staticParser.parseGradleDependenciesStatically(projectDir)
        } else {
            // No root build file: walk for any subdir build files (returns empty if none found)
            coords += parseGradleSubdirs(projectDir)
        }

        return coords.distinct()
    }

    /**
     * Resolves declared (direct) coordinates via POM traversal using Maven Resolver's
     * dependency graph, applying nearest-wins conflict resolution across the full
     * transitive graph. Used when only direct coordinates are known (static parsing).
     *
     * Includes `compile`, `provided`, and `test` scoped transitive dependencies so
     * that OpenRewrite can resolve types in both main and test source sets.
     * Excludes `runtime` and `system` scoped transitives — consistent with Stage 2.
     *
     * Overridable so tests can intercept resolution without network access.
     */
    protected open fun resolveWithPomTraversal(coordinates: List<String>): List<Path> {
        logger.info("Resolving ${coordinates.size} declared dependencies via Maven Resolver")
        val deps = coordinates.map { Dependency(DefaultArtifact(it), "compile") }
        val collectRequest = CollectRequest(deps, emptyList(), aetherContext.remoteRepos)
        val scopeFilter = ScopeDependencyFilter(null, listOf("runtime", "system"))
        val depRequest = DependencyRequest(collectRequest, scopeFilter)
        return try {
            aetherContext.system
                .resolveDependencies(aetherContext.session, depRequest)
                .artifactResults
                .mapNotNull { it.artifact?.path }
        } catch (e: DependencyResolutionException) {
            val partial = e.result?.artifactResults?.mapNotNull { it.artifact?.path }.orEmpty()
            val firstError = e.message?.lineSequence()?.firstOrNull { it.isNotBlank() }
            return if (partial.isNotEmpty()) {
                logger.warn(
                    "Partial classpath resolution (${partial.size} JAR(s); some deps missing): $firstError"
                )
                partial
            } else {
                logger.warn("Could not resolve project classpath: $firstError")
                emptyList()
            }
        }
    }

    /**
     * Finds all `pom.xml` files reachable from [projectDir] — following declared
     * `<modules>` and walking subdirectories up to depth 3 — and returns the
     * union of their declared compile/runtime dependency coordinates.
     */
    private fun discoverAndParseMavenPoms(projectDir: Path): List<String> {
        val pomDirs = mutableSetOf<Path>()

        val rootPom = projectDir.resolve("pom.xml")
        if (rootPom.exists()) {
            pomDirs.add(projectDir)
            collectModuleDirs(projectDir, pomDirs, depth = 0)
        } else {
            // No root aggregator — walk subdirs up to depth 3 for standalone modules
            walkForPomDirs(projectDir, pomDirs)
        }

        return pomDirs.flatMap { dir -> staticParser.parseMavenDependencies(dir) }
    }

    /** Recursively follows `<modules>` declarations to collect multi-module POM directories. */
    private fun collectModuleDirs(dir: Path, found: MutableSet<Path>, depth: Int) {
        if (depth >= 3) return
        try {
            val model = MavenXpp3Reader().read(dir.resolve("pom.xml").toFile().inputStream())
            for (module in model.modules) {
                val moduleDir = dir.resolve(module)
                if (moduleDir.resolve("pom.xml").exists() && found.add(moduleDir)) {
                    collectModuleDirs(moduleDir, found, depth + 1)
                }
            }
        } catch (e: Exception) {
            logger.warn("Stage 3: failed to parse Maven modules in ${dir.fileName}: ${e.message}")
        }
    }

    /** Walks [projectDir] up to depth 3, collecting parent directories of found `pom.xml` files. */
    private fun walkForPomDirs(projectDir: Path, found: MutableSet<Path>) {
        try {
            Files.walk(projectDir, 3).use { stream ->
                stream.filter { path -> path.fileName?.toString() == "pom.xml" }
                    .forEach { pomFile -> found.add(pomFile.parent) }
            }
        } catch (e: Exception) {
            logger.warn("Stage 3: failed to walk for pom.xml files: ${e.message}")
        }
    }

    /**
     * Walks [projectDir] up to depth 3, calling [StaticBuildFileParser.parseGradleDependenciesStatically]
     * for each subdirectory containing a `build.gradle` or `build.gradle.kts` file.
     *
     * Used when there is no root build file but build files exist in subdirectories.
     */
    private fun parseGradleSubdirs(projectDir: Path): List<String> {
        val coords = mutableListOf<String>()
        try {
            Files.walk(projectDir, 3).use { stream ->
                stream.filter { path ->
                    val name = path.fileName?.toString() ?: return@filter false
                    path.parent != projectDir &&
                        (name == "build.gradle" || name == "build.gradle.kts")
                }.forEach { buildFile ->
                    coords += staticParser.parseGradleDependenciesStatically(buildFile.parent)
                }
            }
        } catch (e: Exception) {
            logger.warn("Stage 3: failed to walk Gradle build files in subdirs: ${e.message}")
        }
        return coords
    }
}
