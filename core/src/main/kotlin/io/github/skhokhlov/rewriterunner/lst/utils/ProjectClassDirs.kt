package io.github.skhokhlov.rewriterunner.lst.utils

import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.EnumSet

/**
 * Finds directories where the project's own compiled classes might live, including subprojects.
 */
internal fun projectClassDirs(projectDir: Path): List<Path> {
    val classDirSuffixes = listOf(
        "target/classes",
        "target/test-classes",
        "build/classes/java/main",
        "build/classes/java/test",
        "build/classes/kotlin/main",
        "build/classes/kotlin/test"
    )
    val discovered = linkedSetOf<Path>()

    fun isHiddenRelativePath(path: Path): Boolean = path.any { segment ->
        segment.toString().startsWith(".")
    }

    fun maybeAdd(dir: Path) {
        if (!Files.isDirectory(dir)) return
        val relative = projectDir.relativize(dir)
        if (isHiddenRelativePath(relative)) return
        discovered.add(dir)
    }

    classDirSuffixes.forEach { suffix -> maybeAdd(projectDir.resolve(suffix)) }

    try {
        Files.walkFileTree(
            projectDir,
            EnumSet.noneOf(FileVisitOption::class.java),
            10,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(
                    dir: Path,
                    attrs: BasicFileAttributes
                ): FileVisitResult {
                    val relative = projectDir.relativize(dir)
                    if (isHiddenRelativePath(relative)) return FileVisitResult.SKIP_SUBTREE
                    val relativeText = relative.toString().replace('\\', '/')
                    if (classDirSuffixes.any { suffix -> relativeText.endsWith(suffix) }) {
                        discovered.add(dir)
                    }
                    return FileVisitResult.CONTINUE
                }
            }
        )
    } catch (_: Exception) {
        // Ignore errors while walking subdirectories.
    }

    return discovered.toList()
}
