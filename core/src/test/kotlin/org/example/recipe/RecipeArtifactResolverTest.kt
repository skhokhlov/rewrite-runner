package org.example.recipe

import io.kotest.core.spec.style.FunSpec
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
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
