package io.github.skhokhlov.rewriterunner.lst.utils

import io.github.skhokhlov.rewriterunner.config.ParseConfig
import io.kotest.core.spec.style.FunSpec
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileCollectorTest :
    FunSpec({
        var projectDir: Path = Path.of("")

        beforeEach { projectDir = Files.createTempDirectory("fcollector-") }

        afterEach { projectDir.toFile().deleteRecursively() }

        val collector = FileCollector()

        // ─── resolveExtensions ────────────────────────────────────────────────────

        test("includeExtensions CLI flag restricts to specified types") {
            val exts = collector.resolveExtensions(
                ParseConfig(),
                includeExtensionsCli = listOf(".java"),
                excludeExtensionsCli = emptyList()
            )
            assertEquals(setOf(".java"), exts)
        }

        test("excludeExtensions CLI flag removes types from defaults") {
            val exts = collector.resolveExtensions(
                ParseConfig(),
                includeExtensionsCli = emptyList(),
                excludeExtensionsCli = listOf(".xml", ".properties")
            )
            assertTrue(".xml" !in exts)
            assertTrue(".properties" !in exts)
            assertTrue(".java" in exts)
        }

        test("includeExtensions from config is respected when no CLI flag given") {
            val exts = collector.resolveExtensions(
                ParseConfig(includeExtensions = listOf(".yaml")),
                includeExtensionsCli = emptyList(),
                excludeExtensionsCli = emptyList()
            )
            assertEquals(setOf(".yaml"), exts)
        }

        test("CLI includeExtensions overrides config includeExtensions") {
            val exts = collector.resolveExtensions(
                ParseConfig(includeExtensions = listOf(".yaml")),
                includeExtensionsCli = listOf(".java"),
                excludeExtensionsCli = emptyList()
            )
            assertEquals(setOf(".java"), exts)
        }

        test("extensions without leading dot are normalized") {
            val exts = collector.resolveExtensions(
                ParseConfig(),
                includeExtensionsCli = listOf("java"),
                excludeExtensionsCli = emptyList()
            )
            assertTrue(".java" in exts)
        }

        // ─── collectFiles — extension matching ────────────────────────────────────

        test("only files with matching extensions are collected") {
            projectDir.resolve("Hello.java").writeText("class Hello {}")
            projectDir.resolve("config.yaml").writeText("key: value")
            projectDir.resolve("data.json").writeText("{}")

            val result = collector.collectFiles(projectDir, setOf(".java"), emptyList())

            assertEquals(setOf(".java"), result.keys)
            assertEquals(1, result[".java"]?.size)
        }

        test("multiple extensions are collected into separate buckets") {
            projectDir.resolve("Hello.java").writeText("class Hello {}")
            projectDir.resolve("config.yaml").writeText("key: value")

            val result = collector.collectFiles(projectDir, setOf(".java", ".yaml"), emptyList())

            assertEquals(1, result[".java"]?.size)
            assertEquals(1, result[".yaml"]?.size)
        }

        // ─── collectFiles — Dockerfile name-based detection ──────────────────────

        test("Dockerfile without extension is bucketed under .dockerfile") {
            projectDir.resolve("Dockerfile").writeText("FROM ubuntu:22.04")

            val result =
                collector.collectFiles(projectDir, setOf(".dockerfile"), emptyList())

            assertEquals(1, result[".dockerfile"]?.size)
            assertEquals("Dockerfile", result[".dockerfile"]!!.first().fileName.toString())
        }

        test("Dockerfile.dev is picked up by name prefix") {
            projectDir.resolve("Dockerfile.dev").writeText("FROM ubuntu:22.04")

            val result = collector.collectFiles(projectDir, setOf(".dockerfile"), emptyList())

            assertEquals(1, result[".dockerfile"]?.size)
        }

        test("Containerfile without extension is bucketed under .dockerfile") {
            projectDir.resolve("Containerfile").writeText("FROM fedora:latest")

            val result = collector.collectFiles(projectDir, setOf(".dockerfile"), emptyList())

            assertEquals(1, result[".dockerfile"]?.size)
        }

        test("Dockerfile is NOT collected when .dockerfile is not in effective set") {
            projectDir.resolve("Dockerfile").writeText("FROM ubuntu:22.04")
            projectDir.resolve("app.properties").writeText("key=value")

            val result = collector.collectFiles(projectDir, setOf(".properties"), emptyList())

            assertTrue(result[".dockerfile"].isNullOrEmpty())
        }

        // ─── collectFiles — excluded directories ──────────────────────────────────

        test("files inside build/ are excluded") {
            projectDir.resolve("Hello.java").writeText("class Hello {}")
            projectDir.resolve("build").createDirectories()
            projectDir.resolve("build/Generated.java").writeText("class Generated {}")

            val result = collector.collectFiles(projectDir, setOf(".java"), emptyList())

            assertEquals(1, result[".java"]?.size)
            assertEquals("Hello.java", result[".java"]!!.first().fileName.toString())
        }

        test("files inside target/ are excluded") {
            projectDir.resolve("Hello.java").writeText("class Hello {}")
            projectDir.resolve("target").createDirectories()
            projectDir.resolve("target/Compiled.java").writeText("class Compiled {}")

            val result = collector.collectFiles(projectDir, setOf(".java"), emptyList())

            assertEquals(1, result[".java"]?.size)
        }

        test("files inside node_modules/ are excluded") {
            projectDir.resolve("node_modules").createDirectories()
            projectDir.resolve("node_modules/package.json").writeText("{}")

            val result = collector.collectFiles(projectDir, setOf(".json"), emptyList())

            assertTrue(result[".json"].isNullOrEmpty())
        }

        test("files inside .git/ are excluded") {
            projectDir.resolve(".git").createDirectories()
            projectDir.resolve(".git/COMMIT_EDITMSG").writeText("initial commit")
            projectDir.resolve("app.properties").writeText("key=value")

            val result = collector.collectFiles(projectDir, setOf(".properties"), emptyList())

            assertEquals(1, result[".properties"]?.size)
        }

        // ─── collectFiles — glob excludePaths ─────────────────────────────────────

        test("glob excludePaths patterns exclude matching files") {
            projectDir.resolve("src").createDirectories()
            projectDir.resolve("src/Main.java").writeText("class Main {}")
            projectDir.resolve("generated").createDirectories()
            projectDir.resolve("generated/Gen.java").writeText("class Gen {}")

            val result =
                collector.collectFiles(projectDir, setOf(".java"), listOf("generated/**"))

            assertEquals(1, result[".java"]?.size)
            assertEquals("Main.java", result[".java"]!!.first().fileName.toString())
        }

        test("multiple glob patterns can be applied") {
            projectDir.resolve("src/main").createDirectories()
            projectDir.resolve("src/main/Main.java").writeText("class Main {}")
            projectDir.resolve("generated").createDirectories()
            projectDir.resolve("generated/Gen.java").writeText("class Gen {}")
            projectDir.resolve("vendor").createDirectories()
            projectDir.resolve("vendor/Lib.java").writeText("class Lib {}")

            val result = collector.collectFiles(
                projectDir,
                setOf(".java"),
                listOf("generated/**", "vendor/**")
            )

            assertEquals(1, result[".java"]?.size)
        }
    })
