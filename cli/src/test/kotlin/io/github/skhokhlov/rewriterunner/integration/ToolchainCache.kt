package io.github.skhokhlov.rewriterunner.integration

import java.io.IOException
import java.net.URL
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.util.zip.ZipException
import java.util.zip.ZipFile
import kotlin.io.path.exists

/**
 * Downloads and caches Maven and Gradle distributions for real-plugin integration tests.
 *
 * Archives are stored under `cli/build/test-cache/toolchains/` (the Gradle build directory of the
 * `cli` module) so `./gradlew clean` clears them. Extraction is guarded by a `FileLock`-based
 * marker so concurrent test workers never race on the same archive.
 *
 * Version sourcing:
 * - [MAVEN_VERSION] pins the current Maven 3.9.x LTS.
 * - [gradleVersion] is supplied by the build via the `rewriterunner.test.gradleVersion`
 *   system property, which `cli/build.gradle.kts` reads from
 *   `gradle/wrapper/gradle-wrapper.properties`. Bumping the project's Gradle wrapper
 *   automatically updates the real-plugin test toolchain — there is no separate pin
 *   to keep in sync. When the system property is absent (rare: running tests outside
 *   Gradle), the property file at the repo root is read directly as a fallback.
 */
object ToolchainCache {
    const val MAVEN_VERSION = "3.9.9"

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 60_000
    private const val MAX_DOWNLOAD_ATTEMPTS = 3
    private const val RETRY_BACKOFF_MS = 1_000L

    /** Gradle distribution version used by real-plugin tests; sourced from the project wrapper. */
    val gradleVersion: String by lazy { resolveGradleVersion() }

    private fun resolveGradleVersion(): String {
        System.getProperty("rewriterunner.test.gradleVersion")?.takeIf { it.isNotBlank() }
            ?.let { return it }
        // Fallback for ad-hoc IDE runs that bypass the Gradle test task: locate the repo root
        // by walking up until we find gradle/wrapper/gradle-wrapper.properties.
        var dir: Path? = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        while (dir != null) {
            val candidate = dir.resolve("gradle/wrapper/gradle-wrapper.properties")
            if (Files.exists(candidate)) {
                val match = Regex("""gradle-(\d+\.\d+(?:\.\d+)?)-(?:bin|all)\.zip""")
                    .find(Files.readString(candidate))
                if (match != null) return match.groupValues[1]
            }
            dir = dir.parent
        }
        error(
            "Gradle version unavailable: pass -Drewriterunner.test.gradleVersion=<v> or run " +
                "via ./gradlew :cli:testRealPlugin"
        )
    }

    private val cacheDir: Path by lazy {
        val workDir = Path.of(System.getProperty("user.dir"))
        // When tests run, working directory is the cli module directory.
        // Fall back to a sibling cli/build if running from project root.
        val base =
            when {
                workDir.resolve("build").exists() -> workDir.resolve("build/test-cache/toolchains")

                workDir.resolve("cli/build").exists() ->
                    workDir.resolve("cli/build/test-cache/toolchains")

                else -> workDir.resolve("build/test-cache/toolchains")
            }
        Files.createDirectories(base)
        base
    }

    /** Returns the extracted Maven home directory, downloading and extracting on first call. */
    fun mavenHome(): Path {
        val archiveName = "apache-maven-$MAVEN_VERSION-bin.zip"
        val url =
            "https://repo.maven.apache.org/maven2/org/apache/maven/" +
                "apache-maven/$MAVEN_VERSION/apache-maven-$MAVEN_VERSION-bin.zip"
        return extractIfNeeded(
            archiveName = archiveName,
            url = url,
            extractedDirName = "apache-maven-$MAVEN_VERSION"
        )
    }

    /** Returns the extracted Gradle home directory, downloading and extracting on first call. */
    fun gradleHome(): Path {
        val version = gradleVersion
        val archiveName = "gradle-$version-bin.zip"
        val url =
            "https://services.gradle.org/distributions/gradle-$version-bin.zip"
        return extractIfNeeded(
            archiveName = archiveName,
            url = url,
            extractedDirName = "gradle-$version"
        )
    }

