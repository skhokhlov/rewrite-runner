package io.github.skhokhlov.rewriterunner.lst

import io.github.skhokhlov.rewriterunner.RunnerLogger
import java.nio.file.Path
import org.openrewrite.Parser
import org.openrewrite.java.internal.JavaTypeCache

/**
 * Creates a parser in the execution JVM. Implementations must be public classes with a public
 * no-argument constructor and available on the worker runtime classpath (also in forked mode).
 * Instances and parsers are created per LST build; no live parser is transported to the worker.
 */
interface ParserProvider {
    /** Defaults used only when an application explicitly registers this provider. */
    fun defaultSelection(): ParserFileSelection = ParserFileSelection()

    /** Whether files assigned to this provider need the existing JVM classpath resolution stages. */
    fun requiresClasspath(): Boolean = false

    /** Construct the parser; [options] are application-defined string settings. */
    fun create(context: ParserContext, options: Map<String, String>): Parser
}

/** Project facilities shared by the parsers participating in one LST build. */
class ParserContext internal constructor(
    val projectDir: Path,
    val classpath: List<Path>,
    val typeCache: JavaTypeCache,
    val logger: RunnerLogger,
    private val gradleClasspath: () -> List<Path>
) {
    /** Gradle installation libraries, resolved lazily only when requested. */
    val gradleDslClasspath: List<Path> by lazy(gradleClasspath)
}

/**
 * Alternative file selectors (a match of any selector is sufficient). Extensions include the
 * leading dot and match without case sensitivity. Names are exact; prefixes ignore case.
 * Globs are project-relative; recursive directory prefixes also match the project root.
 */
data class ParserFileSelection @JvmOverloads constructor(
    val extensions: Set<String> = emptySet(),
    val fileNames: Set<String> = emptySet(),
    val pathGlobs: List<String> = emptyList(),
    val fileNamePrefixes: Set<String> = emptySet(),
    /** Extensions excluded from prefix matching, preserving Dockerfile name fallback semantics. */
    val prefixExcludedExtensions: Set<String> = emptySet()
)

/**
 * A transportable parser configuration. [id] is unique within the runner; [overrides] names the
 * registrations this one explicitly takes precedence over when both select the same file.
 * Unresolved overlaps are errors. Order never supplies precedence.
 */
data class ParserRegistration @JvmOverloads constructor(
    val id: String,
    val providerClassName: String,
    val selection: ParserFileSelection,
    val options: Map<String, String> = emptyMap(),
    val overrides: Set<String> = emptySet()
) {
    internal fun snapshot(): ParserRegistration = copy(
        selection = selection.copy(
            extensions = selection.extensions.toSet(),
            fileNames = selection.fileNames.toSet(),
            pathGlobs = selection.pathGlobs.toList(),
            fileNamePrefixes = selection.fileNamePrefixes.toSet(),
            prefixExcludedExtensions = selection.prefixExcludedExtensions.toSet()
        ),
        options = options.toMap(),
        overrides = overrides.toSet()
    )
}

/** Mutable configuration exposed by RewriteRunner.Builder.configure; changes are snapshotted. */
class RunnerConfiguration internal constructor(registrations: List<ParserRegistration>) {
    val parsers: MutableList<ParserRegistration> = registrations.toMutableList()

    /** Register a provider explicitly, using its defaults unless [selection] is supplied. */
    @JvmOverloads
    fun registerParser(
        id: String,
        provider: Class<out ParserProvider>,
        selection: ParserFileSelection? = null,
        options: Map<String, String> = emptyMap(),
        overrides: Set<String> = emptySet()
    ) {
        parsers += ParserRegistration(
            id,
            provider.name,
            selection ?: provider.getConstructor().newInstance().defaultSelection(),
            options,
            overrides
        )
    }
}
