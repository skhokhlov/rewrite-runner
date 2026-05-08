package io.github.skhokhlov.rewriterunner.lst.utils

import io.github.skhokhlov.rewriterunner.UsedExecutionStage
import java.nio.file.Path

/**
 * Result of classpath resolution from [io.github.skhokhlov.rewriterunner.lst.DependencyResolutionStage.resolveClasspath].
 *
 * @param classpath The resolved JAR paths for JVM parsers.
 * @param gradleProjectData Per-Gradle-project configuration data keyed by Gradle project path
 *   (e.g. `":"` for root, `":api"` for a subproject). `null` for Maven projects, or when
 *   the `gradle dependencies` task could not be run.
 * @param stageUsed Which pipeline stage produced the classpath, or `null` when every stage
 *   produced an empty result.
 */
data class ClasspathResolutionResult(
    val classpath: List<Path>,
    val gradleProjectData: Map<String, GradleProjectData>? = null,
    val stageUsed: UsedExecutionStage? = null
)
