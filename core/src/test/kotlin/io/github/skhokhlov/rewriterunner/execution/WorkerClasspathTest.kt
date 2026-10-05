package io.github.skhokhlov.rewriterunner.execution

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals

@Tags("worker")
class WorkerClasspathTest :
    FunSpec({
        val coordinator = Path.of("").toAbsolutePath().resolve("coordinator with spaces")

        test("mixed relative JARs and class directories preserve entry order and duplicates") {
            val absoluteJar = coordinator.resolve("absolute library.jar").toString()
            val entries = listOf("cli.jar", absoluteJar, "build/classes with spaces", "cli.jar")
            assertEquals(
                listOf(
                    coordinator.resolve("cli.jar").toString(),
                    absoluteJar,
                    coordinator.resolve("build/classes with spaces").toString(),
                    coordinator.resolve("cli.jar").toString()
                ).joinToString(File.pathSeparator),
                resolveWorkerClasspath(entries.joinToString(File.pathSeparator), coordinator)
            )
        }

        test("leading trailing and consecutive empty entries retain the coordinator directory") {
            assertEquals(
                listOf(coordinator, coordinator.resolve("classes"), coordinator, coordinator)
                    .joinToString(File.pathSeparator),
                resolveWorkerClasspath(
                    listOf("", "classes", "", "").joinToString(File.pathSeparator),
                    coordinator
                )
            )
            assertEquals(coordinator.toString(), resolveWorkerClasspath("", coordinator))
        }

        test(
            "relative and bare wildcards stay wildcards and absolute wildcard entries stay intact"
        ) {
            val absoluteWildcard = "${coordinator.resolve("absolute libs")}${File.separator}*"
            assertEquals(
                listOf(
                    "${coordinator.resolve("relative libs")}${File.separator}*",
                    "$coordinator${File.separator}*",
                    absoluteWildcard
                ).joinToString(File.pathSeparator),
                resolveWorkerClasspath(
                    listOf(
                        "relative libs/*",
                        "*",
                        absoluteWildcard
                    ).joinToString(File.pathSeparator),
                    coordinator
                )
            )
            assertEquals(
                "${coordinator.resolve("native libs")}${File.separator}*",
                resolveWorkerClasspath("native libs${File.separator}*", coordinator)
            )
        }

        test("dot and parent components retain filesystem resolution semantics") {
            val entries = listOf(".", "linked/classes/../library.jar", "../libs/*")
            assertEquals(
                listOf(
                    coordinator.resolve(".").toString(),
                    coordinator.resolve("linked/classes/../library.jar").toString(),
                    "${coordinator.resolve("../libs")}${File.separator}*"
                ).joinToString(File.pathSeparator),
                resolveWorkerClasspath(entries.joinToString(File.pathSeparator), coordinator)
            )
        }

        test("absolute classpaths remain unchanged using the platform separator") {
            val classpath = listOf(
                coordinator.resolve("classes").toString(),
                coordinator.resolve("library with spaces.jar").toString()
            ).joinToString(File.pathSeparator)
            assertEquals(classpath, resolveWorkerClasspath(classpath, coordinator))
        }
    })
