package io.github.skhokhlov.rewriterunner

import io.kotest.core.spec.style.FunSpec
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AetherContextTest :
    FunSpec({
        var tempDir: Path = Path.of("")

        beforeEach { tempDir = Files.createTempDirectory("aetherctx-") }

        afterEach { tempDir.toFile().deleteRecursively() }

        test("build uses localRepoDir as the session local repository") {
            val localRepo = tempDir.resolve("my-local-repo")
            val ctx = AetherContext.build(localRepoDir = localRepo)
            val sessionLocalRepo = ctx.session.localRepository.basePath.toAbsolutePath()
            assertEquals(localRepo.toAbsolutePath(), sessionLocalRepo)
        }

        test("build creates localRepoDir if it does not exist") {
            val localRepo = tempDir.resolve("does-not-exist-yet")
            AetherContext.build(localRepoDir = localRepo)
            assertTrue(localRepo.toFile().isDirectory, "localRepoDir should be created")
        }

        test("build attaches a transfer listener to the session for download progress logging") {
            val ctx = AetherContext.build(localRepoDir = tempDir.resolve("repo"))
            assertNotNull(
                ctx.session.transferListener,
                "Session must have a non-null transfer listener so download progress is logged"
            )
        }

        test("build configures a DependencyGraphTransformer for conflict resolution") {
            val ctx = AetherContext.build(localRepoDir = tempDir.resolve("repo"))
            assertNotNull(
                ctx.session.dependencyGraphTransformer,
                "Session must have a DependencyGraphTransformer so Maven conflict " +
                    "resolution selects one version per artifact"
            )
        }

        test("build with different localRepoDir values produces independent contexts") {
            val repoA = tempDir.resolve("repo-a")
            val repoB = tempDir.resolve("repo-b")
            val ctxA = AetherContext.build(localRepoDir = repoA)
            val ctxB = AetherContext.build(localRepoDir = repoB)
            val pathA = ctxA.session.localRepository.basePath.toAbsolutePath()
            val pathB = ctxB.session.localRepository.basePath.toAbsolutePath()
            assertEquals(repoA.toAbsolutePath(), pathA)
            assertEquals(repoB.toAbsolutePath(), pathB)
        }

        // ─── includeMavenCentral ──────────────────────────────────────────────────

        test("build by default includes Maven Central in remoteRepos") {
            val ctx = AetherContext.build(localRepoDir = tempDir.resolve("repo"))
            assertTrue(
                ctx.remoteRepos.any { it.url == "https://repo.maven.apache.org/maven2" },
                "Maven Central should be present by default"
            )
        }

        // ─── downloadThreads ──────────────────────────────────────────────────────

        test("build uses default downloadThreads of 5") {
            val ctx = AetherContext.build(localRepoDir = tempDir.resolve("repo"))
            assertEquals(
                5,
                ctx.session.configProperties["aether.connector.basic.threads"],
                "Default download threads should be 5"
            )
        }

        test("build with custom downloadThreads=10 sets the connector threads property") {
            val ctx = AetherContext.build(
                localRepoDir = tempDir.resolve("repo"),
                downloadThreads = 10
            )
            assertEquals(
                10,
                ctx.session.configProperties["aether.connector.basic.threads"],
                "Download threads should be 10"
            )
        }

        test("build with downloadThreads=1 sets single-threaded downloads") {
            val ctx = AetherContext.build(
                localRepoDir = tempDir.resolve("repo"),
                downloadThreads = 1
            )
            assertEquals(
                1,
                ctx.session.configProperties["aether.connector.basic.threads"],
                "Download threads should be 1"
            )
        }

        test("build with includeMavenCentral=false excludes Maven Central from remoteRepos") {
            val extraRepo = io.github.skhokhlov.rewriterunner.config.RepositoryConfig(
                url = "https://nexus.example.com/repository/maven-public"
            )
            val ctx = AetherContext.build(
                localRepoDir = tempDir.resolve("repo"),
                extraRepositories = listOf(extraRepo),
                includeMavenCentral = false
            )
            assertTrue(
                ctx.remoteRepos.none { it.url == "https://repo.maven.apache.org/maven2" },
                "Maven Central should be absent when includeMavenCentral=false"
            )
            assertTrue(
                ctx.remoteRepos.any {
                    it.url == "https://nexus.example.com/repository/maven-public"
                },
                "Extra repo should still be present"
            )
        }
    })
