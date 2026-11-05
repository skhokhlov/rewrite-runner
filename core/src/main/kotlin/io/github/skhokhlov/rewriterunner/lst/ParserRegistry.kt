package io.github.skhokhlov.rewriterunner.lst

import io.github.skhokhlov.rewriterunner.lst.utils.FileCollector
import java.lang.reflect.InvocationTargetException
import java.nio.file.FileSystems
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.extension

/** One ownership resolver shared by Stage 0 exclusions, specialized selection and full fallback. */
internal class ParserRegistry(registrations: List<ParserRegistration>) {
    val registrations = registrations.map(ParserRegistration::snapshot)
    private val defaults = BuiltinParserProviders.registrations().associateBy { it.id }
    val customized: Boolean = this.registrations != defaults.values.toList()
    private val selections = this.registrations.associateWith { Selection(it.selection) }

    init {
        require(this.registrations.map { it.id }.distinct().size == this.registrations.size) {
            "Parser registration IDs must be unique"
        }
        this.registrations.forEach { registration ->
            require(registration.id.isNotBlank()) { "Parser registration ID must not be blank" }
            require(registration.providerClassName.isNotBlank()) {
                "Parser provider class must not be blank: ${registration.id}"
            }
            require(registration.id !in registration.overrides) {
                "Parser '${registration.id}' cannot override itself"
            }
            require(registration.selection.extensions.all { it.startsWith('.') && it.length > 1 }) {
                "Parser '${registration.id}' extensions must include a leading dot"
            }
        }
        // Precedence must be acyclic even if the corresponding files aren't present in this run.
        val byId = this.registrations.associateBy { it.id }
        val visited = mutableSetOf<String>()
        val visiting = linkedSetOf<String>()
        fun visit(id: String) {
            if (id in visited) return
            require(visiting.add(id)) { "Cyclic parser overrides: $visiting -> $id" }
            byId[id]?.overrides?.forEach(::visit)
            visiting.remove(id)
            visited.add(id)
        }
        this.registrations.forEach { visit(it.id) }
    }

    fun isSpecialized(registration: ParserRegistration): Boolean =
        registration.id in setOf("hcl", "protobuf", "docker") ||
            isCustomized(registration)

    fun isCustomized(registration: ParserRegistration): Boolean =
        defaults[registration.id] != registration

    fun loadProvider(registration: ParserRegistration): ParserProvider = try {
        val loader =
            Thread.currentThread().contextClassLoader ?: ParserProvider::class.java.classLoader
        val provider = Class.forName(registration.providerClassName, true, loader)
            .asSubclass(ParserProvider::class.java).getConstructor().newInstance()
        provider
    } catch (failure: Exception) {
        val cause = (failure as? InvocationTargetException)?.targetException ?: failure
        if (cause is Error) throw cause
        throw IllegalStateException(
            "Cannot load parser '${registration.id}' " +
                "provider '${registration.providerClassName}'. " +
                "It must implement ParserProvider, have a public no-argument constructor, " +
                "and be on the execution runtime classpath.",
            cause
        )
    }

