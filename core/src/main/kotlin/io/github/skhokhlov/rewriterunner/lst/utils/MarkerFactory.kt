package io.github.skhokhlov.rewriterunner.lst.utils

import io.github.skhokhlov.rewriterunner.RunnerLogger
import io.github.skhokhlov.rewriterunner.lst.utils.StaticBuildFileParser
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.exists
import org.openrewrite.SourceFile
import org.openrewrite.gradle.marker.GradleDependencyConfiguration
import org.openrewrite.gradle.marker.GradleProject
import org.openrewrite.marker.BuildTool
import org.openrewrite.marker.GitProvenance
import org.openrewrite.marker.OperatingSystemProvenance
import org.openrewrite.marker.ci.BuildEnvironment
import org.openrewrite.maven.tree.Dependency
import org.openrewrite.maven.tree.GroupArtifactVersion
import org.openrewrite.maven.tree.MavenRepository
import org.openrewrite.maven.tree.ResolvedDependency
import org.openrewrite.maven.tree.ResolvedGroupArtifactVersion

/**
 * Builds provenance and build-tool markers that are attached to every parsed [org.openrewrite.SourceFile].
 *
 * Covers:
 * - [org.openrewrite.marker.ci.BuildEnvironment] — CI/CD environment info (GitHub Actions, Jenkins, etc.)
 * - [org.openrewrite.marker.GitProvenance] — git remote/branch/commit metadata
 * - [org.openrewrite.marker.OperatingSystemProvenance] — current OS info
 * - [org.openrewrite.marker.BuildTool] — Maven or Gradle version
 * - [org.openrewrite.gradle.marker.GradleProject] — per-build-file Gradle project descriptor (group, name, version, configs)
 */
