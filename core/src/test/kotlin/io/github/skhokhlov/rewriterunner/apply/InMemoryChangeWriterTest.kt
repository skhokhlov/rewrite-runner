package io.github.skhokhlov.rewriterunner.apply

import io.kotest.core.spec.style.FunSpec
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InMemoryChangeWriterTest :
    FunSpec({
        test("records successes with change kind and path") {
            val writer = InMemoryChangeWriter()
            val create = rewriteResult("created.txt", before = null, after = "new\n")
            val modify = rewriteResult("modified.txt", before = "old\n", after = "new\n")
            val delete = rewriteResult("deleted.txt", before = "gone\n", after = null)

            val outcome = writer.apply(listOf(create, modify, delete))

            assertEquals(
                listOf(
                    AppliedChange(ChangeKind.CREATED, "created.txt"),
                    AppliedChange(ChangeKind.MODIFIED, "modified.txt"),
                    AppliedChange(ChangeKind.DELETED, "deleted.txt")
                ),
                outcome.successes
            )
            assertTrue(outcome.failures.isEmpty())
            assertEquals(listOf(create, modify, delete), writer.recordedResults)
        }

        test("configured failPaths become failures and remaining results still succeed") {
            val writer = InMemoryChangeWriter(failPaths = setOf("bad.txt"))
            val bad = rewriteResult("bad.txt", before = "old\n", after = "new\n")
            val good = rewriteResult("good.txt", before = "old\n", after = "new\n")

            val outcome = writer.apply(listOf(bad, good))

            assertEquals(
                listOf(ApplyFailure(ChangeKind.MODIFIED, "bad.txt", "configured failure")),
                outcome.failures
            )
            assertEquals(listOf(AppliedChange(ChangeKind.MODIFIED, "good.txt")), outcome.successes)
            assertTrue(outcome.failed)
        }
    })
