package io.github.skhokhlov.rewriterunner.lst

import io.github.skhokhlov.rewriterunner.lst.utils.FileCollector
import io.kotest.core.spec.style.FunSpec
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ParserRegistryTest :
    FunSpec({
        var dir = Path.of("")
        beforeEach { dir = Files.createTempDirectory("parser-ownership-") }
        afterEach { dir.toFile().deleteRecursively() }
        fun seed(vararg paths: String) {
            paths.forEach {
                dir.resolve(it).also { p ->
                    p.parent.createDirectories()
                    p.writeText("content")
                }
            }
        }
        fun custom(id: String, selection: ParserFileSelection, vararg overrides: String) =
            ParserRegistration(id, "example.Provider", selection, overrides = overrides.toSet())
        fun ownership(registry: ParserRegistry) = registry.collect(dir, emptyList(), emptyList())
            .flatMap { (registration, files) ->
                files.map {
                    dir.relativize(it).toString().replace('\\', '/') to
                        registration.id
                }
            }.toMap()

        test(
            "default ownership preserves filenames, filtering and plaintext fallback"
        ) {
            seed(
                "a.java", "test/A.KT", "build.gradle.kts", "plain.kts", "pom.xml", "module/pom.xml",
                "schema.xml", "Dockerfile", "dockerfile.dev", "Dockerfile.json", "Containerfile",
                "image.CONTAINERFILE",
                "main.TF", "thing.proto", "a.yaml", "a.yml", "package.json", "a.properties",
                "a.toml",
                "README.md", "build/ignored.java", "node_modules/a.json", "ignored/a.xml"
            )
            val masks = listOf("**/README.md")
            val collected = ParserRegistry(
                BuiltinParserProviders.registrations()
            ).collect(dir, listOf("ignored/**"), masks)
            val actual = collected.values.flatten().map { dir.relativize(it) }.toSet()
            val legacy = FileCollector().collectFiles(
                dir,
                FileCollector.DEFAULT_EXTENSIONS,
                listOf("ignored/**"),
                masks
            )
            assertEquals(legacy.values.flatten().map { dir.relativize(it) }.toSet(), actual)
            val owners = collected.flatMap { (r, fs) ->
                fs.map { it.fileName.toString() to r.id }
            }.toMap()
            assertEquals("maven", owners["pom.xml"])
            assertEquals("gradle-kotlin", owners["build.gradle.kts"])
            assertEquals("json", owners["Dockerfile.json"])
            assertEquals("plain-text", owners["README.md"])
        }
        test(
            "Maven and XML overrides require an explicit winner for overlapping custom assignments"
        ) {
            seed("schemas/pom.xml", "schemas/types.xml", "normal.xml", "pom.xml")
            val defaults = BuiltinParserProviders.registrations()
            val schema =
                custom("schema", ParserFileSelection(pathGlobs = listOf("schemas/**")), "xml")
            val error =
                assertFailsWith<IllegalArgumentException> {
                    ownership(ParserRegistry(defaults + schema))
                }
            assertTrue(error.message!!.contains("schemas"))
            assertTrue(error.message!!.contains("maven"))
            assertTrue(error.message!!.contains("schema"))
            val expected = mapOf(
                "schemas/pom.xml" to "schema",
                "schemas/types.xml" to "schema",
                "normal.xml" to "xml",
                "pom.xml" to "maven"
            )
            assertEquals(
                expected,
                ownership(ParserRegistry(defaults + schema.copy(overrides = setOf("xml", "maven"))))
            )
            assertEquals(
                expected,
                ownership(
                    ParserRegistry(
                        listOf(schema.copy(overrides = setOf("xml", "maven"))) + defaults
                    )
                )
            )
        }
        test("custom extensions and root-level globs participate in specialized ownership") {
            seed(
                "entry.MYLANG",
                "module/other.mylang",
                "config.json",
                "untouched.properties",
                "build/skip.mylang"
            )
            val extension = custom("language", ParserFileSelection(extensions = setOf(".mylang")))
            val json =
                custom(
                    "custom-json",
                    ParserFileSelection(pathGlobs = listOf("**/config.json")),
                    "json"
                )
            val registry = ParserRegistry(BuiltinParserProviders.registrations() + extension + json)
            val owned = registry.collect(
                dir,
                emptyList(),
                emptyList(),
                specializedOnly = true
            ).values.flatten()
            assertEquals(
                setOf("entry.MYLANG", "other.mylang", "config.json"),
                owned.map {
                    it.fileName.toString()
                }.toSet()
            )
        }
        test("replacing the plain-text provider retains fallback ownership") {
            seed("README.md", "app.properties")
            val defaults = BuiltinParserProviders.registrations().map {
                if (it.id ==
                    "plain-text"
                ) {
                    it.copy(providerClassName = "example.Provider")
                } else {
                    it
                }
            }
            val owned = ParserRegistry(
                defaults
            ).collect(dir, emptyList(), listOf("**/README.md"), specializedOnly = true)
            assertEquals(
                setOf("README.md"),
                owned.values.flatten().map {
                    it.fileName.toString()
                }.toSet()
            )
        }
        test("extension restrictions preserve Docker filename fallback semantics") {
            seed(
                "Dockerfile",
                "Dockerfile.dev",
                "Dockerfile.json",
                "image.dockerfile",
                "image.containerfile"
            )
            val restricted = setOf(".dockerfile")
            val actual = ParserRegistry(BuiltinParserProviders.registrations())
                .collect(dir, emptyList(), emptyList(), restrictToExtensions = restricted)
                .values.flatten().map { it.fileName.toString() }.toSet()
            val legacy = FileCollector().collectFiles(dir, restricted, emptyList())
                .values.flatten().map { it.fileName.toString() }.toSet()
            assertEquals(legacy, actual)
        }
        test("invalid precedence and duplicate IDs fail before collecting any files") {
            val a = custom("a", ParserFileSelection(), "b")
            val b = custom("b", ParserFileSelection(), "a")
            assertFailsWith<IllegalArgumentException> { ParserRegistry(listOf(a, b)) }
            assertFailsWith<IllegalArgumentException> { ParserRegistry(listOf(a, a)) }
            assertFailsWith<IllegalArgumentException> {
                ParserRegistry(listOf(a.copy(overrides = setOf("a"))))
            }
            assertFailsWith<IllegalArgumentException> {
                ParserRegistry(
                    listOf(a.copy(selection = ParserFileSelection(extensions = setOf("java"))))
                )
            }
        }
        test("literal Stage 0 exclusions do not broaden filenames containing glob characters") {
            val file = Path.of("schemas", "a[1]{x}.lang")
            val matcher = FileSystems.getDefault().getPathMatcher("glob:${literalExclusion(file)}")
            assertTrue(matcher.matches(file))
            assertTrue(!matcher.matches(Path.of("schemas", "a1x-other.lang")))
        }
    })
