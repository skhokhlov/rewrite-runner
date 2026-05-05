package io.github.skhokhlov.rewriterunner.plugin

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

internal fun createPrivateTempFile(prefix: String, suffix: String): Path =
    Files.createTempFile(prefix, suffix, *privateTempFileAttributes()).also {
        it.toFile().deleteOnExit()
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
            PosixFilePermissions.asFileAttribute(
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE
                )
            )
        )
    } else {
        emptyArray()
    }
