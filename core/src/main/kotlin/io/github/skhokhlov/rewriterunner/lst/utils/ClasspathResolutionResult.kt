package io.github.skhokhlov.rewriterunner.lst.utils

import java.nio.file.Path

/**
 * Result of classpath resolution from [io.github.skhokhlov.rewriterunner.lst.DependencyResolutionStage.resolveClasspath].
 *
 * @param classpath The resolved JAR paths for JVM parsers.
 * @param gradleProjectData Per-Gradle-project configuration data keyed by Gradle project path
 *   (e.g. `":"` for root, `":api"` for a subproject). `null` for Maven projects, or when
 *   the `gradle dependencies` task could not be run.
 */
data class ClasspathResolutionResult(
    val classpath: List<Path>,
    val gradleProjectData: Map<String, GradleProjectData>? = null
)
