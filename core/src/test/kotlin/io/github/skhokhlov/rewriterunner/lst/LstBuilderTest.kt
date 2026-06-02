package io.github.skhokhlov.rewriterunner.lst

import io.github.skhokhlov.rewriterunner.AetherContext
import io.github.skhokhlov.rewriterunner.NoOpRunnerLogger
import io.github.skhokhlov.rewriterunner.RunnerLogger
import io.github.skhokhlov.rewriterunner.UsedExecutionStage
import io.github.skhokhlov.rewriterunner.config.ParseConfig
import io.github.skhokhlov.rewriterunner.config.ToolConfig
import io.github.skhokhlov.rewriterunner.config.ToolConfigDefaults
import io.github.skhokhlov.rewriterunner.lst.utils.ClasspathResolutionResult
import io.kotest.core.spec.style.FunSpec
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.openrewrite.ExecutionContext
import org.openrewrite.ParseExceptionResult
import org.openrewrite.Parser
import org.openrewrite.SourceFile
import org.openrewrite.java.internal.JavaTypeCache
import org.openrewrite.marker.Markers
import org.openrewrite.marker.OperatingSystemProvenance
import org.openrewrite.maven.MavenParser
import org.openrewrite.maven.tree.MavenResolutionResult
import org.openrewrite.text.PlainText
import org.openrewrite.tree.ParseError

/**
 * Integration tests for [LstBuilder.build]: parser routing, 3-stage classpath pipeline,
 * compile-on-demand, and Gradle DSL classpath resolution.
 *
 * Extension filtering and file collection logic is covered by [io.github.skhokhlov.rewriterunner.lst.utils.FileCollectorTest].
 */