    private fun extractIfNeeded(archiveName: String, url: String, extractedDirName: String): Path {
        val extractedDir = cacheDir.resolve(extractedDirName)
        val markerFile = cacheDir.resolve("$extractedDirName.done")

        if (markerFile.exists() && extractedDir.exists()) return extractedDir

        val lockFile = cacheDir.resolve("$extractedDirName.lock")
        FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { ch ->
            ch.lock().use {
                if (markerFile.exists() && extractedDir.exists()) return extractedDir

                val archivePath = cacheDir.resolve(archiveName)
                downloadIfNeeded(archivePath, url)
                extractZip(archivePath, cacheDir)
                // Idempotent marker write: a stale `.done` left behind without its extracted
                // directory must not turn re-extraction into a FileAlreadyExistsException.
                Files.write(markerFile, ByteArray(0))
            }
        }
        return extractedDir
    }

    private fun downloadIfNeeded(target: Path, url: String) {
        if (target.exists() && isValidZip(target)) return
        Files.deleteIfExists(target)
        val tmp = target.parent.resolve("${target.fileName}.tmp")
        // Explicit timeouts so a stalled mirror fails fast instead of hanging the lane for the
        // whole task timeout; retry with backoff so a single dropped connection on a cold CI
        // run doesn't redden the lane outright.
        var lastError: Exception? = null
        repeat(MAX_DOWNLOAD_ATTEMPTS) { attempt ->
            try {
                val conn = URL(url).openConnection()
                conn.connectTimeout = CONNECT_TIMEOUT_MS
                conn.readTimeout = READ_TIMEOUT_MS
                conn.getInputStream().use { input ->
                    Files.copy(input, tmp, StandardCopyOption.REPLACE_EXISTING)
                }
                if (!isValidZip(tmp)) {
                    throw IOException("Downloaded archive is not a valid ZIP: $url")
                }
                Files.move(
                    tmp,
                    target,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
                return
            } catch (e: Exception) {
                Files.deleteIfExists(tmp)
                lastError = e
                if (attempt < MAX_DOWNLOAD_ATTEMPTS - 1) {
                    Thread.sleep(RETRY_BACKOFF_MS * (attempt + 1))
                }
            }
        }
        throw IOException(
            "Failed to download $url after $MAX_DOWNLOAD_ATTEMPTS attempts",
            lastError
        )
    }

    private fun isValidZip(path: Path): Boolean = try {
        // Opening succeeds only if the ZIP central directory is intact; a truncated download
        // throws here. The entry count itself carries no signal (always >= 0).
        ZipFile(path.toFile()).close()
        true
    } catch (_: ZipException) {
        false
    } catch (_: IOException) {
        false
    }

    private fun extractZip(archive: Path, destDir: Path) {
        val canonicalDest = destDir.toAbsolutePath().normalize()
        ZipFile(archive.toFile()).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                val outFile = canonicalDest.resolve(entry.name).normalize()
                if (!outFile.startsWith(canonicalDest)) {
                    throw IOException(
                        "Zip entry '${entry.name}' would extract outside destination directory"
                    )
                }
                if (entry.isDirectory) {
                    Files.createDirectories(outFile)
                } else {
                    Files.createDirectories(outFile.parent)
                    zip.getInputStream(entry).use { input ->
                        Files.copy(input, outFile, StandardCopyOption.REPLACE_EXISTING)
                    }
                    makeExecutableIfBin(outFile, entry.name)
                }
            }
        }
    }

    private fun makeExecutableIfBin(file: Path, entryName: String) {
        if (!entryName.contains("/bin/")) return
        try {
            Files.setPosixFilePermissions(
                file,
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.GROUP_READ,
                    PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_READ,
                    PosixFilePermission.OTHERS_EXECUTE
                )
            )
        } catch (_: UnsupportedOperationException) {
            file.toFile().setExecutable(true)
        }
    }
}
