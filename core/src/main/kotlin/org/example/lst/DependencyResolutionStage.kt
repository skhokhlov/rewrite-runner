package org.example.lst

import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import org.apache.maven.model.io.xpp3.MavenXpp3Reader
import org.eclipse.aether.ConfigurationProperties
import org.eclipse.aether.RepositorySystem
import org.eclipse.aether.RepositorySystemSession
import org.eclipse.aether.artifact.DefaultArtifact
import org.eclipse.aether.collection.CollectRequest
import org.eclipse.aether.graph.Dependency
import org.eclipse.aether.repository.LocalRepository
import org.eclipse.aether.repository.RemoteRepository
import org.eclipse.aether.resolution.DependencyRequest
import org.eclipse.aether.supplier.RepositorySystemSupplier
import org.example.config.RepositoryConfig
import org.slf4j.LoggerFactory

/**
 * Stage 2: Parse the project's build descriptor and resolve declared dependencies
 * via Maven Resolver, without invoking the project's own build tool.
 *
 * For Gradle projects, first attempts to run `gradle dependencies` for the root
 * project and all declared subprojects to obtain accurately resolved coordinates.
 * Falls back to best-effort static regex parsing if Gradle cannot be invoked.
 */
open class DependencyResolutionStage(
    private val cacheDir: Path,
    private val extraRepositories: List<RepositoryConfig> = emptyList()
) {
    private val log = LoggerFactory.getLogger(DependencyResolutionStage::class.java.name)

    private val system: RepositorySystem by lazy { newRepositorySystem() }
    private val session: RepositorySystemSession by lazy { newSession(system) }
    private val remoteRepos: List<RemoteRepository> by lazy { buildRemoteRepos() }

    open fun resolveClasspath(projectDir: Path): List<Path> {
        val coordinates = when {
            projectDir.resolve("pom.xml").exists() -> parseMavenDependencies(projectDir)
            hasBuildGradle(projectDir) -> parseGradleDependencies(projectDir)
            else -> emptyList()
        }

        if (coordinates.isEmpty()) {
            log.info("No dependencies found in build descriptor")
            return emptyList()
        }

        log.info("Resolving ${coordinates.size} declared dependencies via Maven Resolver")
        val resolved = mutableListOf<Path>()
        val failed = mutableListOf<String>()

        for (coord in coordinates) {
            try {
                val paths = resolveSingle(coord)
                resolved.addAll(paths)
            } catch (e: Exception) {
                failed.add("$coord (${e.message})")
            }
        }

        if (failed.isNotEmpty()) {
            log.warn(
                "Could not resolve ${failed.size} dependencies: ${failed.joinToString(", ")}"
            )
        }

        return resolved
    }

    // ─── Maven pom.xml parsing ────────────────────────────────────────────────

    internal fun parseMavenDependencies(projectDir: Path): List<String> {
        val pomFile = projectDir.resolve("pom.xml")
        return try {
            val model = MavenXpp3Reader().read(pomFile.toFile().inputStream())
            model.dependencies
                .filter { !listOf("provided", "system").contains(it.scope) }
                .mapNotNull { dep ->
                    val version = dep.version?.takeIf { it.isNotBlank() && !it.startsWith("\${") }
                        ?: return@mapNotNull null
                    "${dep.groupId}:${dep.artifactId}:$version"
                }
        } catch (e: Exception) {
            log.warn("Failed to parse pom.xml: ${e.message}")
            emptyList()
        }
    }

    // ─── Gradle dependency resolution ─────────────────────────────────────────

    internal fun parseGradleDependencies(projectDir: Path): List<String> {
        // Try the Gradle dependencies task first — gives accurately resolved versions
        // including version catalog lookups, BOM imports, and conflict resolution.
        val fromTask = runGradleDependenciesTask(projectDir)
        if (fromTask != null) return fromTask

        // Fall back to static regex parsing of the build file.
        return parseGradleDependenciesStatically(projectDir)
    }

    /**
     * Runs `gradle dependencies` for the root project and all declared subprojects,
     * then parses the resolved dependency tree to extract coordinates.
     *
     * Returns the list of resolved coordinates, or null if the task cannot be run
     * (no Gradle wrapper, non-zero exit, or no coordinates found in the output).
     */
    protected open fun runGradleDependenciesTask(projectDir: Path): List<String>? {
        val gradleCmd = when {
            projectDir.resolve("gradlew").exists() -> "./gradlew"
            projectDir.resolve("gradlew.bat").exists() -> "gradlew.bat"
            else -> "gradle"
        }

        val subprojects = discoverSubprojects(projectDir)
        // Build task list: root 'dependencies' + ':sub:dependencies' for each subproject.
        // The `gradle dependencies` task only covers the project it is applied to, so
        // subprojects must be queried explicitly.
        val tasks = mutableListOf("dependencies")
        subprojects.mapTo(tasks) { "$it:dependencies" }

        val output = StringBuilder()
        val result = runProcess(
            projectDir,
            listOf(gradleCmd, "-q") + tasks,
            captureStdout = output
        ) ?: return null

        if (result != 0) {
            log.warn(
                "Gradle dependencies task failed with exit code $result — falling back to static parsing"
            )
            return null
        }

        val coords = parseGradleDependencyTaskOutput(output.toString())
        if (coords.isEmpty()) {
            log.info(
                "Gradle dependencies task returned no coordinates — falling back to static parsing"
            )
            return null
        }

        log.info(
            "Discovered ${coords.size} coordinates via Gradle dependencies task" +
                " (root + ${subprojects.size} subproject(s))"
        )
        return coords
    }

    /**
     * Discovers subproject paths declared via `include()` in `settings.gradle` or
     * `settings.gradle.kts`. Returns paths such as [":module1", ":module2"].
     *
     * The `gradle dependencies` task only covers the project it is invoked on.
     * Subprojects must be queried explicitly using `:sub:dependencies` tasks.
     */
    internal fun discoverSubprojects(projectDir: Path): List<String> {
        val settingsFile =
            projectDir.resolve("settings.gradle.kts").takeIf { it.exists() }
                ?: projectDir.resolve("settings.gradle").takeIf { it.exists() }
                ?: return emptyList()

        return try {
            val text = settingsFile.toFile().readText()
            // Match quoted strings starting with ':' — these are subproject paths
            // inside include() calls in both Groovy and Kotlin DSL settings files.
            val pattern = Regex("""["'](:[^"']+)["']""")
            pattern.findAll(text)
                .map { it.groupValues[1] }
                .filter { it.matches(Regex(""":[a-zA-Z][\w.\-/]*""")) }
                .distinct()
                .toList()
        } catch (e: Exception) {
            log.warn("Failed to parse settings file for subprojects: ${e.message}")
            emptyList()
        }
    }

    /**
     * Parses the text output of `gradle dependencies` and extracts
     * `group:artifact:version` coordinates.
     *
     * Handles resolved version overrides (`1.0 -> 2.0`) by using the final
     * resolved version. Deduplicates results.
     */
    internal fun parseGradleDependencyTaskOutput(output: String): List<String> {
        val coordinates = mutableSetOf<String>()
        // Dependency lines start with tree-drawing characters (+---, \---, |    etc.)
        // followed by group:artifact:declaredVersion, optionally overridden by -> resolvedVersion.
        val depPattern = Regex(
            """^[\s|+\\-]+([a-zA-Z][\w.\-]*:[a-zA-Z][\w.\-]+):([\w.\-]+)(?:\s*->\s*([\w.\-]+))?"""
        )
        for (line in output.lines()) {
            val match = depPattern.find(line) ?: continue
            val groupArtifact = match.groupValues[1]
            val declaredVersion = match.groupValues[2]
            val resolvedVersion = match.groupValues[3].takeIf { it.isNotBlank() }
            val version = resolvedVersion ?: declaredVersion
            // Skip FAILED resolutions (unresolvable dependencies) and blank versions.
            if (version.isNotBlank() && version != "FAILED") {
                coordinates.add("$groupArtifact:$version")
            }
        }
        return coordinates.toList()
    }

    /**
     * Best-effort static regex parse of `build.gradle` / `build.gradle.kts`.
     * Used as fallback when the Gradle dependencies task cannot be run.
     */
    internal fun parseGradleDependenciesStatically(projectDir: Path): List<String> {
        val buildFile =
            projectDir.resolve("build.gradle.kts").takeIf { it.exists() }
                ?: projectDir.resolve("build.gradle").takeIf { it.exists() }
                ?: return emptyList()

        return try {
            val text = buildFile.toFile().readText()
            val coordinates = mutableListOf<String>()

            // Match quoted strings like "group:artifact:version"
            val coordPattern = Regex("""["']([a-zA-Z][\w.\-]+:[a-zA-Z][\w.\-]+:[\w.\-]+)["']""")
            coordPattern.findAll(text).forEach { match ->
                val coord = match.groupValues[1]
                // Skip BOM / platform entries
                if (!coord.contains("bom") && !coord.contains("platform")) {
                    coordinates.add(coord)
                }
            }

            // Match Kotlin DSL form: implementation("group", "artifact", "version")
            val threeArgPattern = Regex(
                """(?:implementation|api|compileOnly|runtimeOnly)\s*\(\s*["']([^"']+)["']\s*,\s*["']([^"']+)["']\s*,\s*["']([^"']+)["']\s*\)"""
            )
            threeArgPattern.findAll(text).forEach { match ->
                coordinates.add(
                    "${match.groupValues[1]}:${match.groupValues[2]}:${match.groupValues[3]}"
                )
            }

            coordinates.distinct()
        } catch (e: Exception) {
            log.warn("Failed to parse Gradle build file: ${e.message}")
            emptyList()
        }
    }

    // ─── Maven Resolver ───────────────────────────────────────────────────────

    private fun resolveSingle(coordinate: String): List<Path> {
        val artifact = DefaultArtifact(coordinate)
        val dep = Dependency(artifact, "runtime")
        val collectRequest = CollectRequest(dep, remoteRepos)
        val depRequest = DependencyRequest(collectRequest, null)
        val result = system.resolveDependencies(session, depRequest)
        return result.artifactResults.mapNotNull { it.artifact?.path }
    }

    private fun buildRemoteRepos(): List<RemoteRepository> {
        val repos = mutableListOf(
            RemoteRepository.Builder(
                "central",
                "default",
                "https://repo.maven.apache.org/maven2"
            ).build()
        )
        extraRepositories.forEach { cfg ->
            val builder = RemoteRepository.Builder(
                cfg.url.hashCode().toString(),
                "default",
                cfg.url
            )
            if (cfg.username != null && cfg.password != null) {
                builder.setAuthentication(
                    org.eclipse.aether.util.repository.AuthenticationBuilder()
                        .addUsername(cfg.username)
                        .addPassword(cfg.password)
                        .build()
                )
            }
            repos.add(builder.build())
        }
        return repos
    }

    private fun newRepositorySystem(): RepositorySystem = RepositorySystemSupplier().get()

    private fun newSession(system: RepositorySystem): RepositorySystemSession {
        val repoDir = cacheDir.resolve("repository").also { it.toFile().mkdirs() }
        val localRepo = LocalRepository(repoDir)
        return system
            .createSessionBuilder()
            .withLocalRepositories(localRepo)
            .setConfigProperty(ConfigurationProperties.CONNECT_TIMEOUT, 30_000)
            .setConfigProperty(ConfigurationProperties.REQUEST_TIMEOUT, 60_000)
            .setConfigProperty("aether.remoteRepositoryFilter.prefixes.resolvePrefixFiles", false)
            .setIgnoreArtifactDescriptorRepositories(true)
            .build()
    }

    private fun hasBuildGradle(dir: Path): Boolean =
        dir.resolve("build.gradle").exists() || dir.resolve("build.gradle.kts").exists()

    // ─── Process runner ───────────────────────────────────────────────────────

    private fun runProcess(
        workDir: Path,
        command: List<String>,
        captureStdout: StringBuilder? = null,
        timeoutSeconds: Long = 120
    ): Int? {
        val pb = ProcessBuilder(command).directory(workDir.toFile())

        if (captureStdout != null) {
            pb.redirectError(ProcessBuilder.Redirect.DISCARD)
        } else {
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD)
            pb.redirectError(ProcessBuilder.Redirect.DISCARD)
        }

        val process =
            try {
                pb.start()
            } catch (e: Exception) {
                log.warn("Failed to start process ${command.first()}: ${e.message}")
                return null
            }

        if (captureStdout != null) {
            captureStdout.append(process.inputStream.bufferedReader().readText())
        }

        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            log.warn("Process ${command.first()} timed out after ${timeoutSeconds}s")
            return null
        }

        return process.exitValue()
    }
}
