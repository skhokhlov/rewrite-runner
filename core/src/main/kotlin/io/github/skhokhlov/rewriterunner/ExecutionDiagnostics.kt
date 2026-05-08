package io.github.skhokhlov.rewriterunner

/** Which path produced the recipe run (and the classpath behind it, when applicable). */
enum class UsedExecutionStage {
    /** Stage 0 — official OpenRewrite Gradle/Maven plugin handled the recipe internally;
     *  we never observed the classpath directly. */
    PLUGIN,

    /** Stage 1 — project's own build tool extracted the compile classpath. */
    BUILD_TOOL,

    /** Stage 2 — `mvn dependency:tree` / `gradle dependencies` + Maven Resolver. */
    DEPENDENCY_RESOLUTION,

    /** Stage 3 — static build-file parse + POM traversal via Maven Resolver. */
    DIRECT_PARSE,

    /** Stage 4 — local Maven/Gradle cache scan, no network. */
    LOCAL_REPOSITORY
}

/**
 * Diagnostic info about which execution path produced the run and how its classpath
 * was assembled.
 *
 * @property stageUsed The stage that produced the run, or `null` when every LST
 *   stage produced an empty classpath (recipe ran semantically blind — see #68).
 * @property resolvedJarCount Number of `.jar` entries on the LST classpath (project
 *   class directories excluded). `0` when [stageUsed] is [UsedExecutionStage.PLUGIN]
 *   (the plugin handled resolution internally) or `null`.
 */
data class ExecutionDiagnostics(val stageUsed: UsedExecutionStage?, val resolvedJarCount: Int) {
    companion object {
        val PLUGIN = ExecutionDiagnostics(UsedExecutionStage.PLUGIN, 0)
        val EMPTY = ExecutionDiagnostics(null, 0)
    }
}
