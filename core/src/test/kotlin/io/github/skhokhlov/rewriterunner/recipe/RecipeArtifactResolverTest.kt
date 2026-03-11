package io.github.skhokhlov.rewriterunner.recipe

import io.github.skhokhlov.rewriterunner.AetherContext
import io.github.skhokhlov.rewriterunner.config.RepositoryConfig
import io.kotest.core.spec.style.FunSpec
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarOutputStream
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.eclipse.aether.ConfigurationProperties
import org.eclipse.aether.repository.LocalRepository
import org.eclipse.aether.repository.RemoteRepository
import org.eclipse.aether.supplier.RepositorySystemSupplier

class RecipeArtifactResolverTest :
    FunSpec({
        var cacheDir: Path = Path.of("")

        beforeEach { cacheDir = Files.createTempDirectory("rart-") }

        afterEach { cacheDir.toFile().deleteRecursively() }

        // ─── Construction ─────────────────────────────────────────────────────────

        test("constructor succeeds with minimal arguments") {
            val resolver =
                RecipeArtifactResolver(AetherContext.build(cacheDir.resolve("repository")))
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
            val resolver =
                RecipeArtifactResolver(
                    AetherContext.build(cacheDir.resolve("repository"), extraRepositories = repos)
                )
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
            val resolver =
                RecipeArtifactResolver(
                    AetherContext.build(cacheDir.resolve("repository"), extraRepositories = repos)
                )
            assertNotNull(resolver)
        }

        // ─── Coordinate validation ────────────────────────────────────────────────

        test("resolve throws IllegalArgumentException for single-segment coordinate") {
            val resolver =
                RecipeArtifactResolver(AetherContext.build(cacheDir.resolve("repository")))
            assertFailsWith<IllegalArgumentException> { resolver.resolve("groupIdOnly") }
        }

        test("resolve throws IllegalArgumentException for empty coordinate") {
            val resolver =
                RecipeArtifactResolver(AetherContext.build(cacheDir.resolve("repository")))
            assertFailsWith<IllegalArgumentException> { resolver.resolve("") }
        }

        // ─── Session initialization ───────────────────────────────────────────────

        test(
            "session initialization does not throw IllegalStateException about missing local repository"
        ) {
            // Regression test: Maven Resolver 2.x SessionBuilder requires withLocalRepositories()
            // rather than a bootstrap-then-createLocalRepositoryManager approach.
            // The bug manifested as: "No local repository manager or local repositories set on session"
            val resolver =
                RecipeArtifactResolver(AetherContext.build(cacheDir.resolve("repository")))
            val ex =
                runCatching {
                    resolver.resolve(
                        "io.github.skhokhlov.rewriterunner:nonexistent-artifact-xyz:99.99.99"
                    )
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

            val resolver =
                RecipeArtifactResolver(AetherContext.build(cacheDir.resolve("repository")))
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
            val resolver =
                RecipeArtifactResolver(AetherContext.build(cacheDir.resolve("repository")))
            // Trigger lazy initialization by calling resolve (it will fail on network but
            // the local repository directory is created before the network call)
            runCatching {
                resolver.resolve(
                    "io.github.skhokhlov.rewriterunner:nonexistent-artifact-xyz:99.99.99"
                )
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

            // Pass a custom AetherContext routing ONLY to the black-hole server so network
            // access to Maven Central cannot mask a missing timeout configuration.
            val system = RepositorySystemSupplier().get()
            val repoDir = cacheDir.resolve("repository").also { it.toFile().mkdirs() }
            val session =
                system
                    .createSessionBuilder()
                    .withLocalRepositories(org.eclipse.aether.repository.LocalRepository(repoDir))
                    .setSystemProperties(System.getProperties())
                    .setConfigProperty(ConfigurationProperties.CONNECT_TIMEOUT, 2_000)
                    .setConfigProperty(ConfigurationProperties.REQUEST_TIMEOUT, 2_000)
                    .setConfigProperty(
                        "aether.remoteRepositoryFilter.prefixes.resolvePrefixFiles",
                        false
                    )
                    .setIgnoreArtifactDescriptorRepositories(true)
                    .build()
            val blackHoleRepo = listOf(
                RemoteRepository.Builder("blackhole", "default", "http://127.0.0.1:$port").build()
            )
            val resolver = RecipeArtifactResolver(AetherContext(system, session, blackHoleRepo))

            val startMs = System.currentTimeMillis()
            runCatching { resolver.resolve("test:missing-artifact:1.0.0") }
            val elapsedMs = System.currentTimeMillis() - startMs

            blackHole.close()
            assertTrue(
                elapsedMs < 10_000,
                "resolve() must honour requestTimeoutMs and complete in <10 s; took ${elapsedMs}ms"
            )
        }

        test(
            "resolve returns partial results instead of throwing when some transitive deps are unavailable"
        ) {
            // Regression test: when DependencyResolutionException is thrown (e.g. because a
            // transitive dependency points to a private repo artifact like a Red Hat patch),
            // resolve() must return whatever JARs were successfully resolved instead of
            // crashing the tool with an "Unhandled exception" message.
            //
            // Setup: root artifact pre-cached locally; its transitive dep is unavailable
            // (the only remote is a black-hole server that never responds).
            val rootGroupId = "test.partial"
            val rootArtifactId = "root-with-missing-dep"
            val rootVersion = "1.0.0"
            val missingArtifactId = "unavailable-transitive"

            val pomContent =
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>$rootGroupId</groupId>
                  <artifactId>$rootArtifactId</artifactId>
                  <version>$rootVersion</version>
                  <packaging>jar</packaging>
                  <dependencies>
                    <dependency>
                      <groupId>$rootGroupId</groupId>
                      <artifactId>$missingArtifactId</artifactId>
                      <version>1.0.0</version>
                      <scope>runtime</scope>
                    </dependency>
                  </dependencies>
                </project>
                """.trimIndent()

            val artifactDir =
                cacheDir
                    .resolve(
                        "repository/${rootGroupId.replace('.', '/')}/$rootArtifactId/$rootVersion"
                    )
            Files.createDirectories(artifactDir)
            val pomName = "$rootArtifactId-$rootVersion.pom"
            val jarName = "$rootArtifactId-$rootVersion.jar"
            artifactDir.resolve(pomName).toFile().writeText(pomContent)
            JarOutputStream(artifactDir.resolve(jarName).toFile().outputStream()).close()
            // Use "simple" local repo type so Maven Resolver reads cached files directly
            // without verifying their provenance against a remote repository.

            val blackHole = ServerSocket(0)
            val port = blackHole.localPort
            Thread {
                try {
                    val conn = blackHole.accept()
                    Thread.sleep(30_000)
                    conn.close()
                } catch (_: Exception) {}
            }
                .also { it.isDaemon = true }
                .start()

            // Build a custom AetherContext with a "simple" local repository so the pre-cached
            // root artifact is used as-is, and only the missing transitive dep hits the black-hole.
            val system2 = RepositorySystemSupplier().get()
            val repoDir2 = cacheDir.resolve("repository").also { it.toFile().mkdirs() }
            // "simple" type: reads cached files without _remote.repositories checks
            val localRepo2 = LocalRepository(repoDir2.toFile(), "simple")
            val session2 =
                system2
                    .createSessionBuilder()
                    .withLocalRepositories(localRepo2)
                    .setSystemProperties(System.getProperties())
                    .setConfigProperty(ConfigurationProperties.CONNECT_TIMEOUT, 2_000)
                    .setConfigProperty(ConfigurationProperties.REQUEST_TIMEOUT, 2_000)
                    .build()
            val blackHoleRepo2 = listOf(
                RemoteRepository.Builder("blackhole", "default", "http://127.0.0.1:$port").build()
            )
            val resolver = RecipeArtifactResolver(AetherContext(system2, session2, blackHoleRepo2))

            // Should NOT throw — partial results (root artifact) are returned
            val paths = resolver.resolve("$rootGroupId:$rootArtifactId:$rootVersion")

            blackHole.close()

            assertTrue(
                paths.isNotEmpty(),
                "resolve() must return partial results when some transitive deps are unavailable"
            )
            assertTrue(
                paths.any { it.fileName.toString() == jarName },
                "Resolved paths should include root artifact JAR; got: $paths"
            )
        }

        test("two-part coordinate with version defaults to LATEST and attempts resolution") {
            // Coordinate with only groupId:artifactId should default to LATEST.
            // We can't guarantee network access in all environments, so we only verify
            // the code path is entered (i.e. the call starts, may fail gracefully or succeed).
            val resolver =
                RecipeArtifactResolver(AetherContext.build(cacheDir.resolve("repository")))
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