    fun collect(
        projectDir: Path,
        excludePaths: List<String>,
        plainTextMasks: List<String>,
        restrictToExtensions: Set<String> = emptySet(),
        specializedOnly: Boolean = false
    ): Map<ParserRegistration, List<Path>> {
        val exclusions = excludePaths.map { FileSystems.getDefault().getPathMatcher("glob:$it") }
        val masks = Selection(ParserFileSelection(pathGlobs = plainTextMasks))
        val result = linkedMapOf<ParserRegistration, MutableList<Path>>()
        Files.walkFileTree(
            projectDir,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(
                    dir: Path,
                    attrs: BasicFileAttributes
                ): FileVisitResult {
                    if (dir == projectDir) return FileVisitResult.CONTINUE
                    val relative = projectDir.relativize(dir)
                    return if (relative.any {
                            it.toString() in FileCollector.DEFAULT_EXCLUDED_DIRS
                        } ||
                        exclusions.any { it.matches(relative) }
                    ) {
                        FileVisitResult.SKIP_SUBTREE
                    } else {
                        FileVisitResult.CONTINUE
                    }
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (!Files.isRegularFile(file)) return FileVisitResult.CONTINUE
                    val relative = projectDir.relativize(file)
                    if (exclusions.any { it.matches(relative) }) return FileVisitResult.CONTINUE
                    val candidates = registrations.filter {
                        selections.getValue(it).matches(relative)
                    }
                    val winners = candidates.filter { candidate ->
                        candidates.none { other -> candidate.id in other.overrides }
                    }
                    require(winners.size <= 1) {
                        val ids = winners.joinToString { it.id }
                        "Ambiguous parser ownership for '$relative': $ids. " +
                            "Declare an explicit override."
                    }
                    var owner = winners.singleOrNull() ?: if (candidates.isEmpty() &&
                        restrictToExtensions.isEmpty() && masks.matches(relative) &&
                        withinPlainTextLimit(file)
                    ) {
                        registrations.firstOrNull { it.id == "plain-text" }
                    } else {
                        null
                    }
                    val dockerName = listOf("dockerfile", "containerfile").any {
                        file.fileName.toString().startsWith(it, ignoreCase = true)
                    }
                    // The legacy restricted pass recognizes Dockerfile names even when their
                    // suffix belongs to a non-specialized built-in (e.g. Dockerfile.json).
                    // Keep that fallback unless an explicit registration changed ownership.
                    if (dockerName && owner != null && !isSpecialized(owner) &&
                        (specializedOnly || ".dockerfile" in restrictToExtensions)
                    ) {
                        val legacyDocker = registrations.firstOrNull {
                            it.id == "docker" && it == defaults["docker"]
                        }
                        if (legacyDocker != null) owner = legacyDocker
                    }
                    val dockerFallback = owner?.id == "docker" && dockerName &&
                        ".dockerfile" in restrictToExtensions
                    val extensionInScope = restrictToExtensions.isEmpty() ||
                        ".${file.extension}".lowercase() in restrictToExtensions || dockerFallback
                    if (owner != null && extensionInScope &&
                        (!specializedOnly || isSpecialized(owner))
                    ) {
                        result.getOrPut(owner) { mutableListOf() }.add(file)
                    }
                    return FileVisitResult.CONTINUE
                }
            }
        )
        return registrations.mapNotNull { registration ->
            result[registration]?.let { registration to it.toList() }
        }.toMap()
    }

    private fun withinPlainTextLimit(file: Path): Boolean = try {
        Files.size(file) <= FileCollector.PLAIN_TEXT_SIZE_THRESHOLD_MB * 1024L * 1024L
    } catch (_: Exception) {
        false
    }

    private class Selection(selection: ParserFileSelection) {
        private val extensions = selection.extensions.map { it.lowercase() }.toSet()
        private val names = selection.fileNames
        private val prefixes = selection.fileNamePrefixes.map { it.lowercase() }
        private val excluded = selection.prefixExcludedExtensions.map { it.lowercase() }.toSet()
        private val globs = selection.pathGlobs.flatMap { glob ->
            val patterns = if (glob.startsWith("**/")) {
                listOf(glob, glob.removePrefix("**/"))
            } else {
                listOf(glob)
            }
            patterns.map { FileSystems.getDefault().getPathMatcher("glob:$it") }
        }
        fun matches(relative: Path): Boolean {
            val extension = ".${relative.extension}".lowercase()
            val name = relative.fileName.toString()
            return extension in extensions || name in names || globs.any { it.matches(relative) } ||
                (extension !in excluded && prefixes.any { name.lowercase().startsWith(it) })
        }
    }
}

/** Escape a literal project-relative path for the build plugins' glob exclusions. */
internal fun literalExclusion(path: Path): String = path.joinToString("/") { part ->
    buildString {
        part.toString().forEach { ch ->
            if (ch in "\\*?[]{}") append('\\')
            append(ch)
        }
    }
}