internal class MarkerFactory(
    private val logger: RunnerLogger,
    private val staticParser: StaticBuildFileParser,
    private val versionDetector: VersionDetector
) {
    fun buildEnvironment(): BuildEnvironment? = try {
        BuildEnvironment.build { key -> System.getenv(key) }
    } catch (e: Exception) {
        logger.debug("BuildEnvironment unavailable: ${e.message}")
        null
    }

    fun gitProvenance(projectDir: Path, buildEnv: BuildEnvironment?): GitProvenance? = try {
        GitProvenance.fromProjectDirectory(projectDir, buildEnv)
    } catch (e: Exception) {
        logger.debug("Git provenance unavailable: ${e.message}")
        null
    }

    fun operatingSystem(): OperatingSystemProvenance = OperatingSystemProvenance.current()

    /**
     * Detects the build tool type and version for attaching a [org.openrewrite.marker.BuildTool] marker.
     * Returns null when no build tool is found at [projectDir].
     */
    fun detectBuildToolMarker(projectDir: Path): BuildTool? = when {
        projectDir.resolve("pom.xml").exists() -> {
            val version = detectMavenVersion(projectDir)
            BuildTool(UUID.randomUUID(), BuildTool.Type.Maven, version)
        }

        hasBuildGradle(projectDir) -> {
            val version = detectGradleWrapperVersion(projectDir) ?: "unknown"
            BuildTool(UUID.randomUUID(), BuildTool.Type.Gradle, version)
        }

        else -> null
    }

    /**
     * Attaches a [org.openrewrite.gradle.marker.GradleProject] marker to [sf] when [resolutionResult] contains project data
     * for the build file's Gradle project path. Returns [sf] unchanged when data is unavailable.
     */
    fun addGradleProjectMarker(
        sf: SourceFile,
        projectDir: Path,
        resolutionResult: ClasspathResolutionResult
    ): SourceFile {
        val gradleProjectData = resolutionResult.gradleProjectData ?: return sf
        val buildFile = projectDir.resolve(sf.sourcePath)
        val projectPath = resolveGradleProjectPath(buildFile, projectDir, gradleProjectData.keys)
        val projectData = gradleProjectData[projectPath] ?: return sf
        val projectDirectory = resolveProjectDirectory(projectDir, projectPath)
        val marker =
            buildGradleProjectMarker(projectPath, projectDir, projectDirectory, projectData)
        return sf.withMarkers(sf.markers.addIfAbsent(marker))
    }

    private fun detectGradleWrapperVersion(projectDir: Path): String? =
        versionDetector.parseGradleVersionFromWrapper(
            projectDir.resolve("gradle/wrapper/gradle-wrapper.properties")
        )

    private fun detectMavenVersion(projectDir: Path): String = try {
        val mvnCmd = resolveMavenCommand(projectDir)
        val output = StringBuilder()
        val result = runProcess(
            projectDir,
            listOf(mvnCmd, "--version"),
            captureStdout = output,
            timeoutSeconds = 5,
            logger = logger
        )
        if (result == 0) {
            Regex("""Apache Maven ([\d.]+)""").find(output.toString())?.groupValues?.get(1)
                ?: "unknown"
        } else {
            "unknown"
        }
    } catch (e: Exception) {
        "unknown"
    }

    /**
     * Maps a build file path to a Gradle project path (e.g. `":"` for root,
     * `":api"` for a subproject whose build file is at `api/build.gradle`).
     */
    private fun resolveGradleProjectPath(
        buildFile: Path,
        projectDir: Path,
        availablePaths: Set<String>
    ): String {
        val parentDir = buildFile.parent?.let {
            projectDir.relativize(it).toString().replace('\\', '/')
        } ?: ""
        return availablePaths.find { path ->
            path.trimStart(':').replace(':', '/') == parentDir
        } ?: ":"
    }

    /**
     * Constructs a [org.openrewrite.gradle.marker.GradleProject] marker from the given [projectPath] and [projectData].
     */
    private fun buildGradleProjectMarker(
        projectPath: String,
        projectDir: Path,
        projectDirectory: Path,
        projectData: GradleProjectData
    ): GradleProject {
        val name = resolveProjectName(projectPath, projectDir)
        val buildText = try {
            findBuildFile(projectDirectory)?.toFile()?.readText()
        } catch (e: Exception) {
            null
        }
        val group = buildText?.let {
            Regex("""(?:^|\n)\s*group\s*[=:]\s*["']([^"']+)["']""").find(it)?.groupValues?.get(1)
        } ?: ""
        val version = buildText?.let {
            Regex("""(?:^|\n)\s*version\s*[=:]\s*["']([^"']+)["']""").find(it)?.groupValues?.get(1)
        } ?: "unspecified"

        val standardResolvable = setOf(
            "compileClasspath",
            "runtimeClasspath",
            "testCompileClasspath",
            "testRuntimeClasspath"
        )

        val nameToConfiguration = projectData.configurationsByName
            .mapValues { (configName, configData) ->
                mapDependencyConfiguration(configName, configData, standardResolvable)
            }

        // Repository URLs come from static build-file parsing.
        // Stage-2 GradleProjectData.repositoryUrls is not currently populated.
        val repoUrls = staticParser.parseRepositoryUrls(projectDirectory, buildText)
        val repos = repoUrls.map { url ->
            MavenRepository.builder().uri(url).knownToExist(true).build()
        }

        return GradleProject.builder()
            .group(group.ifEmpty { null })
            .name(name)
            .version(version.ifEmpty { null })
            .path(projectPath)
            .mavenRepositories(repos)
            .nameToConfiguration(nameToConfiguration)
            .build()
    }

    private fun resolveProjectDirectory(projectDir: Path, projectPath: String): Path =
        if (projectPath == ":") {
            projectDir
        } else {
            projectDir.resolve(projectPath.trimStart(':').replace(':', '/'))
        }

    private fun resolveProjectName(projectPath: String, projectDir: Path): String =
        when (projectPath) {
            ":" -> readSettingsProjectName(projectDir) ?: projectDir.fileName.toString()
            else -> projectPath.substringAfterLast(':').ifEmpty { projectDir.fileName.toString() }
        }

    private fun mapDependencyConfiguration(
        configName: String,
        configData: GradleConfigData,
        standardResolvable: Set<String>
    ): GradleDependencyConfiguration {
        val requested = toRequestedDependencies(configData.requested)
        val directResolved = toDirectResolvedDependencies(configData.resolved, requested)
        return GradleDependencyConfiguration.builder()
            .name(configName)
            .description(null)
            .isTransitive(true)
            .isCanBeResolved(configName in standardResolvable)
            .isCanBeConsumed(false)
            .isCanBeDeclared(true)
            .extendsFrom(emptyList())
            .requested(requested)
            .directResolved(directResolved)
            .exceptionType(null)
            .message(null)
            .constraints(emptyList())
            .attributes(emptyMap())
            .build()
    }

    private fun toRequestedDependencies(coordinates: List<String>): List<Dependency> =
        coordinates.mapNotNull { coord ->
            parseCoordinate(coord)?.let { (group, artifact, version) ->
                Dependency.builder()
                    .gav(GroupArtifactVersion(group, artifact, version))
                    .build()
            }
        }

    private fun toDirectResolvedDependencies(
        resolvedCoordinates: List<String>,
        requested: List<Dependency>
    ): List<ResolvedDependency> {
        val requestedByGa = requested.associateBy { "${it.groupId}:${it.artifactId}" }
        return resolvedCoordinates.mapNotNull { coord ->
            parseCoordinate(coord)?.let { (group, artifact, version) ->
                val requestedDependency = requestedByGa["$group:$artifact"] ?: Dependency.builder()
                    .gav(GroupArtifactVersion(group, artifact, version))
                    .build()
                ResolvedDependency.builder()
                    .repository(null)
                    .gav(ResolvedGroupArtifactVersion(null, group, artifact, version, null))
                    .requested(requestedDependency)
                    .dependencies(emptyList())
                    .licenses(emptyList())
                    .type(null)
                    .classifier(null)
                    .optional(null)
                    .depth(0)
                    .effectiveExclusions(emptyList())
                    .build()
            }
        }
    }

    private fun parseCoordinate(coord: String): Triple<String, String, String>? {
        val parts = coord.split(":", limit = 3)
        if (parts.size != 3) return null
        val group = parts[0].trim()
        val artifact = parts[1].trim()
        val version = parts[2].trim()
        if (group.isEmpty() || artifact.isEmpty() || version.isEmpty()) return null
        return Triple(group, artifact, version)
    }

    /** Reads `rootProject.name` from `settings.gradle(.kts)`. */
    private fun readSettingsProjectName(projectDir: Path): String? = try {
        val settingsFile = findSettingsFile(projectDir) ?: return null
        val text = settingsFile.toFile().readText()
        Regex("""rootProject\.name\s*[=:]\s*["']([^"']+)["']""").find(text)?.groupValues?.get(1)
    } catch (e: Exception) {
        null
    }
}
