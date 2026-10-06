package io.github.skhokhlov.rewriterunner.apply

import io.github.skhokhlov.rewriterunner.NoOpRunnerLogger
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.DosFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

@Tags("worker")
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

        test("renames publish updated content and remove the original") {
            projectDir.resolve("old.txt").writeText("old\n")
            val result = rewriteResult("old.txt", "old\n", "new\n", "nested/new.txt")

            val outcome = DiskChangeWriter(projectDir, NoOpRunnerLogger).apply(listOf(result))

            assertFalse(projectDir.resolve("old.txt").exists())
            assertEquals("new\n", projectDir.resolve("nested/new.txt").readText())
            assertEquals(
                listOf(AppliedChange(ChangeKind.MODIFIED, Path.of("nested", "new.txt").toString())),
                outcome.successes
            )
            assertTrue(outcome.failures.isEmpty())
            assertEquals(
                listOf("new.txt"),
                Files.list(projectDir.resolve("nested")).use {
                    it.map { path -> path.fileName.toString() }.toList()
                }
            )
        }

        if (projectDir.fileSystem.supportedFileAttributeViews().contains("posix")) {
            test("renames preserve the original file permissions") {
                val original = projectDir.resolve("old.txt")
                original.writeText("old\n")
                val permissions = PosixFilePermissions.fromString("rwxr-xr-x")
                Files.setPosixFilePermissions(original, permissions)
                val result = rewriteResult("old.txt", "old\n", "new\n", "new.txt")

                val outcome = DiskChangeWriter(projectDir, NoOpRunnerLogger).apply(listOf(result))

                assertTrue(outcome.failures.isEmpty())
                assertEquals(
                    permissions,
                    Files.getPosixFilePermissions(projectDir.resolve("new.txt"))
                )
            }
        }

        if (projectDir.fileSystem.supportedFileAttributeViews().contains("posix")) {
            for (mode in listOf("r--r--r--", "r-xr-xr-x")) {
                test("renames write new content before restoring non-writable permissions $mode") {
                    val original = projectDir.resolve("old.txt")
                    original.writeText("old\n")
                    val permissions = PosixFilePermissions.fromString(mode)
                    Files.setPosixFilePermissions(original, permissions)
                    val result = rewriteResult("old.txt", "old\n", "new\n", "new.txt")

                    val outcome = DiskChangeWriter(
                        projectDir,
                        NoOpRunnerLogger
                    ).apply(listOf(result))

                    assertTrue(outcome.failures.isEmpty())
                    assertFalse(original.exists())
                    assertEquals("new\n", projectDir.resolve("new.txt").readText())
                    assertEquals(
                        permissions,
                        Files.getPosixFilePermissions(projectDir.resolve("new.txt"))
                    )
                }
            }
        }

        test("DOS read-only sources publish updated content before reporting removal failure") {
            assumeTrue(
                System.getProperty("os.name", "").lowercase().contains("windows"),
                "Requires Windows DOS read-only file semantics"
            )
            val original = projectDir.resolve("old.txt")
            val target = projectDir.resolve("new.txt")
            original.writeText("old\n")
            val originalView = Files.getFileAttributeView(
                original,
                DosFileAttributeView::class.java
            )
            originalView.setReadOnly(true)
            try {
                val result = rewriteResult("old.txt", "old\n", "new\n", "new.txt")

                val outcome = DiskChangeWriter(
                    projectDir,
                    NoOpRunnerLogger
                ).apply(listOf(result))

                assertEquals("new\n", target.readText())
                assertTrue(
                    Files.getFileAttributeView(target, DosFileAttributeView::class.java)
                        .readAttributes().isReadOnly
                )
                assertEquals("old\n", original.readText())
                assertTrue(originalView.readAttributes().isReadOnly)
                assertTrue(outcome.successes.isEmpty())
                assertEquals(1, outcome.failures.size)
                assertEquals("new.txt", outcome.failures.single().path)
            } finally {
                originalView.setReadOnly(false)
                if (target.exists()) {
                    Files.getFileAttributeView(target, DosFileAttributeView::class.java)
                        .setReadOnly(false)
                }
            }
        }

        test("failed rename destination writes preserve the original and continue") {
            projectDir.resolve("old.txt").writeText("old\n")
            projectDir.resolve("blocked-parent").writeText("blocker\n")
            val results = listOf(
                rewriteResult("old.txt", "old\n", "new\n", "blocked-parent/new.txt"),
                rewriteResult("ok.txt", null, "ok\n")
            )

            val outcome = DiskChangeWriter(projectDir, NoOpRunnerLogger).apply(results)

            assertEquals("old\n", projectDir.resolve("old.txt").readText())
            assertEquals("blocker\n", projectDir.resolve("blocked-parent").readText())
            assertEquals(listOf(AppliedChange(ChangeKind.CREATED, "ok.txt")), outcome.successes)
            assertEquals(1, outcome.failures.size)
            assertEquals(ChangeKind.MODIFIED, outcome.failures.single().kind)
            assertEquals(
                Path.of("blocked-parent", "new.txt").toString(),
                outcome.failures.single().path
            )
            assertTrue(outcome.failed)
        }

        test("failed original removal reports failure after publishing the destination") {
            projectDir.resolve("old").createDirectories()
            projectDir.resolve("old/child.txt").writeText("child\n")
            val result = rewriteResult("old", "old\n", "new\n", "new.txt")

            val outcome = DiskChangeWriter(projectDir, NoOpRunnerLogger).apply(listOf(result))

            assertEquals("child\n", projectDir.resolve("old/child.txt").readText())
            assertEquals("new\n", projectDir.resolve("new.txt").readText())
            assertTrue(outcome.successes.isEmpty())
            assertEquals(1, outcome.failures.size)
            assertEquals("new.txt", outcome.failures.single().path)
            assertTrue(outcome.failures.single().cause.isNotBlank())
        }

        test("rename collisions preserve both files even when destination content matches") {
            for (content in listOf("occupied\n", "new\n")) {
                projectDir.resolve("old.txt").writeText("old\n")
                projectDir.resolve("new.txt").writeText(content)
                val result = rewriteResult("old.txt", "old\n", "new\n", "new.txt")

                val outcome = DiskChangeWriter(projectDir, NoOpRunnerLogger).apply(listOf(result))

                assertEquals("old\n", projectDir.resolve("old.txt").readText())
                assertEquals(content, projectDir.resolve("new.txt").readText())
                assertTrue(outcome.successes.isEmpty())
                assertEquals(1, outcome.failures.size)
            }
        }

        test("an occupied destination directory is never replaced") {
            projectDir.resolve("old.txt").writeText("old\n")
            projectDir.resolve("new.txt").createDirectories()
            val result = rewriteResult("old.txt", "old\n", "new\n", "new.txt")

            val outcome = DiskChangeWriter(projectDir, NoOpRunnerLogger).apply(listOf(result))

            assertEquals("old\n", projectDir.resolve("old.txt").readText())
            assertTrue(Files.isDirectory(projectDir.resolve("new.txt")))
            assertTrue(outcome.successes.isEmpty())
            assertEquals(1, outcome.failures.size)
        }

        if (!System.getProperty("os.name", "").lowercase().contains("windows")) {
            test("a dangling destination symlink is a collision and is preserved") {
                projectDir.resolve("old.txt").writeText("old\n")
                Files.createSymbolicLink(projectDir.resolve("new.txt"), Path.of("missing.txt"))
                val result = rewriteResult("old.txt", "old\n", "new\n", "new.txt")

                val outcome = DiskChangeWriter(projectDir, NoOpRunnerLogger).apply(listOf(result))

                assertEquals("old\n", projectDir.resolve("old.txt").readText())
                assertEquals(
                    Path.of("missing.txt"),
                    Files.readSymbolicLink(projectDir.resolve("new.txt"))
                )
                assertFalse(projectDir.resolve("missing.txt").exists())
                assertTrue(outcome.successes.isEmpty())
                assertEquals(1, outcome.failures.size)
            }
        }

        test("case-only moves fail without modifying the original on every filesystem") {
            projectDir.resolve("Old.txt").writeText("old\n")
            val result = rewriteResult("Old.txt", "old\n", "new\n", "old.txt")

            val outcome = DiskChangeWriter(projectDir, NoOpRunnerLogger).apply(listOf(result))

            assertEquals("old\n", projectDir.resolve("Old.txt").readText())
            assertTrue(outcome.successes.isEmpty())
            assertTrue(outcome.failures.single().cause.contains("Case-only"))
            assertEquals(
                listOf("Old.txt"),
                Files.list(projectDir).use {
                    it.map { path -> path.fileName.toString() }.toList()
                }
            )
        }

        test("equivalent normalized paths modify content without deleting the file") {
            projectDir.resolve("same.txt").writeText("old\n")
            val result = rewriteResult("./same.txt", "old\n", "new\n", "same.txt")

            val outcome = DiskChangeWriter(projectDir, NoOpRunnerLogger).apply(listOf(result))

            assertEquals("new\n", projectDir.resolve("same.txt").readText())
            assertEquals(listOf(AppliedChange(ChangeKind.MODIFIED, "same.txt")), outcome.successes)
            assertTrue(outcome.failures.isEmpty())
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
            assertEquals(Path.of("blocked-parent", "new.txt").toString(), outcome.failures[0].path)
            assertTrue(outcome.failures[0].cause.isNotBlank())
            assertEquals(ChangeKind.DELETED, outcome.failures[1].kind)
            assertEquals("non-empty-dir", outcome.failures[1].path)
            assertTrue(outcome.failures[1].cause.isNotBlank())
            assertTrue(outcome.failed)
        }
    })
