package io.github.skhokhlov.rewriterunner.plugin

import io.kotest.core.spec.style.FunSpec
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryFlag
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Runs in the Windows worker job as well as on POSIX, where the two permission mechanisms differ. */
class PluginTempFilesTest :
    FunSpec({
        test("private transport directories and files are owner-only") {
            val directory = createPrivateTempDirectory("rewrite-runner-private-test-")
            val standaloneFile = createPrivateTempFile("rewrite-runner-private-test-", ".json")
            val requestFile = createPrivateTempFile(directory, "request-", ".json")
            try {
                Files.writeString(requestFile, "repository credentials")

                assertOwnerOnly(directory, directory = true)
                assertOwnerOnly(standaloneFile, directory = false)
                assertOwnerOnly(requestFile, directory = false)
            } finally {
                deleteRecursively(directory)
                Files.deleteIfExists(standaloneFile)
            }
        }
    })

private fun assertOwnerOnly(path: Path, directory: Boolean) {
    if ("posix" in FileSystems.getDefault().supportedFileAttributeViews()) {
        val expected = if (directory) {
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE
            )
        } else {
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        }
        assertEquals(expected, Files.getPosixFilePermissions(path))
        return
    }

    val aclView = assertNotNull(
        Files.getFileAttributeView(path, AclFileAttributeView::class.java),
        "Windows transport paths must expose an ACL view"
    )
    val owner = Files.getOwner(path)
    val grantedAccess = aclView.acl.filter { entry ->
        entry.type() == AclEntryType.ALLOW &&
            entry.permissions().intersect(AclEntryPermission.values().toSet()).isNotEmpty()
    }
    assertEquals(1, grantedAccess.size, "Only the owner may receive transport-path access")
    val ownerEntry: AclEntry = grantedAccess.single()
    assertEquals(owner, ownerEntry.principal())
    assertTrue(ownerEntry.permissions().containsAll(AclEntryPermission.values().toSet()))
    if (directory) {
        assertTrue(ownerEntry.flags().contains(AclEntryFlag.FILE_INHERIT))
        assertTrue(ownerEntry.flags().contains(AclEntryFlag.DIRECTORY_INHERIT))
    }
}
