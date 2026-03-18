package io.github.skhokhlov.rewriterunner.lst.utils

import io.github.skhokhlov.rewriterunner.RunnerLogger
import java.nio.file.Path
import java.util.Properties
import java.util.UUID
import kotlin.io.path.exists
import org.apache.maven.model.io.xpp3.MavenXpp3Reader
import org.codehaus.plexus.util.xml.Xpp3Dom
import org.openrewrite.java.marker.JavaVersion

/**
 * Detects Java and Kotlin JVM target versions for source files by walking up the
 * directory tree toward the project root.
 *
 * Walk-up algorithm — starting from the source file's immediate parent, each directory
 * is examined for a build descriptor (`pom.xml`, `build.gradle.kts`, `build.gradle`).
 * - Maven: plugin `<release>` > plugin `<source>`/`<target>` > `<properties>` entries.
 * - Gradle: `compileJava.options.release` > `sourceCompatibility`/`targetCompatibility` >
 *   `jvmToolchain()` / `JavaLanguageVersion.of()`.
 *
 * If no explicit version is found in the full ancestor chain the running JVM's major
 * version is used as fallback.
 *
 * Results are cached per directory so each build file is read at most once per invocation.
 */
internal class VersionDetector(private val logger: RunnerLogger) {
    /** Creates a [org.openrewrite.java.marker.JavaVersion] marker with the given source/target version strings. */
    fun buildJavaVersionMarker(source: String, target: String): JavaVersion {
        val createdBy =
            System.getProperty("java.runtime.version") ?: System.getProperty("java.version") ?: ""
        val vmVendor = System.getProperty("java.vm.vendor") ?: ""
        return JavaVersion(UUID.randomUUID(), createdBy, vmVendor, source, target)
    }

    /**
     * Detects the Java source/target version for a specific source file by walking up
     * its directory tree until a build file with an explicit Java version is found.
     */
    fun detectJavaVersionForFile(
        absFilePath: Path,
        projectDir: Path,
        cache: MutableMap<Path, Pair<String, String>?>
    ): Pair<String, String> = walkUpForVersion(
        absFilePath,
        projectDir,
        cache,
        ::detectMavenJavaVersion,
        ::detectGradleJavaVersion
    )

    /**
     * Detects the JVM target version for a Kotlin source file by walking up its directory
     * tree toward [projectDir], using [detectMavenKotlinVersion] and
     * [detectGradleKotlinVersion] at each level.
     */
    fun detectKotlinVersionForFile(
        absFilePath: Path,
        projectDir: Path,
        cache: MutableMap<Path, Pair<String, String>?>
    ): Pair<String, String> = walkUpForVersion(
        absFilePath,
        projectDir,
        cache,
        ::detectMavenKotlinVersion,
        ::detectGradleKotlinVersion
    )

    private fun walkUpForVersion(
        absFilePath: Path,
        projectDir: Path,
        cache: MutableMap<Path, Pair<String, String>?>,
        mavenDetector: (Path) -> Pair<String, String>?,
        gradleDetector: (Path) -> Pair<String, String>?
    ): Pair<String, String> {
        val jvmMajor = normalizeJvmVersion(System.getProperty("java.version") ?: "")
        val fallback = Pair(jvmMajor, jvmMajor)

        var dir: Path? = absFilePath.parent
        while (dir != null && dir.startsWith(projectDir)) {
            if (dir in cache) {
                val cached = cache[dir]
                if (cached != null) return cached
                // null → build file here but no explicit version; keep walking up
            } else {
                val pomFile = dir.resolve("pom.xml")
                val buildFile = findBuildFile(dir)
                val detected: Pair<String, String>? = when {
                    pomFile.exists() -> mavenDetector(dir)

                    buildFile != null -> gradleDetector(buildFile)

                    else -> {
                        if (dir == projectDir) break
                        dir = dir.parent
                        continue
                    }
                }
                cache[dir] = detected
                if (detected != null) return detected
            }
            if (dir == projectDir) break
            dir = dir.parent
        }
        return fallback
    }

    /**
     * Extracts the JVM target version for Kotlin from Maven's `kotlin-maven-plugin`
     * `<jvmTarget>` configuration, then falls back to [detectMavenJavaVersion].
     */
    private fun detectMavenKotlinVersion(dir: Path): Pair<String, String>? {
        return try {
            val model = MavenXpp3Reader().read(dir.resolve("pom.xml").toFile().inputStream())
            val kotlinPlugin = model.build?.plugins?.find { it.artifactId == "kotlin-maven-plugin" }
            val dom = kotlinPlugin?.configuration as? Xpp3Dom
            val jvmTarget = dom?.getChild("jvmTarget")?.value
                ?.takeIf { it.isNotBlank() && !it.startsWith("\${") }
            if (jvmTarget != null) {
                val v = normalizeJvmVersion(jvmTarget)
                return Pair(v, v)
            }
            detectMavenJavaVersion(dir)
        } catch (e: Exception) {
            logger.warn("Failed to detect Kotlin JVM target from pom.xml: ${e.message}")
            null
        }
    }

    /**
     * Extracts the JVM target version for Kotlin from a Gradle build file.
     *
     * Checks `kotlinOptions.jvmTarget` / `JvmTarget.JVM_N` first, then delegates to
     * [detectGradleJavaVersion] for shared settings.
     */
    private fun detectGradleKotlinVersion(buildFile: Path): Pair<String, String>? = try {
        val text = buildFile.toFile().readText()
        val jvmTargetPattern =
            Regex(
                """jvmTarget\s*(?:[=:]\s*|\.set\s*\(\s*)""" +
                    """(?:(?:\w+\.)*JvmTarget\.JVM_(?:1_)?)?["']?(?:1\.)?(\d+)["']?"""
            )
        val jvmTarget = jvmTargetPattern.find(text)?.groupValues?.get(1)
        if (jvmTarget != null) {
            Pair(jvmTarget, jvmTarget)
        } else {
            detectGradleJavaVersion(buildFile)
        }
    } catch (e: Exception) {
        logger.warn("Failed to detect Kotlin JVM target from Gradle build file: ${e.message}")
        null
    }

