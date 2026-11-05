package io.github.skhokhlov.rewriterunner.lst

import io.github.skhokhlov.rewriterunner.ExecutionMode
import io.github.skhokhlov.rewriterunner.NoOpRunnerLogger
import io.github.skhokhlov.rewriterunner.RewriteRunner
import io.github.skhokhlov.rewriterunner.config.ToolConfig
import io.kotest.core.spec.style.FunSpec
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Stream
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.openrewrite.ExecutionContext
import org.openrewrite.Parser
import org.openrewrite.SourceFile
import org.openrewrite.json.JsonParser
import org.openrewrite.json.tree.Json
import org.openrewrite.marker.OperatingSystemProvenance
import org.openrewrite.text.PlainTextParser
import org.openrewrite.tree.ParseError

/** A library-provided parser, intentionally independent of any built-in language's file suffix. */
class TestLanguageProvider : ParserProvider {
    override fun defaultSelection() = ParserFileSelection(extensions = setOf(".mylang"))
    override fun create(context: ParserContext, options: Map<String, String>): Parser {
        contexts += context
        options["marker"]?.let { Files.writeString(context.projectDir.resolve("provider.log"), it) }
        return object : Parser {
            override fun accept(path: Path): Boolean = true
            override fun sourcePathFromSourceText(prefix: Path, sourceCode: String): Path =
                prefix.resolve("source.mylang")
            override fun parseInputs(
                sources: Iterable<Parser.Input>,
                relativeTo: Path?,
                ctx: ExecutionContext
            ): Stream<SourceFile> {
                val inputs = sources.toList()
                batches += inputs.map { it.path }
                when (options["mode"]) {
                    "throw" -> throw IllegalStateException("custom batch failure")
                    "fatal" -> throw LinkageError("custom fatal error")
                    "drop" -> return Stream.empty()
                }
                return PlainTextParser.builder().build().parseInputs(inputs, relativeTo, ctx)
            }
        }
    }
    companion object {
        val contexts = mutableListOf<ParserContext>()
        val batches = mutableListOf<List<Path>>()
    }
}

class FailingLanguageProvider : ParserProvider {
    override fun create(context: ParserContext, options: Map<String, String>): Parser =
        error("bad provider settings")
}

class FatalConstructorProvider : ParserProvider {
    init {
        throw AssertionError("fatal constructor failure")
    }
    override fun create(context: ParserContext, options: Map<String, String>): Parser =
        error("Unreachable")
}

class TestSemanticLanguageProvider : ParserProvider {
    override fun defaultSelection() = ParserFileSelection(extensions = setOf(".semantic"))
    override fun create(context: ParserContext, options: Map<String, String>): Parser =
        object : JsonParser() {
            override fun accept(path: Path): Boolean = true
        }
}

class ClasspathLanguageProvider : ParserProvider {
    override fun requiresClasspath(): Boolean = true
    override fun create(context: ParserContext, options: Map<String, String>): Parser {
        TestLanguageProvider.contexts += context
        return PlainTextParser.builder().build()
    }
}

