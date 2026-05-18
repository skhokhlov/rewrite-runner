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
 * [ExecutionDiagnostics.parseFailures] so callers can see which files the parsers
 * could not handle without having to scrape the logs.
 *
 * The same file path may appear in more than one [ParseFailure] when several parsers
 * have tried and failed on it — for example a `pom.xml` that trips `MavenParser` and
 * also `XmlParser` produces one entry per parser.
 *
 * @property path Project-relative source path, or — for non-file failures recorded by
 *   the classpath-resolution stages — the malformed Maven coordinate string itself.
 * @property reason Short, human-readable cause — typically the exception message
 *   from the parser, the `ParseExceptionResult` marker attached to a [org.openrewrite.tree.ParseError],
 *   the literal text `"silently dropped by <parser>"` when a parser returned fewer
 *   files than it was given, or `"illegal Maven coordinate"` for coordinate-string
 *   failures from the classpath-resolution stages.
 * @property parser The producer that gave up on this entry. Either a canonical parser
 *   name (e.g. `"JavaParser"`, `"MavenParser"`, `"XmlParser"`) or — for malformed Maven
 *   coordinate strings encountered while assembling the LST classpath — the stage name
 *   (`"DependencyResolutionStage"`, `"BuildFileParseStage"`).
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
 * @property parseFailures Non-fatal failures collected across every parser the LST
 *   pipeline ran *and* the classpath-resolution stages. The signals end up here:
 *
 *   - **[org.openrewrite.tree.ParseError] SourceFiles** in the parser output — the
 *     parser produced a stub instead of a real LST node. The `ParseError` itself
 *     still appears in [io.github.skhokhlov.rewriterunner.lst.LstBuildResult.sourceFiles],
 *     so callers can inspect it.
 *   - **Silently dropped files** — the parser was given a file but returned nothing
 *     for it. The reason is `"silently dropped by <parser>"`.
 *   - **Thrown exceptions** from `parser.parse(...)` — caught so the build does not
 *     abort. One entry is recorded for every file in the batch that threw.
 *   - **Malformed Maven coordinate strings** encountered while resolving the LST
 *     classpath via Stage 2 / Stage 3. The `path` field holds the offending
 *     coordinate, the `reason` is `"illegal Maven coordinate"`, and the `parser`
 *     is the stage name (`"DependencyResolutionStage"` or `"BuildFileParseStage"`).
 *
 *   Empty when every file parsed cleanly. With one deliberate exception, the build
 *   does not abort on per-file parse failures: the recipe still runs against whatever
 *   was successfully parsed.
 *
 *   **Exception — MavenParser non-URI failures are fatal by design.** A
 *   `MavenParser` throw whose cause chain does not contain a `URISyntaxException`
 *   is rethrown rather than recorded, so unrelated MavenParser regressions surface
 *   instead of being silently downgraded. Only URI-class MavenParser failures fall
 *   back to `XmlParser` (and are recorded here); every other parser's batch throws
 *   are caught and recorded.
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
