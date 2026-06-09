package io.github.skhokhlov.rewriterunner.apply

import io.github.skhokhlov.rewriterunner.NoOpRunnerLogger
import io.kotest.core.spec.style.FunSpec
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiskChangeWriterTest :
    FunSpec({
        var projectDir: Path = Path.of("")

        beforeEach { projectDir = Files.createTempDirectory("dcwt-") }

        afterEach { projectDir.toFile().deleteRecursively() }

        test("applies create modify and delete results to disk") {
            projectDir.resolve("modified.txt").writeText("old\n")
            projectDir.resolve("deleted.txt").writeText("gone\n")
            val results =
                listOf(
                    rewriteResult("created.txt", before = null, after = "created\n"),
                    rewriteResult("modified.txt", before = "old\n", after = "modified\n"),
                    rewriteResult("deleted.txt", before = "gone\n", after = null)
                )

            val outcome = DiskChangeWriter(projectDir, NoOpRunnerLogger).apply(results)

            assertEquals("created\n", projectDir.resolve("created.txt").readText())
            assertEquals("modified\n", projectDir.resolve("modified.txt").readText())
            assertFalse(projectDir.resolve("deleted.txt").exists())
            assertEquals(
                listOf(
                    AppliedChange(ChangeKind.CREATED, "created.txt"),
                    AppliedChange(ChangeKind.MODIFIED, "modified.txt"),
                    AppliedChange(ChangeKind.DELETED, "deleted.txt")
                ),
                outcome.successes
            )
            assertTrue(outcome.failures.isEmpty())
            assertEquals(
                listOf("created.txt", "modified.txt"),
                outcome.successes.filter { it.kind != ChangeKind.DELETED }.map { it.path }
            )
        }

        test("collects write and delete failures and continues applying remaining results") {
            projectDir.resolve("blocked-parent").writeText("not a directory\n")
            projectDir.resolve("non-empty-dir").createDirectories()
            projectDir.resolve("non-empty-dir/child.txt").writeText("child\n")
            val results =
                listOf(
                    rewriteResult("blocked-parent/new.txt", before = null, after = "new\n"),
                    rewriteResult("non-empty-dir", before = "old\n", after = null),
                    rewriteResult("ok.txt", before = null, after = "ok\n")
                )

            val outcome = DiskChangeWriter(projectDir, NoOpRunnerLogger).apply(results)

            assertEquals("ok\n", projectDir.resolve("ok.txt").readText())
            assertTrue(projectDir.resolve("non-empty-dir").exists())
            assertEquals(listOf(AppliedChange(ChangeKind.CREATED, "ok.txt")), outcome.successes)
            assertEquals(2, outcome.failures.size)
            assertEquals(ChangeKind.CREATED, outcome.failures[0].kind)
            assertEquals("blocked-parent/new.txt", outcome.failures[0].path)
            assertTrue(outcome.failures[0].cause.isNotBlank())
            assertEquals(ChangeKind.DELETED, outcome.failures[1].kind)
            assertEquals("non-empty-dir", outcome.failures[1].path)
            assertTrue(outcome.failures[1].cause.isNotBlank())
            assertTrue(outcome.failed)
        }
    })