class CustomParserTest :
    FunSpec({
        var dir = Path.of("")
        beforeEach {
            dir = Files.createTempDirectory("custom-parser-")
            TestLanguageProvider.contexts.clear()
            TestLanguageProvider.batches.clear()
        }
        afterEach { dir.toFile().deleteRecursively() }
        fun registration(mode: String = "ok") = ParserRegistration(
            "my-language",
            TestLanguageProvider::class.java.name,
            ParserFileSelection(extensions = setOf(".mylang")),
            options = mapOf("mode" to mode)
        )
        fun builder(registrations: List<ParserRegistration>): LstBuilder = LstBuilder(
            logger = NoOpRunnerLogger,
            cacheDir = dir.resolve("cache"),
            toolConfig = ToolConfig(logger = NoOpRunnerLogger),
            projectBuildStage = object : ProjectBuildStage(NoOpRunnerLogger) {
                override fun extractClasspath(projectDir: Path): List<Path>? =
                    error("Classpath stages must not run for a classpath-free parser")
            }
        ).withParserRegistrations(registrations)

        test(
            "custom files share context and provenance, batch together and respect exclusions"
        ) {
            dir.resolve("one.mylang").writeText("hello")
            dir.resolve("nested").createDirectories()
            dir.resolve("nested/two.mylang").writeText("world")
            dir.resolve("ignored.mylang").writeText("ignored")
            dir.resolve("build").createDirectories()
            dir.resolve("build/output.mylang").writeText("ignored")
            val registrations = BuiltinParserProviders.registrations() + registration()
            val result = builder(registrations).build(dir, excludePaths = listOf("ignored.mylang"))
            assertEquals(2, result.sourceFiles.size)
            assertEquals(1, TestLanguageProvider.batches.size)
            assertEquals(2, TestLanguageProvider.batches.single().size)
            assertTrue(
                result.sourceFiles.all {
                    it.markers.findFirst(OperatingSystemProvenance::class.java).isPresent
                }
            )
            assertEquals(emptyList(), TestLanguageProvider.contexts.single().classpath)
            assertEquals(emptyList(), result.executionDiagnostics.parseFailures)
        }
        test("ordinary custom parse exceptions and dropped inputs retain existing diagnostics") {
            dir.resolve("one.mylang").writeText("hello")
            for (mode in listOf("throw", "drop")) {
                val result = builder(listOf(registration(mode))).build(dir)
                assertEquals(0, result.sourceFiles.size)
                assertEquals(
                    "my-language",
                    result.executionDiagnostics.parseFailures.single().parser
                )
                assertEquals("one.mylang", result.executionDiagnostics.parseFailures.single().path)
            }
            assertFailsWith<LinkageError> { builder(listOf(registration("fatal"))).build(dir) }
        }
        test("provider loading and construction errors do not silently omit selected files") {
            dir.resolve("one.mylang").writeText("hello")
            val missing = assertFailsWith<IllegalStateException> {
                builder(
                    listOf(registration().copy(providerClassName = "missing.Provider"))
                ).build(dir)
            }
            assertTrue(missing.message!!.contains("my-language"))
            val failure = assertFailsWith<IllegalStateException> {
                builder(
                    listOf(
                        registration().copy(
                            providerClassName = FailingLanguageProvider::class.java.name
                        )
                    )
                ).build(dir)
            }
            assertTrue(failure.message!!.contains("bad provider settings"))
            assertFailsWith<AssertionError> {
                builder(
                    listOf(
                        registration().copy(
                            providerClassName = FatalConstructorProvider::class.java.name
                        )
                    )
                ).build(dir)
            }
        }
        test("custom semantic trees and ParseError trees retain the shared diagnostics contract") {
            dir.resolve("valid.semantic").writeText("""{"old": true}""")
            dir.resolve("broken.semantic").writeText("{ invalid json")
            val registration =
                ParserRegistration(
                    "semantic",
                    TestSemanticLanguageProvider::class.java.name,
                    ParserFileSelection(extensions = setOf(".semantic"))
                )
            val result = builder(listOf(registration)).build(dir)
            assertEquals(1, result.sourceFiles.count { it is Json.Document })
            assertEquals(1, result.sourceFiles.count { it is ParseError })
            assertEquals(1, result.executionDiagnostics.parsedFileCount)
            assertEquals("semantic", result.executionDiagnostics.parseFailures.single().parser)
        }
        test("custom providers can request the project classpath and share one JVM type cache") {
            dir.resolve("custom.mylang").writeText("hello")
            dir.resolve("other.ext").writeText("world")
            val jar = dir.resolve("dependency.jar").also { Files.write(it, byteArrayOf()) }
            var resolutions = 0
            val result = LstBuilder(
                NoOpRunnerLogger,
                dir.resolve("cache"),
                ToolConfig(logger = NoOpRunnerLogger),
                projectBuildStage = object : ProjectBuildStage(NoOpRunnerLogger) {
                    override fun extractClasspath(projectDir: Path): List<Path> {
                        resolutions++
                        return listOf(jar)
                    }
                }
            ).withParserRegistrations(
                listOf(
                    registration().copy(
                        providerClassName = ClasspathLanguageProvider::class.java.name
                    ),
                    registration().copy(
                        id = "other",
                        providerClassName = ClasspathLanguageProvider::class.java.name,
                        selection = ParserFileSelection(extensions = setOf(".ext"))
                    )
                )
            ).build(dir)
            assertEquals(2, result.sourceFiles.size)
            assertEquals(1, resolutions)
            assertTrue(jar in TestLanguageProvider.contexts.first().classpath)
            assertSame(
                TestLanguageProvider.contexts.first().typeCache,
                TestLanguageProvider.contexts.last().typeCache
            )
        }
        test("Java clients can implement and explicitly register a provider") {
            dir.resolve("example.javalang").writeText("hello")
            val result = JavaParserProviderFixture.configure(
                RewriteRunner.builder().projectDir(dir).cacheDir(dir.resolve("cache"))
                    .activeRecipe("org.openrewrite.FindSourceFiles").skipPluginRun(true)
                    .dryRun(true).executionMode(ExecutionMode.IN_PROCESS)
            ).build().run()
            assertEquals(1, result.executionDiagnostics.parsedFileCount)
        }
        test(
            "builder snapshots config and allows replacing a built-in provider"
        ) {
            dir.resolve("data.json").writeText("language syntax which is not JSON")
            var retained: RunnerConfiguration? = null
            val runnerBuilder = RewriteRunner.builder().projectDir(
                dir
            ).cacheDir(dir.resolve("cache"))
                .activeRecipe(
                    "org.openrewrite.FindSourceFiles"
                ).skipPluginRun(true).dryRun(true).executionMode(ExecutionMode.IN_PROCESS)
                .configure { config ->
                    retained = config
                    val index = config.parsers.indexOfFirst { it.id == "json" }
                    config.parsers[index] =
                        config.parsers[index].copy(
                            providerClassName = TestLanguageProvider::class.java.name
                        )
                }
                .configure {
                    assertEquals(
                        TestLanguageProvider::class.java.name,
                        it.parsers.first { p ->
                            p.id ==
                                "json"
                        }.providerClassName
                    )
                }
            val runner = runnerBuilder.build()
            assertNotNull(retained).parsers.clear()
            runnerBuilder.configure { it.parsers.clear() }
            val result = runner.run()
            assertEquals(1, result.executionDiagnostics.parsedFileCount)
            assertTrue(result.executionDiagnostics.parseFailures.isEmpty())
        }
    })
