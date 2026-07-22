package io.github.skhokhlov.rewriterunner.plugin

import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryFlag
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

internal fun createPrivateTempFile(prefix: String, suffix: String): Path = secureCreatedPath(
    Files.createTempFile(prefix, suffix, *privateTempFileAttributes()),
    directory = false
)

internal fun createPrivateTempFile(directory: Path, prefix: String, suffix: String): Path =
    secureCreatedPath(
        Files.createTempFile(directory, prefix, suffix, *privateTempFileAttributes()),
        directory = false
    )

internal fun createPrivateTempDirectory(prefix: String): Path = secureCreatedPath(
    Files.createTempDirectory(prefix, *privateTempDirectoryAttributes()),
    directory = true
)

internal fun deleteRecursively(path: Path) {
    if (!Files.exists(path)) return
    Files.walk(path).use { stream ->
        stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }
}

internal fun createRewriteConfigFile(content: String?): Path? {
    if (content == null) return null
    val configFile = createPrivateTempFile("rewrite-runner-config-", ".yml")
    configFile.toFile().writeText(content, Charsets.UTF_8)
    return configFile
}

internal fun pluginFailureMessage(action: String, exitCode: Int?): String = if (exitCode == null) {
    "$action did not start or timed out"
} else {
    "$action exited with $exitCode"
}

private fun privateTempFileAttributes(): Array<FileAttribute<*>> =
    if ("posix" in FileSystems.getDefault().supportedFileAttributeViews()) {
        arrayOf<FileAttribute<*>>(
            PosixFilePermissions.asFileAttribute(privateTempFilePermissions)
        )
    } else {
        emptyArray()
    }

private fun privateTempDirectoryAttributes(): Array<FileAttribute<*>> =
    if ("posix" in FileSystems.getDefault().supportedFileAttributeViews()) {
        arrayOf<FileAttribute<*>>(
            PosixFilePermissions.asFileAttribute(privateTempDirectoryPermissions)
        )
    } else {
        emptyArray()
    }

/**
 * Restricts temporary transport paths after creation as well as at creation time. POSIX creation
 * attributes avoid a permissive creation mode; the explicit set also protects providers that do
 * not honor the supplied attribute. Windows has no POSIX view, so replace the ACL with one owner
 * entry and make that entry inheritable for worker-created request/response files.
 */
private fun secureCreatedPath(path: Path, directory: Boolean): Path = try {
    restrictToOwner(path, directory)
    path.toFile().deleteOnExit()
    path
} catch (failure: Exception) {
    runCatching {
        if (directory) deleteRecursively(path) else Files.deleteIfExists(path)
    }
    throw failure
}

private fun restrictToOwner(path: Path, directory: Boolean) {
    if ("posix" in FileSystems.getDefault().supportedFileAttributeViews()) {
        Files.setPosixFilePermissions(
            path,
            if (directory) privateTempDirectoryPermissions else privateTempFilePermissions
        )
        return
    }

    val aclView = Files.getFileAttributeView(path, AclFileAttributeView::class.java)
        ?: throw IOException("Filesystem does not support owner-only ACLs for private temp paths")
    val flags = if (directory) {
        setOf(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT)
    } else {
        emptySet()
    }
    val ownerEntry = AclEntry.newBuilder()
        .setType(AclEntryType.ALLOW)
        .setPrincipal(Files.getOwner(path))
        .setPermissions(AclEntryPermission.values().toSet())
        .setFlags(flags)
        .build()
    aclView.setAcl(listOf(ownerEntry))
}

private val privateTempFilePermissions = setOf(
    PosixFilePermission.OWNER_READ,
    PosixFilePermission.OWNER_WRITE
)

private val privateTempDirectoryPermissions = privateTempFilePermissions +
    PosixFilePermission.OWNER_EXECUTE
