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
    })
