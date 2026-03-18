package io.github.skhokhlov.rewriterunner.lst.utils

/**
 * Per-Gradle-project configuration data extracted from `gradle dependencies` output.
 *
 * @param configurationsByName Map from configuration name to its dependency data.
 * @param repositoryUrls Repository URLs parsed from the build file.
 */
data class GradleProjectData(
    val configurationsByName: Map<String, GradleConfigData>,
    val repositoryUrls: List<String>
)

/**
 * Dependency data for a single Gradle configuration.
 *
 * @param requested `"group:artifact:version"` strings as declared in the build file.
 * @param resolved `"group:artifact:version"` strings after conflict resolution.
 */
data class GradleConfigData(val requested: List<String>, val resolved: List<String>)
