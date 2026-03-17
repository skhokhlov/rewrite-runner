package io.github.skhokhlov.rewriterunner.lst

import io.github.skhokhlov.rewriterunner.RunnerLogger
import java.nio.file.Files
import java.nio.file.Path

/**
 * Resolves the Gradle DSL classpath used when parsing Gradle build scripts.
 *
 * Gradle Groovy DSL files (`.gradle`) and Gradle Kotlin DSL files (`*.gradle.kts`) need
 * the Gradle API on the classpath for type resolution. Plain `.kt` sources and non-Gradle
 * `.kts` scripts do **not** receive this classpath.
 *
 * Lookup order:
 * 1. `GRADLE_HOME` environment variable — use `$GRADLE_HOME/lib/`.
 * 2. Gradle wrapper properties (`gradle/wrapper/gradle-wrapper.properties`) in
 *    the project directory — parse the declared Gradle version and locate the unpacked
 *    distribution under `~/.gradle/wrapper/dists/`.
 * 3. Any available distribution under `~/.gradle/wrapper/dists/` — picks the
 *    most recently modified one as a best-effort fallback.
 *
 * Only JARs directly inside `lib/` are included (not `lib/plugins/` or `lib/agents/`).
 */
internal class GradleDslClasspathResolver(
    private val logger: RunnerLogger,
    private val versionDetector: VersionDetector
) {
    /**
     * Returns the list of Gradle API JARs for build-script parsing, or an empty list
     * when no Gradle installation can be located.
     */
    internal fun resolveGradleDslClasspath(projectDir: Path): List<Path> {
        val gradleHome = findGradleHome(projectDir)
        if (gradleHome == null) {
            logger.warn(
                "Gradle DSL classpath not added: no Gradle installation found " +
                    "(set GRADLE_HOME or add a Gradle wrapper to the project)"
            )
            return emptyList()
        }
        val libDir = gradleHome.resolve("lib")
        if (!Files.isDirectory(libDir)) {
            logger.warn("Gradle lib/ directory not found at $libDir")
            return emptyList()
        }
        return Files.list(libDir).use { stream ->
            stream
                .filter { it.fileName.toString().endsWith(".jar") }
                .filter { Files.isRegularFile(it) }
                .toList()
        }
    }

    /**
     * Resolves the root directory of a Gradle installation, trying (in order):
     * 1. `GRADLE_HOME` env var.
     * 2. Gradle version declared in the project's wrapper properties.
     * 3. Any distribution cached under `~/.gradle/wrapper/dists/` (most recently modified).
     */
    private fun findGradleHome(projectDir: Path): Path? {
        val gradleHomeEnv = System.getenv("GRADLE_HOME")
        if (!gradleHomeEnv.isNullOrBlank()) {
            val path = Path.of(gradleHomeEnv)
            if (Files.isDirectory(path)) {
                logger.info("Using Gradle installation from GRADLE_HOME: $path")
                return path
            }
        }

        val gradleUserHome = Path.of(System.getProperty("user.home")).resolve(".gradle")
        val distsRoot = gradleUserHome.resolve("wrapper/dists")

        val wrapperProps = projectDir.resolve("gradle/wrapper/gradle-wrapper.properties")
        if (Files.isRegularFile(wrapperProps)) {
            val gradleVersion = versionDetector.parseGradleVersionFromWrapper(wrapperProps)
            if (gradleVersion != null && Files.isDirectory(distsRoot)) {
                val match = findGradleDistribution(distsRoot, gradleVersion)
                if (match != null) {
                    logger.info(
                        "Using Gradle $gradleVersion distribution from wrapper cache: $match"
                    )
                    return match
                }
            }
        }

        if (Files.isDirectory(distsRoot)) {
            val newest = Files.list(distsRoot).use { stream ->
                stream
                    .filter { Files.isDirectory(it) }
                    .filter { it.fileName.toString().startsWith("gradle-") }
                    .flatMap { distDir ->
                        Files.list(distDir).use { hashStream ->
                            hashStream
                                .filter { Files.isDirectory(it) }
                                .flatMap { hashDir ->
                                    Files.list(hashDir).use { subStream ->
                                        subStream
                                            .filter { Files.isDirectory(it.resolve("lib")) }
                                            .toList()
                                            .stream()
                                    }
                                }
                                .toList()
                                .stream()
                        }
                    }
                    .max(Comparator.comparingLong { Files.getLastModifiedTime(it).toMillis() })
                    .orElse(null)
            }
            if (newest != null) {
                logger.info("Using Gradle distribution (best-effort fallback): $newest")
                return newest
            }
        }

        return null
    }

    /**
     * Scans [distsRoot] for an unpacked Gradle distribution matching [version].
     *
     * The Gradle wrapper unpacks distributions into a three-level hierarchy:
     * `<distsRoot>/gradle-<version>-<type>/<hash>/gradle-<version>/`
     * where the innermost directory is the actual Gradle home (it contains `lib/`).
     */
    private fun findGradleDistribution(distsRoot: Path, version: String): Path? {
        if (!Files.isDirectory(distsRoot)) return null
        return Files.list(distsRoot).use { stream ->
            stream
                .filter { Files.isDirectory(it) }
                .filter { it.fileName.toString().startsWith("gradle-$version-") }
                .flatMap { distDir ->
                    Files.list(distDir).use { hashStream ->
                        hashStream
                            .filter { Files.isDirectory(it) }
                            .flatMap { hashDir ->
                                Files.list(hashDir).use { subStream ->
                                    subStream
                                        .filter { Files.isDirectory(it.resolve("lib")) }
                                        .toList()
                                        .stream()
                                }
                            }
                            .toList()
                            .stream()
                    }
                }
                .findFirst()
                .orElse(null)
        }
    }
}
