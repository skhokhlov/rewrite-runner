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
 * A non-fatal parse failure recorded during LST building. Surfaced via
 * [ExecutionDiagnostics.parseFailures] so callers can see which files were degraded
 * (e.g. a `pom.xml` parsed by `XmlParser` instead of `MavenParser` because the
 * Maven dependency graph could not be resolved).
 *
 * @property path Project-relative source path.
 * @property reason Short, human-readable cause (usually the exception message).
 * @property parser The parser that gave up on this file (e.g. `"MavenParser"`).
 */
data class ParseFailure(val path: String, val reason: String, val parser: String)

/**
 * Diagnostic info about which execution path produced the run and how its classpath
 * was assembled.
 *
 * @property stageUsed The stage that produced the run, or `null` when every LST
 *   stage produced an empty classpath (recipe ran semantically blind — see #68).
 * @property resolvedJarCount Number of `.jar` entries on the LST classpath (project
 *   class directories excluded). `0` when [stageUsed] is [UsedExecutionStage.PLUGIN]
 *   (the plugin handled resolution internally) or `null`.
 * @property parseFailures Files that the canonical parser could not handle and that
 *   were either dropped or downgraded to a more lenient parser. Empty when every
 *   file parsed successfully.
 */
data class ExecutionDiagnostics(
    val stageUsed: UsedExecutionStage?,
    val resolvedJarCount: Int,
    val parseFailures: List<ParseFailure> = emptyList()
) {
    companion object {
        val PLUGIN = ExecutionDiagnostics(UsedExecutionStage.PLUGIN, 0)
        val EMPTY = ExecutionDiagnostics(null, 0)
    }
}