class LstBuilderTest :
    FunSpec({
        var projectDir: Path = Path.of("")

        beforeEach { projectDir = Files.createTempDirectory("lstb-") }

        afterEach { projectDir.toFile().deleteRecursively() }

        val toolConfig = ToolConfig(logger = NoOpRunnerLogger)

        /** A ProjectBuildStage that always returns null (simulates broken build tool). */
        val failingBuildTool =
            object : ProjectBuildStage(NoOpRunnerLogger) {
                override fun extractClasspath(projectDir: Path): List<Path>? = null
            }

        fun lstBuilder(
            buildTool: ProjectBuildStage = failingBuildTool,
            logger: RunnerLogger = NoOpRunnerLogger
        ): LstBuilder {
            val noOpDepStage =
                object : DependencyResolutionStage(
                    AetherContext.build(
                        projectDir.resolve("cache").resolve("repository"),
                        logger = NoOpRunnerLogger
                    ),
                    NoOpRunnerLogger
                ) {
                    override fun resolveClasspath(projectDir: Path): ClasspathResolutionResult =
                        ClasspathResolutionResult(emptyList())
                }
            val noOpBuildFileStage =
                object : BuildFileParseStage(
                    AetherContext.build(
                        projectDir.resolve("cache").resolve("repository"),
                        logger = NoOpRunnerLogger
                    ),
                    NoOpRunnerLogger
                ) {
                    override fun resolveClasspath(projectDir: Path): List<Path> = emptyList()
                }
            return LstBuilder(
                logger = logger,
                cacheDir = projectDir.resolve("cache"),
                toolConfig = toolConfig,
                projectBuildStage = buildTool,
                depResolutionStage = noOpDepStage,
                buildFileParseStage = noOpBuildFileStage
            )
        }

        // ─── Path-glob exclusion (integration smoke tests) ────────────────────────
        // Full unit tests for FileCollector.collectFiles live in FileCollectorTest.

        test("excludePaths skips files matching the glob") {
            projectDir.resolve("Hello.java").writeText("class Hello {}")
            projectDir.resolve("notes.md").writeText("# notes")

            val sources = lstBuilder().build(
                projectDir = projectDir,
                excludePaths = listOf("**/*.md")
            ).sourceFiles

            val paths = sources.map { it.sourcePath.toString() }
            assertTrue(paths.any { it.endsWith(".java") }, "Java file should remain")
            assertTrue(paths.none { it.endsWith(".md") }, "Markdown file should be excluded")
        }

        test("plain text mask matched files parse as PlainText with provenance markers") {
            projectDir.resolve("CODEOWNERS").writeText("* @team")

            val result = lstBuilder().build(
                projectDir = projectDir,
                plainTextMasks = listOf("**/CODEOWNERS")
            )

            val source = result.sourceFiles.single()
            assertEquals("CODEOWNERS", source.sourcePath.toString())
            assertTrue(source is PlainText, "CODEOWNERS should parse as PlainText")
            assertTrue(
                source.markers.findFirst(OperatingSystemProvenance::class.java).isPresent,
                "Provenance markers should be attached to plain-text files"
            )
            assertEquals(1, result.executionDiagnostics.parsedFileCount)
        }

        test("empty plainTextMasks falls back to upstream defaults") {
            projectDir.resolve("CODEOWNERS").writeText("* @team")

            val sources = lstBuilder().build(
                projectDir = projectDir,
                plainTextMasks = emptyList()
            ).sourceFiles

            assertEquals(listOf("CODEOWNERS"), sources.map { it.sourcePath.toString() })
            assertTrue(sources.single() is PlainText)
        }

        test("custom plainTextMasks replace upstream defaults") {
            projectDir.resolve("CODEOWNERS").writeText("* @team")
            projectDir.resolve("OWNERS").writeText("* @custom")

            val sources = lstBuilder().build(
                projectDir = projectDir,
                plainTextMasks = listOf("**/OWNERS")
            ).sourceFiles

            assertEquals(listOf("OWNERS"), sources.map { it.sourcePath.toString() })
        }

        test("tool config plainTextMasks are used when build override is empty") {
            projectDir.resolve("CODEOWNERS").writeText("* @team")
            projectDir.resolve("OWNERS").writeText("* @custom")
            val config = ToolConfig(
                parse = ParseConfig(plainTextMasks = listOf("**/OWNERS")),
                logger = NoOpRunnerLogger
            )
            val builder = LstBuilder(
                logger = NoOpRunnerLogger,
                cacheDir = projectDir.resolve("cache"),
                toolConfig = config,
                projectBuildStage = failingBuildTool,
                depResolutionStage = object : DependencyResolutionStage(
                    AetherContext.build(
                        projectDir.resolve("cache").resolve("repository"),
                        logger = NoOpRunnerLogger
                    ),
                    NoOpRunnerLogger
                ) {
                    override fun resolveClasspath(projectDir: Path): ClasspathResolutionResult =
                        ClasspathResolutionResult(emptyList())
                },
                buildFileParseStage = object : BuildFileParseStage(
                    AetherContext.build(
                        projectDir.resolve("cache").resolve("repository"),
                        logger = NoOpRunnerLogger
                    ),
                    NoOpRunnerLogger
                ) {
                    override fun resolveClasspath(projectDir: Path): List<Path> = emptyList()
                }
            )

            val sources = builder.build(projectDir = projectDir).sourceFiles

            assertEquals(listOf("OWNERS"), sources.map { it.sourcePath.toString() })
        }

        test("default plainTextMasks includes representative upstream masks") {
            assertTrue(ToolConfigDefaults.DEFAULT_PLAIN_TEXT_MASKS.contains("**/CODEOWNERS"))
            assertTrue(ToolConfigDefaults.DEFAULT_PLAIN_TEXT_MASKS.contains("**/*.md"))
            assertTrue(ToolConfigDefaults.DEFAULT_PLAIN_TEXT_MASKS.contains("**/Dockerfile"))
            assertTrue(!ToolConfigDefaults.DEFAULT_PLAIN_TEXT_MASKS.contains("**/*.css"))
            assertTrue(!ToolConfigDefaults.DEFAULT_PLAIN_TEXT_MASKS.contains("**/Dockerfile*"))
        }

        // ─── Multi-language parsing ───────────────────────────────────────────────

        test("all supported file types are parsed by default") {
            projectDir.resolve("Hello.java").writeText("class Hello {}")
            projectDir.resolve("Main.kt").writeText("fun main() {}")
            projectDir.resolve("build.gradle.kts").writeText("// gradle script")
            projectDir.resolve("build.gradle").writeText("// gradle script")
            projectDir.resolve("Helper.groovy").writeText("class Helper {}")
            projectDir.resolve("config.yaml").writeText("key: value")
            projectDir.resolve("data.json").writeText("{}")
            projectDir.resolve("pom.xml").writeText("<project/>")
            projectDir.resolve("app.properties").writeText("key=value")
            projectDir.resolve("config.toml").writeText("[package]\nname = \"example\"")
            projectDir.resolve(
                "main.tf"
            ).writeText("provider \"aws\" {\n  region = \"us-east-1\"\n}")
            projectDir.resolve("hello.proto").writeText("syntax = \"proto3\";\npackage example;")
            projectDir.resolve("Dockerfile").writeText("FROM ubuntu:22.04")

            val sources = lstBuilder().build(projectDir = projectDir).sourceFiles

            val paths = sources.map { it.sourcePath.toString() }
            assertTrue(paths.any { it.endsWith(".java") }, "Java should be parsed")
            assertTrue(paths.any { it.endsWith(".kt") }, "Kotlin should be parsed")
            assertTrue(
                paths.any { it.endsWith(".gradle.kts") },
                "Gradle Kotlin DSL should be parsed"
            )
            assertTrue(paths.any { it.endsWith(".gradle") }, "Gradle Groovy DSL should be parsed")
            assertTrue(paths.any { it.endsWith(".groovy") }, "Groovy should be parsed")
            assertTrue(paths.any { it.endsWith(".yaml") }, "YAML should be parsed")
            assertTrue(paths.any { it.endsWith(".json") }, "JSON should be parsed")
            assertTrue(
                paths.any { it.endsWith(".xml") },
                "XML (pom.xml via MavenParser) should be parsed"
            )
            assertTrue(paths.any { it.endsWith(".properties") }, "Properties should be parsed")
            assertTrue(paths.any { it.endsWith(".toml") }, "TOML should be parsed")
            assertTrue(paths.any { it.endsWith(".tf") }, "Terraform (.tf) should be parsed")
            assertTrue(paths.any { it.endsWith(".proto") }, "Protobuf should be parsed")
            assertTrue(paths.any { it == "Dockerfile" }, "Dockerfile should be parsed by name")
        }

        test("gradle.kts files and plain kts files are both parsed under kts extension") {
            projectDir.resolve("build.gradle.kts").writeText("// gradle kotlin dsl")
            projectDir.resolve("settings.gradle.kts").writeText("// settings")
            projectDir.resolve("script.kts").writeText("// plain kts script")

            val sources =
                lstBuilder().build(
                    projectDir = projectDir
                ).sourceFiles

            val paths = sources.map { it.sourcePath.toString() }
            assertEquals(3, sources.size, "All three .kts files should be parsed")
            assertTrue(paths.any { it == "build.gradle.kts" }, "build.gradle.kts should be parsed")
            assertTrue(
                paths.any { it == "settings.gradle.kts" },
                "settings.gradle.kts should be parsed"
            )
            assertTrue(paths.any { it == "script.kts" }, "plain script.kts should be parsed")
        }

        test("groovy files and gradle files are both parsed under their respective extensions") {
            projectDir.resolve("Helper.groovy").writeText("class Helper {}")
            projectDir.resolve("build.gradle").writeText("// gradle groovy dsl")

            val sources = lstBuilder().build(
                projectDir = projectDir
            ).sourceFiles

            val paths = sources.map { it.sourcePath.toString() }
            assertEquals(2, sources.size, "Both .groovy and .gradle files should be parsed")
            assertTrue(paths.any { it.endsWith(".groovy") }, "Groovy file should be parsed")
            assertTrue(paths.any { it.endsWith(".gradle") }, "Gradle file should be parsed")
        }

        test("yml extension is treated same as yaml") {
            projectDir.resolve("app.yml").writeText("spring:\n  port: 8080")

            val sources =
                lstBuilder().build(
                    projectDir = projectDir
                ).sourceFiles
            assertEquals(1, sources.size)
        }

        // ─── TOML / HCL / Protobuf / Docker parsers ──────────────────────────────

        test("toml files are parsed") {
            projectDir.resolve("Cargo.toml").writeText(
                "[package]\nname = \"example\"\nversion = \"0.1.0\"\n"
            )

            val sources =
                lstBuilder().build(
                    projectDir = projectDir
                ).sourceFiles
            assertEquals(1, sources.size, "TOML file should be parsed")
            assertTrue(sources.first().sourcePath.toString().endsWith(".toml"))
        }

        test("hcl files are parsed") {
            projectDir.resolve("config.hcl").writeText(
                "variable \"region\" {\n  default = \"us-east-1\"\n}\n"
            )

            val sources =
                lstBuilder().build(
                    projectDir = projectDir
                ).sourceFiles
            assertEquals(1, sources.size, "HCL file should be parsed")
        }

        test("tf files are parsed") {
            projectDir.resolve("main.tf").writeText(
                "provider \"aws\" {\n  region = \"us-east-1\"\n}\n"
            )

            val sources =
                lstBuilder().build(
                    projectDir = projectDir
                ).sourceFiles
            assertEquals(1, sources.size, "Terraform (.tf) file should be parsed")
        }

        test("tfvars files are parsed") {
            projectDir.resolve("terraform.tfvars").writeText("region = \"us-east-1\"\n")

            val sources =
                lstBuilder().build(
                    projectDir = projectDir
                ).sourceFiles
            assertEquals(1, sources.size, "Terraform variable (.tfvars) file should be parsed")
        }

        test("hcl tf and tfvars are all parsed together under hcl extension group") {
            projectDir.resolve("config.hcl").writeText(
                "variable \"env\" {\n  default = \"dev\"\n}\n"
            )
            projectDir.resolve("main.tf").writeText(
                "provider \"aws\" {\n  region = \"us-east-1\"\n}\n"
            )
            projectDir.resolve("terraform.tfvars").writeText("env = \"prod\"\n")

            val sources =
                lstBuilder().build(
                    projectDir = projectDir
                ).sourceFiles
            assertEquals(3, sources.size, "All three HCL-family extensions should be parsed")
        }

        test("proto files are parsed") {
            projectDir.resolve("hello.proto").writeText(
                "syntax = \"proto3\";\npackage example;\nmessage Hello {\n  string name = 1;\n}\n"
            )

            val sources =
                lstBuilder().build(
                    projectDir = projectDir
                ).sourceFiles
            assertEquals(1, sources.size, "Protobuf file should be parsed")
        }

        test("dockerfile with dockerfile extension is parsed") {
            projectDir.resolve("service.dockerfile").writeText(
                "FROM ubuntu:22.04\nRUN apt-get update\n"
            )

            val sources =
                lstBuilder().build(
                    projectDir = projectDir
                ).sourceFiles
            assertEquals(1, sources.size, ".dockerfile extension should be parsed")
        }

        test("containerfile with containerfile extension is parsed") {
            projectDir.resolve("service.containerfile").writeText(
                "FROM ubuntu:22.04\nRUN dnf install -y bash\n"
            )

            val sources =
                lstBuilder().build(
                    projectDir = projectDir
                ).sourceFiles
            assertEquals(1, sources.size, ".containerfile extension should be parsed")
        }

        test("Dockerfile without extension is parsed when dockerfile extension is included") {
            projectDir.resolve("Dockerfile").writeText("FROM ubuntu:22.04\nRUN echo hello\n")

            val sources =
                lstBuilder().build(
                    projectDir = projectDir
                ).sourceFiles
            assertEquals(1, sources.size, "Dockerfile (no extension) should be parsed by name")
            assertEquals("Dockerfile", sources.first().sourcePath.toString())
        }

        test("Dockerfile.dev is parsed by name prefix when dockerfile extension is included") {
            projectDir.resolve("Dockerfile.dev").writeText("FROM ubuntu:22.04\nENV ENV=dev\n")

            val sources =
                lstBuilder().build(
                    projectDir = projectDir
                ).sourceFiles
            assertEquals(1, sources.size, "Dockerfile.dev should be parsed by name prefix")
        }

        test(
            "Containerfile without extension is parsed by name when dockerfile extension included"
        ) {
            projectDir.resolve("Containerfile").writeText("FROM fedora:latest\nRUN dnf -y update\n")

            val sources =
                lstBuilder().build(
                    projectDir = projectDir
                ).sourceFiles
            assertEquals(1, sources.size, "Containerfile (no extension) should be parsed by name")
        }

        // ─── Maven POM parser ─────────────────────────────────────────────────────

        test("pom.xml is parsed with MavenParser and has MavenResolutionResult marker") {
            projectDir.resolve("pom.xml").writeText(
                """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>my-app</artifactId>
                  <version>1.0.0</version>
                </project>
                """.trimIndent()
            )

            val sources =
                lstBuilder().build(
                    projectDir = projectDir
                ).sourceFiles
            assertEquals(1, sources.size, "pom.xml should be parsed")
            val pom = sources.first()
            assertEquals("pom.xml", pom.sourcePath.toString())
            assertTrue(
                pom.markers.findFirst(MavenResolutionResult::class.java).isPresent,
                "pom.xml should have MavenResolutionResult marker from MavenParser"
            )
        }

        test("non-pom xml files do NOT have MavenResolutionResult marker") {
            projectDir.resolve(
                "config.xml"
            ).writeText("<configuration><key>value</key></configuration>")

            val sources =
                lstBuilder().build(
                    projectDir = projectDir
                ).sourceFiles
            assertEquals(1, sources.size, "config.xml should be parsed")
            assertFalse(
                sources.first().markers.findFirst(MavenResolutionResult::class.java).isPresent,
                "Generic XML file should NOT have MavenResolutionResult marker"
            )
        }

        test("pom.xml and other xml files are both parsed when xml extension is included") {
            projectDir.resolve("pom.xml").writeText(
                "<project><modelVersion>4.0.0</modelVersion>" +
                    "<groupId>com.example</groupId>" +
                    "<artifactId>app</artifactId>" +
                    "<version>1.0</version></project>"
            )
            projectDir.resolve("config.xml").writeText("<config/>")
            projectDir.resolve("logback.xml").writeText("<configuration/>")

            val sources =
                lstBuilder().build(
                    projectDir = projectDir
                ).sourceFiles
            assertEquals(3, sources.size, "All 3 xml files should be parsed")
            val paths = sources.map { it.sourcePath.toString() }
            assertTrue(paths.contains("pom.xml"))
            assertTrue(paths.contains("config.xml"))
            assertTrue(paths.contains("logback.xml"))
            val pom = sources.first { it.sourcePath.toString() == "pom.xml" }
            assertTrue(
                pom.markers.findFirst(MavenResolutionResult::class.java).isPresent,
                "pom.xml should have Maven marker"
            )
            val config = sources.first { it.sourcePath.toString() == "config.xml" }
            assertFalse(
                config.markers.findFirst(MavenResolutionResult::class.java).isPresent,
                "config.xml should NOT have Maven marker"
            )
        }

        test("pom.xml is NOT parsed when excluded via excludePaths glob") {
            projectDir.resolve("pom.xml").writeText(
                "<project><modelVersion>4.0.0</modelVersion>" +
                    "<groupId>com.example</groupId>" +
                    "<artifactId>app</artifactId>" +
                    "<version>1.0</version></project>"
            )
            projectDir.resolve("app.properties").writeText("key=value")

            val sources =
                lstBuilder().build(
                    projectDir = projectDir,
                    excludePaths = listOf("pom.xml")
                ).sourceFiles
            assertTrue(
                sources.none { it.sourcePath.toString() == "pom.xml" },
                "pom.xml should be excluded when matched by excludePaths"
            )
        }

        // ─── MavenParser URI failure fallback ────────────────────────────────────

        /**
         * Builds an [LstBuilder] whose [LstBuilder.buildMavenParser] returns a
         * [MavenParser] that throws [IllegalArgumentException] (with a
         * [java.net.URISyntaxException] cause) for any pom whose relative path matches
         * [throwFor]. Mirrors the [MavenPomDownloader] failure mode where
         * `URI.create(...)` rejects an illegal artifact path.
         */
        fun lstBuilderWithMavenStub(throwFor: Set<String>): LstBuilder {
            val noOpDepStage =
                object : DependencyResolutionStage(
                    AetherContext.build(
                        projectDir.resolve("cache").resolve("repository"),
                        logger = NoOpRunnerLogger
                    ),
                    NoOpRunnerLogger
                ) {
                    override fun resolveClasspath(projectDir: Path): ClasspathResolutionResult =
                        ClasspathResolutionResult(emptyList())
                }
            val noOpBuildFileStage =
                object : BuildFileParseStage(
                    AetherContext.build(
                        projectDir.resolve("cache").resolve("repository"),
                        logger = NoOpRunnerLogger
                    ),
                    NoOpRunnerLogger
                ) {
                    override fun resolveClasspath(projectDir: Path): List<Path> = emptyList()
                }
            return object : LstBuilder(
                logger = NoOpRunnerLogger,
                cacheDir = projectDir.resolve("cache"),
                toolConfig = toolConfig,
                projectBuildStage = failingBuildTool,
                depResolutionStage = noOpDepStage,
                buildFileParseStage = noOpBuildFileStage
            ) {
                override fun buildMavenParser(): MavenParser =
                    object : MavenParser(emptyList(), emptyMap(), false) {
                        override fun parseInputs(
                            sources: Iterable<Parser.Input>,
                            relativeTo: Path?,
                            ctx: ExecutionContext
                        ): java.util.stream.Stream<SourceFile> {
                            val cwd = relativeTo ?: projectDir
                            sources.forEach { input ->
                                val rel = cwd.relativize(input.path).normalize().toString()
                                if (rel in throwFor) {
                                    throw IllegalArgumentException(
                                        "Illegal character in path at index 7: $rel",
                                        java.net.URISyntaxException("bad", "Illegal character")
                                    )
                                }
                            }
                            return super.parseInputs(sources, relativeTo, ctx)
                        }
                    }
            }
        }

        test("pom.xml with URI-failure does not abort the build") {
            // Good pom in a subdirectory so per-file fallback can still resolve it.
            val module = projectDir.resolve("module").also { it.createDirectories() }
            module.resolve("pom.xml").writeText(
                """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>good</artifactId>
                  <version>1.0.0</version>
                </project>
                """.trimIndent()
            )
            // Bad pom — content itself is valid XML; the stub MavenParser throws on it.
            projectDir.resolve("pom.xml").writeText(
                """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>bad</artifactId>
                  <version>1.0.0</version>
                </project>
                """.trimIndent()
            )

            val result = lstBuilderWithMavenStub(throwFor = setOf("pom.xml")).build(
                projectDir = projectDir
            )

            val poms = result.sourceFiles.filter { it.sourcePath.fileName.toString() == "pom.xml" }
            assertEquals(2, poms.size, "Both pom.xml files should be in the LST")

            val badPom = poms.first { it.sourcePath.toString() == "pom.xml" }
            assertFalse(
                badPom.markers.findFirst(MavenResolutionResult::class.java).isPresent,
                "Bad pom should be downgraded to XmlParser (no MavenResolutionResult marker)"
            )

            val goodPom = poms.first {
                it.sourcePath.toString().endsWith("module/pom.xml") ||
                    it.sourcePath.toString().endsWith("module${java.io.File.separator}pom.xml")
            }
            assertTrue(
                goodPom.markers.findFirst(MavenResolutionResult::class.java).isPresent,
                "Good pom should still be parsed by MavenParser"
            )

            assertEquals(
                1,
                result.executionDiagnostics.parseFailures.size,
                "Exactly one parse failure should be recorded"
            )
            val failure = result.executionDiagnostics.parseFailures.first()
            assertEquals("pom.xml", failure.path)
            assertEquals("MavenParser", failure.parser)
            assertTrue(failure.reason.contains("Illegal character"))
        }

        test("non-URI MavenParser exceptions still bubble up") {
            projectDir.resolve("pom.xml").writeText(
                """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>app</artifactId>
                  <version>1.0.0</version>
                </project>
                """.trimIndent()
            )

            val noOpDepStage =
                object : DependencyResolutionStage(
                    AetherContext.build(
                        projectDir.resolve("cache").resolve("repository"),
                        logger = NoOpRunnerLogger
                    ),
                    NoOpRunnerLogger
                ) {
                    override fun resolveClasspath(projectDir: Path): ClasspathResolutionResult =
                        ClasspathResolutionResult(emptyList())
                }
            val noOpBuildFileStage =
                object : BuildFileParseStage(
                    AetherContext.build(
                        projectDir.resolve("cache").resolve("repository"),
                        logger = NoOpRunnerLogger
                    ),
                    NoOpRunnerLogger
                ) {
                    override fun resolveClasspath(projectDir: Path): List<Path> = emptyList()
                }
            val builder = object : LstBuilder(
                logger = NoOpRunnerLogger,
                cacheDir = projectDir.resolve("cache"),
                toolConfig = toolConfig,
                projectBuildStage = failingBuildTool,
                depResolutionStage = noOpDepStage,
                buildFileParseStage = noOpBuildFileStage
            ) {
                override fun buildMavenParser(): MavenParser =
                    object : MavenParser(emptyList(), emptyMap(), false) {
                        override fun parseInputs(
                            sources: Iterable<Parser.Input>,
                            relativeTo: Path?,
                            ctx: ExecutionContext
                        ): java.util.stream.Stream<SourceFile> =
                            throw IllegalStateException("unrelated bug")
                    }
            }

            val thrown = kotlin.runCatching {
                builder.build(projectDir = projectDir)
            }.exceptionOrNull()
            assertNotNull(thrown, "An unrelated MavenParser bug must not be swallowed")
            assertTrue(
                thrown is IllegalStateException && thrown.message == "unrelated bug",
                "Expected the original IllegalStateException to bubble up, got: $thrown"
            )
        }

        test("IllegalArgumentException without URISyntaxException cause bubbles up") {
            // Regression guard: an IllegalArgumentException whose cause chain does NOT
            // include a URISyntaxException must not trigger the XmlParser fallback.
            // Otherwise unrelated MavenParser bugs would be silently downgraded.
            projectDir.resolve("pom.xml").writeText(
                """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>app</artifactId>
                  <version>1.0.0</version>
                </project>
                """.trimIndent()
            )

            val noOpDepStage =
                object : DependencyResolutionStage(
                    AetherContext.build(
                        projectDir.resolve("cache").resolve("repository"),
                        logger = NoOpRunnerLogger
                    ),
                    NoOpRunnerLogger
                ) {
                    override fun resolveClasspath(projectDir: Path): ClasspathResolutionResult =
                        ClasspathResolutionResult(emptyList())
                }
            val noOpBuildFileStage =
                object : BuildFileParseStage(
                    AetherContext.build(
                        projectDir.resolve("cache").resolve("repository"),
                        logger = NoOpRunnerLogger
                    ),
                    NoOpRunnerLogger
                ) {
                    override fun resolveClasspath(projectDir: Path): List<Path> = emptyList()
                }
            val builder = object : LstBuilder(
                logger = NoOpRunnerLogger,
                cacheDir = projectDir.resolve("cache"),
                toolConfig = toolConfig,
                projectBuildStage = failingBuildTool,
                depResolutionStage = noOpDepStage,
                buildFileParseStage = noOpBuildFileStage
            ) {
                override fun buildMavenParser(): MavenParser =
                    object : MavenParser(emptyList(), emptyMap(), false) {
                        override fun parseInputs(
                            sources: Iterable<Parser.Input>,
                            relativeTo: Path?,
                            ctx: ExecutionContext
                        ): java.util.stream.Stream<SourceFile> =
                            throw IllegalArgumentException("non-URI validation failure")
                    }
            }

            val thrown = kotlin.runCatching {
                builder.build(projectDir = projectDir)
            }.exceptionOrNull()
            assertNotNull(thrown, "Non-URI IllegalArgumentException must not be swallowed")
            assertTrue(
                thrown is IllegalArgumentException &&
                    thrown.message == "non-URI validation failure",
                "Expected the original IllegalArgumentException to bubble up, got: $thrown"
            )
        }

        // ─── 4-stage pipeline fallthrough ────────────────────────────────────────

        test("stage 1 is attempted first") {
            var stage1Called = false
            projectDir.resolve("Hello.java").writeText("class Hello {}")
            val trackingBuildTool =
                object : ProjectBuildStage(NoOpRunnerLogger) {
                    override fun extractClasspath(projectDir: Path): List<Path>? {
                        stage1Called = true
                        return null
                    }
                }
            lstBuilder(buildTool = trackingBuildTool).build(
                projectDir = projectDir
            )
            assertTrue(stage1Called, "Stage 1 (build tool) should always be attempted first")
        }

        test("stage 1 result is used when it returns a non-null list") {
            var stage2Called = false
            val fakeJar = projectDir.resolve("fake.jar").also { it.writeText("") }

            val successfulBuildTool =
                object : ProjectBuildStage(NoOpRunnerLogger) {
                    override fun extractClasspath(projectDir: Path): List<Path> = listOf(fakeJar)
                }
            val trackingDepStage =
                object : DependencyResolutionStage(
                    AetherContext.build(
                        projectDir.resolve("cache").resolve("repository"),
                        logger = NoOpRunnerLogger
                    ),
                    NoOpRunnerLogger
                ) {
                    override fun resolveClasspath(projectDir: Path): ClasspathResolutionResult {
                        stage2Called = true
                        return ClasspathResolutionResult(emptyList())
                    }
                }

            projectDir.resolve("Hello.java").writeText("class Hello {}")
            LstBuilder(
                cacheDir = projectDir.resolve("cache"),
                toolConfig = toolConfig,
                projectBuildStage = successfulBuildTool,
                depResolutionStage = trackingDepStage,
                logger = NoOpRunnerLogger
            )
                .build(projectDir = projectDir)

            assertTrue(!stage2Called, "Stage 2 should NOT be called when Stage 1 succeeds")
        }

        test("stage 2 is attempted when stage 1 returns null") {
            projectDir.resolve("Hello.java").writeText("class Hello {}")
            var stage2Called = false
            val trackingDepStage =
                object : DependencyResolutionStage(
                    AetherContext.build(
                        projectDir.resolve("cache").resolve("repository"),
                        logger = NoOpRunnerLogger
                    ),
                    NoOpRunnerLogger
                ) {
                    override fun resolveClasspath(projectDir: Path): ClasspathResolutionResult {
                        stage2Called = true
                        return ClasspathResolutionResult(emptyList())
                    }
                }

            LstBuilder(
                cacheDir = projectDir.resolve("cache"),
                toolConfig = toolConfig,
                projectBuildStage = failingBuildTool,
                depResolutionStage = trackingDepStage,
                logger = NoOpRunnerLogger
            )
                .build(projectDir = projectDir)

            assertTrue(stage2Called, "Stage 2 should be attempted when Stage 1 fails")
        }

        test("stage 3 is attempted when stage 1 and stage 2 fail") {
            projectDir.resolve("Hello.java").writeText("class Hello {}")
            var stage3Called = false
            val noOpDepStage =
                object : DependencyResolutionStage(
                    AetherContext.build(
                        projectDir.resolve("cache").resolve("repository"),
                        logger = NoOpRunnerLogger
                    ),
                    NoOpRunnerLogger
                ) {
                    override fun resolveClasspath(projectDir: Path): ClasspathResolutionResult =
                        ClasspathResolutionResult(emptyList())
                }
            val trackingBuildFileStage =
                object : BuildFileParseStage(
                    AetherContext.build(
                        projectDir.resolve("cache").resolve("repository"),
                        logger = NoOpRunnerLogger
                    ),
                    NoOpRunnerLogger
                ) {
                    override fun resolveClasspath(projectDir: Path): List<Path> {
                        stage3Called = true
                        return emptyList()
                    }
                }

            LstBuilder(
                cacheDir = projectDir.resolve("cache"),
                toolConfig = toolConfig,
                projectBuildStage = failingBuildTool,
                depResolutionStage = noOpDepStage,
                buildFileParseStage = trackingBuildFileStage,
                logger = NoOpRunnerLogger
            )
                .build(projectDir = projectDir)

            assertTrue(stage3Called, "Stage 3 should be attempted when Stages 1 and 2 fail")
        }

        test("parsing succeeds even when all classpath stages fail") {
            projectDir.resolve("Hello.java").writeText("class Hello {}")

            val sources =
                lstBuilder().build(
                    projectDir = projectDir
                ).sourceFiles

            assertEquals(
                1,
                sources.size,
                "Java file should be parsed even without classpath (Stage 4 fallback)"
            )
        }

        // ─── Compile-on-demand (Stage 1 enhancement) ─────────────────────────────

        test("tryCompile is called when stage 1 succeeds but no class directories exist") {
            var tryCompileCalled = false
            val fakeJar = projectDir.resolve("fake.jar").also { it.writeText("") }

            val trackingBuildTool2 =
                object : ProjectBuildStage(NoOpRunnerLogger) {
                    override fun extractClasspath(projectDir: Path): List<Path> = listOf(fakeJar)

                    override fun tryCompile(projectDir: Path): Boolean {
                        tryCompileCalled = true
                        return false
                    }
                }

            projectDir.resolve("Hello.java").writeText("class Hello {}")
            lstBuilder(buildTool = trackingBuildTool2).build(
                projectDir = projectDir
            )

            assertTrue(
                tryCompileCalled,
                "tryCompile should be called when Stage 1 succeeds but class dirs are absent"
            )
        }

        test("tryCompile is NOT called when class directories already exist") {
            var tryCompileCalled = false
            val fakeJar = projectDir.resolve("fake.jar").also { it.writeText("") }

            projectDir.resolve("target/classes").createDirectories()

            val trackingBuildTool2 =
                object : ProjectBuildStage(NoOpRunnerLogger) {
                    override fun extractClasspath(projectDir: Path): List<Path> = listOf(fakeJar)

                    override fun tryCompile(projectDir: Path): Boolean {
                        tryCompileCalled = true
                        return false
                    }
                }

            projectDir.resolve("Hello.java").writeText("class Hello {}")
            lstBuilder(buildTool = trackingBuildTool2).build(
                projectDir = projectDir
            )

            assertTrue(
                !tryCompileCalled,
                "tryCompile should NOT be called when class directories already exist"
            )
        }

        test("tryCompile failure is non-fatal and parsing continues") {
            val fakeJar = projectDir.resolve("fake.jar").also { it.writeText("") }

            val failingCompileTool =
                object : ProjectBuildStage(NoOpRunnerLogger) {
                    override fun extractClasspath(projectDir: Path): List<Path> = listOf(fakeJar)

                    override fun tryCompile(projectDir: Path): Boolean = false
                }

            projectDir.resolve("Hello.java").writeText("class Hello {}")
            val sources =
                lstBuilder(buildTool = failingCompileTool).build(
                    projectDir = projectDir
                ).sourceFiles

            assertEquals(
                1,
                sources.size,
                "Parsing should succeed even when tryCompile returns false"
            )
        }

        test("class dirs produced by tryCompile are included in classpath") {
            val fakeJar = projectDir.resolve("fake.jar").also { it.writeText("") }

            val compilingBuildTool =
                object : ProjectBuildStage(NoOpRunnerLogger) {
                    override fun extractClasspath(projectDir: Path): List<Path> = listOf(fakeJar)

                    override fun tryCompile(projectDir: Path): Boolean {
                        projectDir.resolve("target/classes").createDirectories()
                        return true
                    }
                }

            projectDir.resolve("Hello.java").writeText("class Hello {}")
            val sources =
                lstBuilder(buildTool = compilingBuildTool).build(
                    projectDir = projectDir
                ).sourceFiles

            assertEquals(
                1,
                sources.size,
                "Parsing should succeed after tryCompile creates class directories"
            )
        }

        test("tryCompile is NOT called when stage 1 returns null") {
            var tryCompileCalled = false

            val nullReturningBuildTool =
                object : ProjectBuildStage(NoOpRunnerLogger) {
                    override fun extractClasspath(projectDir: Path): List<Path>? = null

                    override fun tryCompile(projectDir: Path): Boolean {
                        tryCompileCalled = true
                        return false
                    }
                }

            lstBuilder(buildTool = nullReturningBuildTool).build(
                projectDir = projectDir
            )

            assertTrue(
                !tryCompileCalled,
                "tryCompile should NOT be called when Stage 1 returns null"
            )
        }

        // ─── Gradle DSL classpath resolution ─────────────────────────────────────

        test("resolveGradleDslClasspath returns empty list when no Gradle installation found") {
            val builder = lstBuilder()
            val emptyProject = Files.createTempDirectory("gradle-dsl-test-")
            try {
                val gradleHome = System.getenv("GRADLE_HOME")
                if (gradleHome.isNullOrBlank()) {
                    val distsRoot =
                        Path.of(System.getProperty("user.home")).resolve(".gradle/wrapper/dists")
                    if (!distsRoot.toFile().exists()) {
                        val result = builder.resolveGradleDslClasspath(emptyProject)
                        assertTrue(
                            result.isEmpty(),
                            "Should return empty list when no Gradle found"
                        )
                    }
                }
            } finally {
                emptyProject.toFile().deleteRecursively()
            }
        }

        test("resolveGradleDslClasspath finds distribution via wrapper properties") {
            val wrapperDir = projectDir.resolve("gradle/wrapper").also {
                it.createDirectories()
            }
            val distsRoot =
                Path.of(System.getProperty("user.home")).resolve(".gradle/wrapper/dists")
            val cachedVersion = if (distsRoot.toFile().exists()) {
                distsRoot.toFile()
                    .listFiles { f -> f.isDirectory && f.name.startsWith("gradle-") }
                    ?.firstOrNull()
                    ?.name
                    ?.removePrefix("gradle-")
                    ?.substringBeforeLast("-")
            } else {
                null
            }

            if (cachedVersion != null) {
                wrapperDir.resolve("gradle-wrapper.properties").toFile().writeText(
                    "distributionUrl=https\\://services.gradle.org/distributions/gradle-$cachedVersion-bin.zip\n"
                )
                val builder = lstBuilder()
                val jars = builder.resolveGradleDslClasspath(projectDir)
                assertTrue(jars.isNotEmpty(), "Should find Gradle JARs for version $cachedVersion")
                assertTrue(
                    jars.all { it.toString().endsWith(".jar") },
                    "All resolved paths should be JARs"
                )
            }
        }

        test("resolveGradleDslClasspath returns only lib/ JARs, not plugins or agents") {
            val distsRoot =
                Path.of(System.getProperty("user.home")).resolve(".gradle/wrapper/dists")
            if (!distsRoot.toFile().exists()) return@test

            val builder = lstBuilder()
            val jars = builder.resolveGradleDslClasspath(projectDir)
            if (jars.isEmpty()) return@test

            assertTrue(
                jars.none { it.toString().contains("/lib/plugins/") },
                "lib/plugins/ JARs should not be included"
            )
            assertTrue(
                jars.none { it.toString().contains("/lib/agents/") },
                "lib/agents/ JARs should not be included"
            )
        }

        // ─── Classpath empty warning ──────────────────────────────────────────────

        test("warn is emitted when classpath is empty and JVM source files are present") {
            projectDir.resolve("Hello.java").writeText("class Hello {}")

            val warnings = mutableListOf<String>()
            val capturingLogger =
                object : RunnerLogger by NoOpRunnerLogger {
                    override fun warn(message: String) {
                        warnings.add(message)
                    }
                }

            lstBuilder(logger = capturingLogger).build(
                projectDir = projectDir
            )

            assertTrue(
                warnings.any { it.contains("Classpath resolution failed across all 4 stages") },
                "Expected classpath-failure warning but got: $warnings"
            )
        }

        test("warn is NOT emitted when classpath is empty but no JVM source files are present") {
            projectDir.resolve("config.yaml").writeText("key: value")

            val warnings = mutableListOf<String>()
            val capturingLogger =
                object : RunnerLogger by NoOpRunnerLogger {
                    override fun warn(message: String) {
                        warnings.add(message)
                    }
                }

            lstBuilder(logger = capturingLogger).build(
                projectDir = projectDir
            )

            assertFalse(
                warnings.any { it.contains("Classpath resolution failed across all 4 stages") },
                "Classpath-failure warning should not be emitted for non-JVM projects"
            )
        }

        test("skips classpath stages 1-4 when no JVM sources collected") {
            // Project contains only YAML — no JVM source survives the file walk, so the four
            // classpath stages must be skipped entirely. Each stage's overrideable method
            // would record an invocation if called.
            projectDir.resolve("config.yaml").writeText("key: value")

            var stage1Calls = 0
            var stage2Calls = 0
            var stage3Calls = 0
            var stage4Calls = 0

            val trackingBuildTool =
                object : ProjectBuildStage(NoOpRunnerLogger) {
                    override fun extractClasspath(projectDir: Path): List<Path>? {
                        stage1Calls++
                        return null
                    }
                }
            val trackingDepStage =
                object : DependencyResolutionStage(
                    AetherContext.build(
                        projectDir.resolve("cache").resolve("repository"),
                        logger = NoOpRunnerLogger
                    ),
                    NoOpRunnerLogger
                ) {
                    override fun resolveClasspath(projectDir: Path): ClasspathResolutionResult {
                        stage2Calls++
                        return ClasspathResolutionResult(emptyList())
                    }
                }
            val trackingBuildFileStage =
                object : BuildFileParseStage(
                    AetherContext.build(
                        projectDir.resolve("cache").resolve("repository"),
                        logger = NoOpRunnerLogger
                    ),
                    NoOpRunnerLogger
                ) {
                    override fun resolveClasspath(projectDir: Path): List<Path> {
                        stage3Calls++
                        return emptyList()
                    }
                }
            val infoMessages = mutableListOf<String>()
            val capturingLogger =
                object : RunnerLogger by NoOpRunnerLogger {
                    override fun info(message: String) {
                        infoMessages.add(message)
                    }
                }
            val builder =
                object : LstBuilder(
                    logger = capturingLogger,
                    cacheDir = projectDir.resolve("cache"),
                    toolConfig = toolConfig,
                    projectBuildStage = trackingBuildTool,
                    depResolutionStage = trackingDepStage,
                    buildFileParseStage = trackingBuildFileStage
                ) {
                    override fun createLocalRepositoryStage(
                        projectDir: Path
                    ): LocalRepositoryStage =
                        object : LocalRepositoryStage(projectDir, NoOpRunnerLogger) {
                            override fun findAvailableJars(
                                declaredCoordinates: List<String>
                            ): List<Path> {
                                stage4Calls++
                                return emptyList()
                            }
                        }
                }

            builder.build(projectDir = projectDir)

            assertEquals(0, stage1Calls, "Stage 1 must not be invoked when no JVM sources")
            assertEquals(0, stage2Calls, "Stage 2 must not be invoked when no JVM sources")
            assertEquals(0, stage3Calls, "Stage 3 must not be invoked when no JVM sources")
            assertEquals(0, stage4Calls, "Stage 4 must not be invoked when no JVM sources")
            assertTrue(
                infoMessages.any {
                    it.contains(
                        "No JVM source files in scope — skipping classpath resolution stages."
                    )
                },
                "Expected the stage-skip info log message; got: $infoMessages"
            )
        }

        test("warn is NOT emitted when stage 1 resolves a non-empty classpath") {
            val fakeJar = projectDir.resolve("fake.jar").also { it.writeText("") }
            val successfulBuildTool =
                object : ProjectBuildStage(NoOpRunnerLogger) {
                    override fun extractClasspath(projectDir: Path): List<Path> = listOf(fakeJar)
                }
            projectDir.resolve("Hello.java").writeText("class Hello {}")

            val warnings = mutableListOf<String>()
            val capturingLogger =
                object : RunnerLogger by NoOpRunnerLogger {
                    override fun warn(message: String) {
                        warnings.add(message)
                    }
                }

            lstBuilder(buildTool = successfulBuildTool, logger = capturingLogger).build(
                projectDir = projectDir
            )

            assertFalse(
                warnings.any { it.contains("Classpath resolution failed across all 4 stages") },
                "Classpath-failure warning should not be emitted when stage 1 succeeds"
            )
        }

        // ─── projectClassDirs — subproject support ────────────────────────────────

        test("projectClassDirs returns root Gradle class directories") {
            val classDir = projectDir.resolve("build/classes/kotlin/main")
            classDir.createDirectories()

            val result = lstBuilder().projectClassDirs(projectDir)

            assertTrue(
                result.contains(classDir),
                "Should include root Gradle kotlin/main class dir"
            )
        }

        test("projectClassDirs returns root Maven class directories") {
            val classDir = projectDir.resolve("target/classes")
            classDir.createDirectories()

            val result = lstBuilder().projectClassDirs(projectDir)

            assertTrue(result.contains(classDir), "Should include root Maven target/classes")
        }

        test("projectClassDirs includes Gradle subproject class directories") {
            val coreClassDir = projectDir.resolve("core/build/classes/kotlin/main")
            coreClassDir.createDirectories()
            val apiClassDir = projectDir.resolve("api/build/classes/java/main")
            apiClassDir.createDirectories()

            val result = lstBuilder().projectClassDirs(projectDir)

            assertTrue(result.contains(coreClassDir), "Should include core subproject class dir")
            assertTrue(result.contains(apiClassDir), "Should include api subproject class dir")
        }

        test("projectClassDirs includes nested Gradle subproject class directories") {
            val nestedClassDir = projectDir.resolve("services/api/build/classes/java/main")
            nestedClassDir.createDirectories()

            val result = lstBuilder().projectClassDirs(projectDir)

            assertTrue(
                result.contains(nestedClassDir),
                "Should include nested Gradle subproject class dir"
            )
        }

        test("projectClassDirs includes Maven submodule class directories") {
            val moduleClassDir = projectDir.resolve("module-a/target/classes")
            moduleClassDir.createDirectories()

            val result = lstBuilder().projectClassDirs(projectDir)

            assertTrue(
                result.contains(moduleClassDir),
                "Should include Maven submodule target/classes"
            )
        }

        test("projectClassDirs excludes non-existent directories") {
            // No class dirs created at all
            val result = lstBuilder().projectClassDirs(projectDir)

            assertTrue(result.isEmpty(), "Should return empty list when no class dirs exist")
        }

        test("projectClassDirs does not scan hidden subdirectories") {
            // Create a class dir inside a hidden subdirectory — should be ignored
            val hiddenClassDir = projectDir.resolve(".hidden/build/classes/kotlin/main")
            hiddenClassDir.createDirectories()

            val result = lstBuilder().projectClassDirs(projectDir)

            assertFalse(result.contains(hiddenClassDir), "Should not scan hidden subdirectories")
        }

        test("projectClassDirs excludes hidden directories at nested depth") {
            val hiddenNestedClassDir = projectDir.resolve(
                "services/.hidden/api/build/classes/java/main"
            )
            hiddenNestedClassDir.createDirectories()

            val result = lstBuilder().projectClassDirs(projectDir)

            assertFalse(
                result.contains(hiddenNestedClassDir),
                "Should ignore nested hidden directories when scanning class dirs"
            )
        }

        // ─── Parse failure warnings ───────────────────────────────────────────────

        test("warn is emitted when a file fails to parse") {
            // XML uses a strict SAX parser — truly invalid XML triggers ctx.getOnError()
            projectDir.resolve("valid.xml").writeText("<root><item>ok</item></root>")
            projectDir.resolve("broken.xml").writeText("<<< not xml at all >>>")

            val warnings = mutableListOf<String>()
            val capturingLogger =
                object : RunnerLogger by NoOpRunnerLogger {
                    override fun warn(message: String) {
                        warnings.add(message)
                    }
                }

            lstBuilder(logger = capturingLogger).build(
                projectDir = projectDir
            )

            assertTrue(
                warnings.any { it.startsWith("Parse warning:") },
                "Expected a 'Parse warning:' message but got: $warnings"
            )
        }

        test("parse failure warning includes the error reason") {
            projectDir.resolve("broken.xml").writeText("<<< not xml at all >>>")

            val warnings = mutableListOf<String>()
            val capturingLogger =
                object : RunnerLogger by NoOpRunnerLogger {
                    override fun warn(message: String) {
                        warnings.add(message)
                    }
                }

            lstBuilder(logger = capturingLogger).build(
                projectDir = projectDir
            )

            val parseWarning = warnings.firstOrNull { it.startsWith("Parse warning:") }
            assertNotNull(parseWarning, "Expected 'Parse warning:' but got: $warnings")
            assertTrue(
                parseWarning.length > "Parse warning: ".length,
                "Warning should include a reason, got: $parseWarning"
            )
        }

        test("parse failure reports missing file path when file is dropped from parser output") {
            // XML uses a strict SAX parser that may drop invalid files from output
            projectDir.resolve("valid.xml").writeText("<root/>")
            projectDir.resolve("broken.xml").writeText("<<< not xml at all >>>")

            val warnings = mutableListOf<String>()
            val capturingLogger =
                object : RunnerLogger by NoOpRunnerLogger {
                    override fun warn(message: String) {
                        warnings.add(message)
                    }
                }

            val sources = lstBuilder(logger = capturingLogger).build(
                projectDir = projectDir
            ).sourceFiles

            // If the XML parser drops the broken file, reportParseFailures should warn about it
            if (sources.size == 1) {
                assertTrue(
                    warnings.any { it.contains("broken.xml") },
                    "When file is dropped, expected warning mentioning 'broken.xml' but got: $warnings"
                )
            }
        }

        // ─── ExecutionDiagnostics ─────────────────────────────────────────────────

        test(
            "executionDiagnostics stageUsed is BUILD_TOOL and resolvedJarCount > 0 when stage 1 succeeds"
        ) {
            val fakeJar = projectDir.resolve("fake.jar").also { it.writeText("") }
            val successBuildTool =
                object : ProjectBuildStage(NoOpRunnerLogger) {
                    override fun extractClasspath(projectDir: Path): List<Path> = listOf(fakeJar)
                }
            projectDir.resolve("Hello.java").writeText("class Hello {}")

            val lstBuildResult =
                lstBuilder(buildTool = successBuildTool).build(
                    projectDir = projectDir
                )

            assertEquals(
                UsedExecutionStage.BUILD_TOOL,
                lstBuildResult.executionDiagnostics.stageUsed,
                "Stage 1 success should set stageUsed to BUILD_TOOL"
            )
            assertTrue(
                lstBuildResult.executionDiagnostics.resolvedJarCount > 0,
                "resolvedJarCount should be > 0 when stage 1 returns JARs"
            )
        }

        test(
            "executionDiagnostics stageUsed is null and resolvedJarCount is 0 when all stages empty"
        ) {
            projectDir.resolve("Hello.java").writeText("class Hello {}")

            val lstBuildResult =
                lstBuilder().build(
                    projectDir = projectDir
                )

            assertNull(
                lstBuildResult.executionDiagnostics.stageUsed,
                "All stages empty → stageUsed should be null (blind run)"
            )
            assertEquals(
                0,
                lstBuildResult.executionDiagnostics.resolvedJarCount,
                "All stages empty → resolvedJarCount should be 0"
            )
        }

        test("executionDiagnostics parsedFileCount counts successfully parsed files") {
            projectDir.resolve("Hello.java").writeText("class Hello {}")
            projectDir.resolve("config.yaml").writeText("key: value")

            val lstBuildResult =
                lstBuilder().build(
                    projectDir = projectDir
                )

            assertEquals(
                2,
                lstBuildResult.executionDiagnostics.parsedFileCount,
                "Two parseable files should be counted as successfully parsed"
            )
        }

        test("executionDiagnostics parsedFileCount is zero when everything is excluded") {
            projectDir.resolve("Hello.java").writeText("class Hello {}")
            projectDir.resolve("config.yaml").writeText("key: value")

            val lstBuildResult =
                lstBuilder().build(
                    projectDir = projectDir,
                    excludePaths = listOf("Hello.java", "config.yaml")
                )

            assertEquals(
                0,
                lstBuildResult.executionDiagnostics.parsedFileCount,
                "Excluded files should leave a measured count of zero"
            )
            assertTrue(
                lstBuildResult.executionDiagnostics.parseFailures.isEmpty(),
                "Empty scope should not look like a parse failure"
            )
        }

        test("executionDiagnostics stageUsed is LOCAL_REPOSITORY when only stage 4 finds JARs") {
            val fakeJar = projectDir.resolve("cached.jar").also { it.writeText("") }
            val noOpDepStage =
                object : DependencyResolutionStage(
                    AetherContext.build(
                        projectDir.resolve("cache").resolve("repository"),
                        logger = NoOpRunnerLogger
                    ),
                    NoOpRunnerLogger
                ) {
                    override fun resolveClasspath(projectDir: Path): ClasspathResolutionResult =
                        ClasspathResolutionResult(emptyList())
                }
            val noOpBuildFileStage =
                object : BuildFileParseStage(
                    AetherContext.build(
                        projectDir.resolve("cache").resolve("repository"),
                        logger = NoOpRunnerLogger
                    ),
                    NoOpRunnerLogger
                ) {
                    override fun resolveClasspath(projectDir: Path): List<Path> = emptyList()
                }
            val builderWithFakeStage4 =
                object : LstBuilder(
                    logger = NoOpRunnerLogger,
                    cacheDir = projectDir.resolve("cache"),
                    toolConfig = toolConfig,
                    projectBuildStage = failingBuildTool,
                    depResolutionStage = noOpDepStage,
                    buildFileParseStage = noOpBuildFileStage
                ) {
                    override fun createLocalRepositoryStage(
                        projectDir: Path
                    ): LocalRepositoryStage =
                        object : LocalRepositoryStage(projectDir, NoOpRunnerLogger) {
                            override fun findAvailableJars(
                                declaredCoordinates: List<String>
                            ): List<Path> = listOf(fakeJar)
                        }
                }

            projectDir.resolve("Hello.java").writeText("class Hello {}")
            val lstBuildResult =
                builderWithFakeStage4.build(
                    projectDir = projectDir
                )

            assertEquals(
                UsedExecutionStage.LOCAL_REPOSITORY,
                lstBuildResult.executionDiagnostics.stageUsed,
                "Stage 4 with JARs → stageUsed should be LOCAL_REPOSITORY"
            )
            assertEquals(
                1,
                lstBuildResult.executionDiagnostics.resolvedJarCount,
                "Stage 4 with 1 JAR → resolvedJarCount should be 1"
            )
        }

        // ─── Generic parser-level failure collection ─────────────────────────────

        /**
         * Builds a no-op DependencyResolutionStage with an in-tree cache. Used by helpers
         * below that construct anonymous [LstBuilder] subclasses.
         */
        fun noOpDepStage(): DependencyResolutionStage = object : DependencyResolutionStage(
            AetherContext.build(
                projectDir.resolve("cache").resolve("repository"),
                logger = NoOpRunnerLogger
            ),
            NoOpRunnerLogger
        ) {
            override fun resolveClasspath(projectDir: Path): ClasspathResolutionResult =
                ClasspathResolutionResult(emptyList())
        }

        fun noOpBuildFileStage(): BuildFileParseStage = object : BuildFileParseStage(
            AetherContext.build(
                projectDir.resolve("cache").resolve("repository"),
                logger = NoOpRunnerLogger
            ),
            NoOpRunnerLogger
        ) {
            override fun resolveClasspath(projectDir: Path): List<Path> = emptyList()
        }

        /**
         * Builds a [Parser] whose [Parser.parseInputs] returns whatever [behavior] produces.
         * The stub is generic — used to stand in for JavaParser / XmlParser etc. in tests.
         */
        fun stubParser(behavior: (List<Parser.Input>, Path?) -> List<SourceFile>): Parser =
            object : Parser {
                override fun parseInputs(
                    sources: Iterable<Parser.Input>,
                    relativeTo: Path?,
                    ctx: ExecutionContext
                ): java.util.stream.Stream<SourceFile> {
                    val inputs = sources.toList()
                    return behavior(inputs, relativeTo).stream()
                }

                override fun accept(path: Path): Boolean = true

                override fun sourcePathFromSourceText(prefix: Path, sourceCode: String): Path =
                    prefix.resolve("Synthetic.java")
            }

        /** Builds an [LstBuilder] whose Java parser factory returns [stub]. */
        fun lstBuilderWithJavaStub(stub: Parser): LstBuilder = object : LstBuilder(
            logger = NoOpRunnerLogger,
            cacheDir = projectDir.resolve("cache"),
            toolConfig = toolConfig,
            projectBuildStage = failingBuildTool,
            depResolutionStage = noOpDepStage(),
            buildFileParseStage = noOpBuildFileStage()
        ) {
            override fun buildJavaParser(classpath: List<Path>, typeCache: JavaTypeCache): Parser =
                stub
        }

        test("ParseError SourceFile in parser output is recorded as a ParseFailure") {
            projectDir.resolve("Broken.java").writeText("class Broken { /* unterminated")

            val builder = lstBuilderWithJavaStub(
                stubParser { inputs, relativeTo ->
                    val input = inputs.single()
                    val rel = (relativeTo ?: projectDir).relativize(input.path).normalize()
                    val markers = Markers.EMPTY.addIfAbsent(
                        ParseExceptionResult(
                            java.util.UUID.randomUUID(),
                            "JavaParser",
                            "ParseException",
                            "unterminated comment",
                            null
                        )
                    )
                    listOf(
                        ParseError(
                            java.util.UUID.randomUUID(),
                            markers,
                            rel,
                            null,
                            null,
                            false,
                            null,
                            "class Broken { /* unterminated",
                            null
                        )
                    )
                }
            )

            val result = builder.build(
                projectDir = projectDir
            )

            val failures = result.executionDiagnostics.parseFailures
            assertEquals(1, failures.size, "Exactly one ParseFailure should be recorded")
            val failure = failures.single()
            assertEquals("Broken.java", failure.path)
            assertEquals("JavaParser", failure.parser)
            assertTrue(
                failure.reason.contains("unterminated", ignoreCase = true),
                "Reason should reflect the ParseExceptionResult message, got: ${failure.reason}"
            )
            // ParseError SourceFile still lives in the LST so callers can inspect it.
            assertTrue(
                result.sourceFiles.any { it is ParseError },
                "ParseError SourceFile should remain in the LST"
            )
            assertEquals(
                0,
                result.executionDiagnostics.parsedFileCount,
                "ParseError stubs should not be counted as successfully parsed files"
            )
        }

        test("silently dropped Java input is recorded as a ParseFailure") {
            projectDir.resolve("Kept.java").writeText("class Kept {}")
            projectDir.resolve("Dropped.java").writeText("class Dropped {}")

            val builder = lstBuilderWithJavaStub(
                stubParser { inputs, _ ->
                    // Drop "Dropped.java" entirely; return only "Kept.java" as a ParseError stub
                    // so we have a valid SourceFile for the kept input.
                    inputs
                        .filter { it.path.fileName.toString() == "Kept.java" }
                        .map { input ->
                            ParseError(
                                java.util.UUID.randomUUID(),
                                Markers.EMPTY,
                                projectDir.relativize(input.path).normalize(),
                                null,
                                null,
                                false,
                                null,
                                "class Kept {}",
                                null
                            ) as SourceFile
                        }
                }
            )

            val result = builder.build(
                projectDir = projectDir
            )

            val dropFailures =
                result.executionDiagnostics.parseFailures.filter { it.path == "Dropped.java" }
            assertEquals(1, dropFailures.size, "Dropped.java should be recorded once")
            assertEquals("JavaParser", dropFailures.single().parser)
            assertTrue(
                dropFailures.single().reason.contains("dropped", ignoreCase = true),
                "Drop reason should mention 'dropped', got: ${dropFailures.single().reason}"
            )
        }

        test("thrown exception from a parser is recorded and the build continues") {
            projectDir.resolve("Boom.java").writeText("class Boom {}")
            projectDir.resolve("config.yaml").writeText("key: value")

            val builder = lstBuilderWithJavaStub(
                stubParser { _, _ -> throw IllegalStateException("boom") }
            )

            val result = builder.build(projectDir = projectDir)

            val javaFailures =
                result.executionDiagnostics.parseFailures.filter { it.parser == "JavaParser" }
            assertEquals(
                1,
                javaFailures.size,
                "One ParseFailure per .java input should be recorded when the parser throws"
            )
            assertEquals("Boom.java", javaFailures.single().path)
            assertTrue(
                javaFailures.single().reason.contains("boom"),
                "Reason should contain the exception message, got: ${javaFailures.single().reason}"
            )
            // The YAML file must still be parsed — the JavaParser throw must not abort the build.
            assertTrue(
                result.sourceFiles.any { it.sourcePath.toString().endsWith("config.yaml") },
                "config.yaml should still be parsed when JavaParser throws"
            )
        }

        test("executionDiagnostics parsedFileCount is zero when every file fails parsing") {
            projectDir.resolve("Boom.java").writeText("class Boom {}")

            val builder = lstBuilderWithJavaStub(
                stubParser { _, _ -> throw IllegalStateException("boom") }
            )

            val result = builder.build(projectDir = projectDir)

            assertEquals(
                0,
                result.executionDiagnostics.parsedFileCount,
                "A total parse wipeout should be distinguishable from a no-op recipe run"
            )
            assertTrue(
                result.executionDiagnostics.parseFailures.isNotEmpty(),
                "A total parse wipeout should carry at least one parse failure"
            )
        }

        test("Gradle DSL parser failure records a GradleParser ParseFailure and falls back") {
            projectDir.resolve("build.gradle.kts").writeText(
                """
                plugins { id("java") }
                """.trimIndent()
            )

            val builder = object : LstBuilder(
                logger = NoOpRunnerLogger,
                cacheDir = projectDir.resolve("cache"),
                toolConfig = toolConfig,
                projectBuildStage = failingBuildTool,
                depResolutionStage = noOpDepStage(),
                buildFileParseStage = noOpBuildFileStage()
            ) {
                override fun buildGradleKtsParser(
                    classpath: List<Path>,
                    gradleDslClasspath: List<Path>,
                    typeCache: JavaTypeCache
                ): org.openrewrite.gradle.GradleParser =
                    throw IllegalStateException("gradle ktS parser boom")
            }

            val result = builder.build(
                projectDir = projectDir
            )

            val gradleFailures =
                result.executionDiagnostics.parseFailures.filter { it.parser == "GradleParser" }
            assertEquals(
                1,
                gradleFailures.size,
                "GradleParser failure should be recorded exactly once"
            )
            assertEquals("build.gradle.kts", gradleFailures.single().path)
            // The fallback KotlinParser should still produce a SourceFile for the script.
            assertTrue(
                result.sourceFiles.any {
                    it.sourcePath.toString().endsWith("build.gradle.kts")
                },
                "Fallback parser should still place build.gradle.kts in the LST"
            )
        }

        test("ParseError in MavenParser output is recorded even when no exception is thrown") {
            // Regression test for the case where MavenParser returns a ParseError SourceFile
            // (or silently drops a pom) without throwing. The Maven path used to skip the
            // failure scan and miss these cases.
            projectDir.resolve("pom.xml").writeText(
                """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>silent</artifactId>
                  <version>1.0.0</version>
                </project>
                """.trimIndent()
            )

            val builder =
                object : LstBuilder(
                    logger = NoOpRunnerLogger,
                    cacheDir = projectDir.resolve("cache"),
                    toolConfig = toolConfig,
                    projectBuildStage = failingBuildTool,
                    depResolutionStage = noOpDepStage(),
                    buildFileParseStage = noOpBuildFileStage()
                ) {
                    override fun buildMavenParser(): MavenParser =
                        object : MavenParser(emptyList(), emptyMap(), false) {
                            override fun parseInputs(
                                sources: Iterable<Parser.Input>,
                                relativeTo: Path?,
                                ctx: ExecutionContext
                            ): java.util.stream.Stream<SourceFile> {
                                // Return a ParseError instead of an Xml.Document, no throw.
                                val cwd = relativeTo ?: projectDir
                                return sources
                                    .toList()
                                    .map { input ->
                                        val rel = cwd.relativize(input.path).normalize()
                                        val markers = Markers.EMPTY.addIfAbsent(
                                            ParseExceptionResult(
                                                java.util.UUID.randomUUID(),
                                                "MavenParser",
                                                "ParseException",
                                                "malformed pom marker",
                                                null
                                            )
                                        )
                                        ParseError(
                                            java.util.UUID.randomUUID(),
                                            markers,
                                            rel,
                                            null,
                                            null,
                                            false,
                                            null,
                                            "<project/>",
                                            null
                                        ) as SourceFile
                                    }
                                    .stream()
                            }
                        }
                }

            val result = builder.build(
                projectDir = projectDir
            )

            val mavenFailures =
                result.executionDiagnostics.parseFailures.filter { it.parser == "MavenParser" }
            assertEquals(
                1,
                mavenFailures.size,
                "MavenParser ParseError output should be recorded as a ParseFailure"
            )
            assertEquals("pom.xml", mavenFailures.single().path)
            assertTrue(
                mavenFailures.single().reason.contains("malformed pom marker"),
                "Reason should reflect the ParseExceptionResult message"
            )
        }

        test("fatal Errors propagate instead of being recorded as ParseFailure") {
            // OutOfMemoryError / StackOverflowError signal an invalid JVM state.
            // Continuing the build would emit misleading results.
            projectDir.resolve("Hello.java").writeText("class Hello {}")

            val builder = lstBuilderWithJavaStub(
                stubParser { _, _ -> throw OutOfMemoryError("simulated") }
            )

            val thrown = kotlin.runCatching {
                builder.build(projectDir = projectDir)
            }.exceptionOrNull()

            assertNotNull(thrown, "Fatal Error must not be swallowed by parseAndRecord")
            assertTrue(
                thrown is OutOfMemoryError && thrown.message == "simulated",
                "Expected OutOfMemoryError to propagate, got: $thrown"
            )
        }

        test("pom that also fails XmlParser produces two ParseFailure entries") {
            projectDir.resolve("pom.xml").writeText(
                """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>bad</artifactId>
                  <version>1.0.0</version>
                </project>
                """.trimIndent()
            )

            val builder = object : LstBuilder(
                logger = NoOpRunnerLogger,
                cacheDir = projectDir.resolve("cache"),
                toolConfig = toolConfig,
                projectBuildStage = failingBuildTool,
                depResolutionStage = noOpDepStage(),
                buildFileParseStage = noOpBuildFileStage()
            ) {
                override fun buildMavenParser(): MavenParser =
                    object : MavenParser(emptyList(), emptyMap(), false) {
                        override fun parseInputs(
                            sources: Iterable<Parser.Input>,
                            relativeTo: Path?,
                            ctx: ExecutionContext
                        ): java.util.stream.Stream<SourceFile> = throw IllegalArgumentException(
                            "Illegal character in path at index 7: pom.xml",
                            java.net.URISyntaxException("bad", "Illegal character")
                        )
                    }

                override fun buildXmlParser(): Parser =
                    stubParser { _, _ -> throw IllegalStateException("xml parser boom") }
            }

            val result = builder.build(
                projectDir = projectDir
            )

            val pomFailures =
                result.executionDiagnostics.parseFailures.filter { it.path == "pom.xml" }
            assertEquals(
                2,
                pomFailures.size,
                "Both MavenParser and XmlParser failures should be recorded for the same pom"
            )
            assertTrue(
                pomFailures.any { it.parser == "MavenParser" },
                "MavenParser failure should be present"
            )
            assertTrue(
                pomFailures.any { it.parser == "XmlParser" },
                "XmlParser fallback failure should be present"
            )
        }

        test("malformed Maven coords from stages bubble up as parseFailures") {
            // Stage 2 stub records two malformed coordinates via the new failure-sink
            // overload. Stage 3 is no-op. The integration test confirms that LstBuilder
            // threads its parseFailures accumulator into both stages.
            projectDir.resolve("Hello.java").writeText("class Hello {}")

            val depStageStub =
                object : DependencyResolutionStage(
                    AetherContext.build(
                        projectDir.resolve("cache").resolve("repository"),
                        logger = NoOpRunnerLogger
                    ),
                    NoOpRunnerLogger
                ) {
                    override fun resolveClasspath(
                        projectDir: Path,
                        parseFailures: MutableList<io.github.skhokhlov.rewriterunner.ParseFailure>
                    ): ClasspathResolutionResult {
                        parseFailures +=
                            io.github.skhokhlov.rewriterunner.ParseFailure(
                                path = "com.example:bad name:1.0",
                                reason = "illegal Maven coordinate",
                                parser = "DependencyResolutionStage"
                            )
                        parseFailures +=
                            io.github.skhokhlov.rewriterunner.ParseFailure(
                                path = "com.example:also bad:2.0",
                                reason = "illegal Maven coordinate",
                                parser = "DependencyResolutionStage"
                            )
                        return ClasspathResolutionResult(emptyList())
                    }
                }

            val builder =
                LstBuilder(
                    logger = NoOpRunnerLogger,
                    cacheDir = projectDir.resolve("cache"),
                    toolConfig = toolConfig,
                    projectBuildStage = failingBuildTool,
                    depResolutionStage = depStageStub,
                    buildFileParseStage = noOpBuildFileStage()
                )

            val result = builder.build(
                projectDir = projectDir
            )

            val coordFailures =
                result.executionDiagnostics.parseFailures.filter {
                    it.parser == "DependencyResolutionStage"
                }
            assertEquals(
                2,
                coordFailures.size,
                "Both malformed coordinates from Stage 2 should appear in ExecutionDiagnostics"
            )
            assertTrue(
                coordFailures.any { it.path == "com.example:bad name:1.0" }
            )
            assertTrue(
                coordFailures.any { it.path == "com.example:also bad:2.0" }
            )
        }
    })
