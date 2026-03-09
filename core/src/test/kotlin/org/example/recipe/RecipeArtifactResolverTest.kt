package org.example.recipe

import io.kotest.core.spec.style.FunSpec
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarOutputStream
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.eclipse.aether.repository.RemoteRepository
import org.example.config.RepositoryConfig

class RecipeArtifactResolverTest :
    FunSpec({
        var cacheDir: Path = Path.of("")

        beforeEach { cacheDir = Files.createTempDirectory("rart-") }

        afterEach { cacheDir.toFile().deleteRecursively() }

        // ─── Construction ─────────────────────────────────────────────────────────

        test("constructor succeeds with minimal arguments") {
            val resolver = RecipeArtifactResolver(cacheDir)
            assertNotNull(resolver)
        }

        test("constructor succeeds with extra repositories (no credentials)") {
            val repos =
                listOf(
                    RepositoryConfig(
                        url = "https://repo.example.com/maven2",
                        username = null,
                        password = null
                    )
                )
            val resolver = RecipeArtifactResolver(cacheDir, extraRepositories = repos)
            assertNotNull(resolver)
        }

        test("constructor succeeds with extra repositories with credentials") {
            val repos =
                listOf(
                    RepositoryConfig(
                        url = "https://private.example.com/maven2",
                        username = "user",
                        password = "secret"
                    )
                )
            val resolver = RecipeArtifactResolver(cacheDir, extraRepositories = repos)
            assertNotNull(resolver)
        }

        // ─── Coordinate validation ────────────────────────────────────────────────

        test("resolve throws IllegalArgumentException for single-segment coordinate") {
            val resolver = RecipeArtifactResolver(cacheDir)
            assertFailsWith<IllegalArgumentException> { resolver.resolve("groupIdOnly") }
        }

        test("resolve throws IllegalArgumentException for empty coordinate") {
            val resolver = RecipeArtifactResolver(cacheDir)
            assertFailsWith<IllegalArgumentException> { resolver.resolve("") }
        }

        // ─── Session initialization ───────────────────────────────────────────────

        test(
            "session initialization does not throw IllegalStateException about missing local repository"
        ) {
            // Regression test: Maven Resolver 2.x SessionBuilder requires withLocalRepositories()
            // rather than a bootstrap-then-createLocalRepositoryManager approach.
            // The bug manifested as: "No local repository manager or local repositories set on session"
            val resolver = RecipeArtifactResolver(cacheDir)
            val ex =
                runCatching {
                    resolver.resolve("org.example:nonexistent-artifact-xyz:99.99.99")
                }.exceptionOrNull()

            if (ex is IllegalStateException) {
                assertFalse(
                    ex.message?.contains("local repositor", ignoreCase = true) == true,
                    "Session init must not fail with: ${ex.message}"
                )
            }
        }

        test(
            "resolve succeeds for locally cached artifact whose POM has JDK-version profile activations"
        ) {
            // Regression test: without System.getProperties() on the session, Maven Resolver
            // model builder cannot evaluate profiles like <jdk>[9,)</jdk> and throws:
            // "Failed to determine Java version for profile jdk9plus"
            //
            // This test pre-populates the local Maven cache (cacheDir/repository) with a
            // minimal POM that contains a <jdk> profile — no network access required.
            val groupId = "test.example"
            val artifactId = "profile-jdk-test"
            val version = "1.0.0"

            val pomContent =
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>$groupId</groupId>
                  <artifactId>$artifactId</artifactId>
                  <version>$version</version>
                  <packaging>jar</packaging>
                  <profiles>
                    <profile>
                      <id>jdk9plus</id>
                      <activation><jdk>[9,)</jdk></activation>
                    </profile>
                    <profile>
                      <id>jdk17plus</id>
                      <activation><jdk>[17,)</jdk></activation>
                    </profile>
                  </profiles>
                </project>
                """.trimIndent()

            // Pre-populate the local Maven repository inside cacheDir
            val artifactDir =
                cacheDir
                    .resolve("repository")
                    .resolve(groupId.replace('.', '/'))
                    .resolve(artifactId)
                    .resolve(version)
            Files.createDirectories(artifactDir)

            val pomName = "$artifactId-$version.pom"
            val jarName = "$artifactId-$version.jar"
            artifactDir.resolve(pomName).toFile().writeText(pomContent)
            JarOutputStream(artifactDir.resolve(jarName).toFile().outputStream()).close()
            // _remote.repositories marks these files as originating from "central" so that
            // EnhancedLocalRepositoryManager doesn't attempt to re-download them.
            artifactDir
                .resolve("_remote.repositories")
                .toFile()
                .writeText("$pomName>central=\n$jarName>central=\n")

            val resolver = RecipeArtifactResolver(cacheDir)
            val result = runCatching { resolver.resolve("$groupId:$artifactId:$version") }

            val ex = result.exceptionOrNull()
            if (ex != null) {
                // Walk the full exception chain (including suppressed) to detect the bug
                fun collectMessages(t: Throwable): String = buildString {
                    appendLine("${t::class.simpleName}: ${t.message}")
                    t.suppressed.forEach { appendLine(collectMessages(it)) }
                    t.cause?.let { appendLine(collectMessages(it)) }
                }
                val fullMessage = collectMessages(ex)
                assertFalse(
                    fullMessage.contains("Failed to determine Java version", ignoreCase = true),
                    "Resolution must not fail with profile evaluation error.\nFull chain:\n$fullMessage"
                )
            }
        }

        // ─── Cache directory setup ────────────────────────────────────────────────

        test("resolve creates repository subdirectory inside cacheDir") {
            val resolver = RecipeArtifactResolver(cacheDir)
            // Trigger lazy initialization by calling resolve (it will fail on network but
            // the local repository directory is created before the network call)
            runCatching {
                resolver.resolve("org.example:nonexistent-artifact-xyz:99.99.99")
            }
            // The session setup creates cacheDir/repository
            val repoDir = cacheDir.resolve("repository").toFile()
            assertTrue(
                repoDir.exists(),
                "Cache repository directory should be created during session init"
            )
        }

        // ─── Timeout behaviour ────────────────────────────────────────────────────

        test(
            "resolve completes within requestTimeoutMs when remote server accepts but never responds"
        ) {
            // Regression test: without an explicit REQUEST_TIMEOUT the Maven Resolver
            // default is 30 minutes, causing the process to hang indefinitely when a
            // remote repository accepts TCP connections but never sends an HTTP response.
            val blackHole = ServerSocket(0) // bind on any free port
            val port = blackHole.localPort
            // Accept the connection but never respond, simulating a hung server
            Thread {
                try {
                    val conn = blackHole.accept()
                    Thread.sleep(60_000)
                    conn.close()
                } catch (_: Exception) {}
            }.also { it.isDaemon = true }.start()

            // Subclass routes ONLY to the black-hole server so network access to
            // Maven Central cannot mask a missing timeout configuration.
            val resolver =
                object : RecipeArtifactResolver(cacheDir, requestTimeoutMs = 2_000) {
                    override fun buildRemoteRepos() = listOf(
                        RemoteRepository.Builder(
                            "blackhole",
                            "default",
                            "http://127.0.0.1:$port"
                        )
                            .build()
                    )
                }

            val startMs = System.currentTimeMillis()
            runCatching { resolver.resolve("test:missing-artifact:1.0.0") }
            val elapsedMs = System.currentTimeMillis() - startMs

            blackHole.close()
            assertTrue(
                elapsedMs < 10_000,
                "resolve() must honour requestTimeoutMs and complete in <10 s; took ${elapsedMs}ms"
            )
        }

        test("two-part coordinate with version defaults to LATEST and attempts resolution") {
            // Coordinate with only groupId:artifactId should default to LATEST.
            // We can't guarantee network access in all environments, so we only verify
            // the code path is entered (i.e. the call starts, may fail gracefully or succeed).
            val resolver = RecipeArtifactResolver(cacheDir)
            val result = runCatching {
                resolver.resolve("com.example.nonexistent:artifact-that-does-not-exist")
            }
            // Either succeeds (unlikely) or throws an exception from the resolver —
            // what we care about is that the version defaulted to LATEST without IllegalArgumentException.
            val ex = result.exceptionOrNull()
            if (ex != null) {
                assertTrue(
                    ex !is IllegalArgumentException,
                    "Two-part coordinate should not throw IllegalArgumentException; " +
                        "got: ${ex::class.simpleName}"
                )
            }
        }
    })
