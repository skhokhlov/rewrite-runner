package io.github.skhokhlov.rewriterunner

import io.kotest.core.spec.style.FunSpec
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.eclipse.aether.repository.RepositoryPolicy
import org.eclipse.aether.util.graph.selector.AndDependencySelector

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

        // ─── dependency selector ──────────────────────────────────────────────────

        test("session without excludeScopesFromGraph uses AndDependencySelector") {
            val ctx = AetherContext.build(localRepoDir = tempDir.resolve("repo"))
            val selector = ctx.session.dependencySelector
            assertNotNull(selector, "Session must have a dependency selector")
            assertTrue(
                selector is AndDependencySelector,
                "Selector must be an AndDependencySelector"
            )
        }

        test("session without excludeScopesFromGraph does not prune test-scoped transitive deps") {
            val ctx = AetherContext.build(localRepoDir = tempDir.resolve("repo"))
            val selectorStr = ctx.session.dependencySelector.toString()
            assertFalse(
                selectorStr.contains("ScopeDependency"),
                "Default context (project) should not have a ScopeDependencySelector: $selectorStr"
            )
        }

        test("session with excludeScopesFromGraph includes ScopeDependencySelector in chain") {
            val ctx = AetherContext.build(
                localRepoDir = tempDir.resolve("repo"),
                excludeScopesFromGraph = listOf("test", "provided", "system")
            )
            val selectorStr = ctx.session.dependencySelector.toString()
            assertTrue(
                selectorStr.contains("test") && selectorStr.contains("provided"),
                "Recipe context should have ScopeDependencySelector excluding test/provided: $selectorStr"
            )
        }

        // ─── repository trust policy ──────────────────────────────────────────────

        test(
            "Maven Central repository uses UPDATE_POLICY_DAILY to limit redundant remote checks"
        ) {
            val ctx = AetherContext.build(localRepoDir = tempDir.resolve("repo"))
            val central = ctx.remoteRepos.first { it.url == "https://repo.maven.apache.org/maven2" }
            assertEquals(
                RepositoryPolicy.UPDATE_POLICY_DAILY,
                central.getPolicy(false).updatePolicy,
                "Maven Central should use UPDATE_POLICY_DAILY"
            )
        }

        test(
            "Maven Central repository uses CHECKSUM_POLICY_IGNORE to trust artifacts without checksums"
        ) {
            val ctx = AetherContext.build(localRepoDir = tempDir.resolve("repo"))
            val central = ctx.remoteRepos.first { it.url == "https://repo.maven.apache.org/maven2" }
            assertEquals(
                RepositoryPolicy.CHECKSUM_POLICY_IGNORE,
                central.getPolicy(false).checksumPolicy,
                "Maven Central should use CHECKSUM_POLICY_IGNORE"
            )
        }

        test("extra repository also uses UPDATE_POLICY_DAILY and CHECKSUM_POLICY_IGNORE") {
            val ctx = AetherContext.build(
                localRepoDir = tempDir.resolve("repo"),
                extraRepositories = listOf(
                    io.github.skhokhlov.rewriterunner.config.RepositoryConfig(
                        url = "https://private.example.com/maven2"
                    )
                )
            )
            val extra = ctx.remoteRepos.first { it.url == "https://private.example.com/maven2" }
            assertEquals(RepositoryPolicy.UPDATE_POLICY_DAILY, extra.getPolicy(false).updatePolicy)
            assertEquals(
                RepositoryPolicy.CHECKSUM_POLICY_IGNORE,
                extra.getPolicy(false).checksumPolicy
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