    /**
     * Extracts Java source/target version from Maven's maven-compiler-plugin.
     * Priority: plugin `<release>` > plugin `<source>`/`<target>` > project properties.
     */
    private fun detectMavenJavaVersion(projectDir: Path): Pair<String, String>? {
        return try {
            val model =
                MavenXpp3Reader().read(projectDir.resolve("pom.xml").toFile().inputStream())

            val compilerPlugin =
                model.build?.plugins?.find { it.artifactId == "maven-compiler-plugin" }
            val dom = compilerPlugin?.configuration as? Xpp3Dom
            if (dom != null) {
                val release = dom.getChild("release")?.value?.takeIf {
                    it.isNotBlank() && !it.startsWith("\${")
                }
                if (release != null) {
                    val v = normalizeJvmVersion(release)
                    return Pair(v, v)
                }

                val source = dom.getChild("source")?.value?.takeIf {
                    it.isNotBlank() && !it.startsWith("\${")
                }
                val target = dom.getChild("target")?.value?.takeIf {
                    it.isNotBlank() && !it.startsWith("\${")
                }
                if (source != null || target != null) {
                    return Pair(
                        normalizeJvmVersion(source ?: target ?: ""),
                        normalizeJvmVersion(target ?: source ?: "")
                    )
                }
            }

            val props = model.properties
            val propsRelease = props["maven.compiler.release"]?.toString()?.takeIf {
                it.isNotBlank()
            }
            if (propsRelease != null) {
                val v = normalizeJvmVersion(propsRelease)
                return Pair(v, v)
            }

            val propsSource =
                props["maven.compiler.source"]?.toString()?.takeIf { it.isNotBlank() }
            val propsTarget =
                props["maven.compiler.target"]?.toString()?.takeIf { it.isNotBlank() }
            if (propsSource != null || propsTarget != null) {
                return Pair(
                    normalizeJvmVersion(propsSource ?: propsTarget ?: ""),
                    normalizeJvmVersion(propsTarget ?: propsSource ?: "")
                )
            }

            null
        } catch (e: Exception) {
            logger.warn("Failed to detect Java version from pom.xml: ${e.message}")
            null
        }
    }

    /**
     * Extracts Java source/target version from a Gradle build file via regex.
     * Handles Groovy DSL and Kotlin DSL forms of `sourceCompatibility`, `jvmToolchain`,
     * `JavaLanguageVersion.of()`, and `compileJava.options.release`.
     */
    private fun detectGradleJavaVersion(buildFile: Path): Pair<String, String>? = try {
        val text = buildFile.toFile().readText()

        val sourcePattern =
            Regex(
                """sourceCompatibility\s*[=:]\s*(?:JavaVersion\.VERSION_(?:1_)?)?['"]?(?:1\.)?(\d+)['"]?"""
            )
        val targetPattern =
            Regex(
                """targetCompatibility\s*[=:]\s*(?:JavaVersion\.VERSION_(?:1_)?)?['"]?(?:1\.)?(\d+)['"]?"""
            )
        val jvmToolchainPattern = Regex("""jvmToolchain\s*\(\s*(\d+)\s*\)""")
        val javaToolchainPattern = Regex("""JavaLanguageVersion\.of\s*\(\s*(\d+)\s*\)""")
        val releasePattern = Regex("""[.\s]release\s*[=.(]\s*(\d+)""")

        val source = sourcePattern.find(text)?.groupValues?.get(1)
        val target = targetPattern.find(text)?.groupValues?.get(1)
        val toolchain = jvmToolchainPattern.find(text)?.groupValues?.get(1)
            ?: javaToolchainPattern.find(text)?.groupValues?.get(1)
        val release = releasePattern.find(text)?.groupValues?.get(1)

        when {
            release != null -> Pair(release, release)

            source != null || target != null -> Pair(
                source ?: target ?: "",
                target ?: source ?: ""
            )

            toolchain != null -> Pair(toolchain, toolchain)

            else -> null
        }
    } catch (e: Exception) {
        logger.warn("Failed to detect Java version from Gradle build file: ${e.message}")
        null
    }

    /**
     * Parses the Gradle version string from `gradle-wrapper.properties`.
     * Extracts the version from the `distributionUrl` value, e.g.
     * `https://services.gradle.org/distributions/gradle-8.7-bin.zip` → `"8.7"`.
     */
    internal fun parseGradleVersionFromWrapper(wrapperProps: Path): String? = try {
        val props = Properties()
        wrapperProps.toFile().inputStream().use { props.load(it) }
        val url = props.getProperty("distributionUrl") ?: return null
        Regex("""gradle-(\d+\.\d+(?:\.\d+)?(?:-[a-zA-Z]+-\d+)?)-(?:bin|all)""")
            .find(url)?.groupValues?.get(1)
    } catch (e: Exception) {
        logger.warn("Failed to parse Gradle wrapper properties: ${e.message}")
        null
    }

    /** Converts JVM version strings like "1.8.0_xxx" → "8", "21.0.1" → "21". */
    internal fun normalizeJvmVersion(version: String): String {
        val v = if (version.startsWith("1.")) version.removePrefix("1.") else version
        return v.substringBefore(".")
    }
}
