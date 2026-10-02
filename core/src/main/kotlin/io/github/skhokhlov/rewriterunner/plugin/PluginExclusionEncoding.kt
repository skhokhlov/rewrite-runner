package io.github.skhokhlov.rewriterunner.plugin

/**
 * Escapes a string for embedding in a Groovy double-quoted string literal (backslash, double
 * quote, and `$` for GString interpolation). Shared by [PluginExclusionEncoding] and by
 * [GradlePluginStrategy]'s other Groovy string literals (recipe names, paths, repository
 * credentials).
 */
internal fun String.groovyString(): String =
    replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")

/**
 * Encodes `--exclude-paths` globs into each build tool's native Stage 0 plugin syntax.
 *
 * The two formats are not unifiable into one representation — Gradle takes a list of DSL
 * statements, Maven a single CSV property value — but each format previously had its own
 * ad hoc, untested inline implementation duplicated across [GradlePluginStrategy] and
 * [MavenPluginStrategy]. This gives each format one implementation and one set of tests.
 */
internal object PluginExclusionEncoding {
    /**
     * One `exclusion("<escaped glob>")` DSL line per glob, for the `rootProject { rewrite { } }`
     * block of the Gradle init script. Whether that block's placement reaches subproject files is
     * a separate concern (see issue #182) — this function only encodes the glob text.
     */
    fun gradleDsl(excludePaths: List<String>): List<String> =
        excludePaths.map { "exclusion(\"${it.groovyString()}\")" }

    /**
     * A comma-joined `-Drewrite.exclusions=<csv>` value, or `null` when [excludePaths] is empty.
     * Globs are not escaped: a glob using comma-alternation (e.g. `src/{main,test}/x`) is
     * corrupted by this join — a known limitation tracked separately as issue #183, not fixed
     * here.
     */
    fun mavenCsv(excludePaths: List<String>): String? =
        excludePaths.takeIf { it.isNotEmpty() }?.joinToString(",")
}
