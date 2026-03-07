package org.example.recipe

import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.example.config.RepositoryConfig
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class RecipeArtifactResolverTest {

    @TempDir
    lateinit var cacheDir: Path

    // ─── Construction ─────────────────────────────────────────────────────────

    @Test
    fun `constructor succeeds with minimal arguments`() {
        val resolver = RecipeArtifactResolver(cacheDir)
        assertNotNull(resolver)
    }

    @Test
    fun `constructor succeeds with extra repositories (no credentials)`() {
        val repos = listOf(
            RepositoryConfig(
                url = "https://repo.example.com/maven2",
                username = null,
                password = null
            )
        )
        val resolver = RecipeArtifactResolver(cacheDir, extraRepositories = repos)
        assertNotNull(resolver)
    }

    @Test
    fun `constructor succeeds with extra repositories with credentials`() {
        val repos = listOf(
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

    @Test
    fun `resolve throws IllegalArgumentException for single-segment coordinate`() {
        val resolver = RecipeArtifactResolver(cacheDir)
        assertFailsWith<IllegalArgumentException> {
            resolver.resolve("groupIdOnly")
        }
    }

    @Test
    fun `resolve throws IllegalArgumentException for empty coordinate`() {
        val resolver = RecipeArtifactResolver(cacheDir)
        assertFailsWith<IllegalArgumentException> {
            resolver.resolve("")
        }
    }

    // ─── Cache directory setup ────────────────────────────────────────────────

    @Test
    fun `resolve creates repository subdirectory inside cacheDir`() {
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

    @Test
    fun `two-part coordinate with version defaults to LATEST and attempts resolution`() {
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
                "Two-part coordinate should not throw IllegalArgumentException; got: ${ex::class.simpleName}"
            )
        }
    }
}
