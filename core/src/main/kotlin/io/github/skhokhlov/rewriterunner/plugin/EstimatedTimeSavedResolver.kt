package io.github.skhokhlov.rewriterunner.plugin

import java.time.Duration

internal object EstimatedTimeSavedResolver {
    fun resolve(sources: List<() -> Duration?>): Duration? =
        sources.firstNotNullOfOrNull { source ->
            source()?.takeIf { it > Duration.ZERO }
        }
}
