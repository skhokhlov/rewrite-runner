package io.github.skhokhlov.rewriterunner.lst

import io.github.skhokhlov.rewriterunner.AetherContext
import io.github.skhokhlov.rewriterunner.NoOpRunnerLogger
import io.github.skhokhlov.rewriterunner.config.ParseConfig
import io.github.skhokhlov.rewriterunner.config.ToolConfig
import io.kotest.core.spec.style.FunSpec
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.openrewrite.maven.tree.MavenResolutionResult

class LstBuilderTest :
    FunSpec({
        var projectDir: Path = Path.of("")

        beforeEach { projectDir = Files.createTempDirectory("lstb-") }

        afterEach { projectDir.toFile().deleteRecursively() }

        val toolConfig = ToolConfig(logger = NoOpRunnerLogger)

        /** A BuildToolStage that always returns null (simulates broken build tool). */
        val failingBuildTool =
            object : BuildToolStage(NoOpRunnerLogger) {
                override fun extractClasspath(projectDir: Path): List<Path>? = null
            }

        fun lstBuilder(buildTool: BuildToolStage = failingBuildTool): LstBuilder {
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
            return LstBuilder(
                logger = NoOpRunnerLogger,
                cacheDir = projectDir.resolve("cache"),
                toolConfig = toolConfig,
                buildToolStage = buildTool,
                depResolutionStage = noOpDepStage
            )
        }

        // ─── Extension filtering ──────────────────────────────────────────────────

        test("includeExtensions CLI flag restricts parsed file types") {
            projectDir.resolve("Hello.java").writeText("class Hello {}")
            projectDir.resolve("config.yaml").writeText("key: value")
            projectDir.resolve("data.json").writeText("{}")

            val sources = lstBuilder().build(
                projectDir = projectDir,
                includeExtensionsCli = listOf(".java")
            )

            assertEquals(1, sources.size, "Only .java file should be parsed")
            assertTrue(sources.first().sourcePath.toString().endsWith(".java"))
        }

        test("excludeExtensions CLI flag removes specific types from defaults") {
            projectDir.resolve("Hello.java").writeText("class Hello {}")
            projectDir.resolve("config.xml").writeText("<root/>")
            projectDir.resolve("app.properties").writeText("key=value")

            val sources =
                lstBuilder().build(
                    projectDir = projectDir,
                    excludeExtensionsCli = listOf(".xml", ".properties")
                )

            val paths = sources.map { it.sourcePath.toString() }
            assertTrue(paths.none { it.endsWith(".xml") }, "XML files should be excluded")
            assertTrue(
                paths.none { it.endsWith(".properties") },
                "Properties files should be excluded"
            )
            assertTrue(paths.any { it.endsWith(".java") }, "Java files should still be included")
        }

        test("includeExtensions in config file is respected when no CLI flag given") {
            projectDir.resolve("Hello.java").writeText("class Hello {}")
            projectDir.resolve("config.yaml").writeText("key: value")

            val config =
                ToolConfig(
                    parse = ParseConfig(includeExtensions = listOf(".yaml")),
                    logger = NoOpRunnerLogger
                )
            val noOpDepStage =
                object : DependencyResolutionStage(
                    AetherContext.build(
                        projectDir.resolve("cache").resolve("repository"),
                        logger = NoOpRunnerLogger
                    ),
                    NoOpRunnerLogger
                ) {
                    override fun resolveClasspath(projectDir: Path) =
                        ClasspathResolutionResult(emptyList())
                }
            val builder =
                LstBuilder(
                    logger = NoOpRunnerLogger,
                    cacheDir = projectDir.resolve("cache"),
                    toolConfig = config,
                    buildToolStage = failingBuildTool,
                    depResolutionStage = noOpDepStage
                )

            val sources = builder.build(projectDir = projectDir)
            assertEquals(1, sources.size)
            assertTrue(sources.first().sourcePath.toString().endsWith(".yaml"))
        }

        test("CLI includeExtensions overrides config file") {
            projectDir.resolve("Hello.java").writeText("class Hello {}")
            projectDir.resolve("config.yaml").writeText("key: value")

            val config =
                ToolConfig(
                    parse = ParseConfig(includeExtensions = listOf(".yaml")),
                    logger = NoOpRunnerLogger
                )
            val noOpDepStage =
                object : DependencyResolutionStage(
                    AetherContext.build(
                        projectDir.resolve("cache").resolve("repository"),
                        logger = NoOpRunnerLogger
                    ),
                    NoOpRunnerLogger
                ) {
                    override fun resolveClasspath(projectDir: Path) =
                        ClasspathResolutionResult(emptyList())
                }
            val builder =
                LstBuilder(
                    logger = NoOpRunnerLogger,
                    cacheDir = projectDir.resolve("cache"),
                    toolConfig = config,
                    buildToolStage = failingBuildTool,
                    depResolutionStage = noOpDepStage
                )

            // CLI flag should override the config's yaml-only setting
            val sources = builder.build(
                projectDir = projectDir,
                includeExtensionsCli = listOf(".java")
            )
            assertEquals(1, sources.size)
            assertTrue(sources.first().sourcePath.toString().endsWith(".java"))
        }

        // ─── Directory exclusion ──────────────────────────────────────────────────

        test("files inside build directory are excluded") {
            projectDir.resolve("Hello.java").writeText("class Hello {}")
            projectDir.resolve("build").createDirectories()
            projectDir.resolve("build/Generated.java").writeText("class Generated {}")

            val sources =
                lstBuilder().build(projectDir = projectDir, includeExtensionsCli = listOf(".java"))
            assertEquals(1, sources.size, "build/ files should be excluded")
            assertTrue(sources.first().sourcePath.toString() == "Hello.java")
        }

        test("files inside target directory are excluded") {
            projectDir.resolve("Hello.java").writeText("class Hello {}")
            projectDir.resolve("target").createDirectories()
            projectDir.resolve("target/Compiled.java").writeText("class Compiled {}")

            val sources =
                lstBuilder().build(projectDir = projectDir, includeExtensionsCli = listOf(".java"))
            assertEquals(1, sources.size, "target/ files should be excluded")
        }

        test("files inside node_modules are excluded") {
            projectDir.resolve("config.yaml").writeText("key: value")
            projectDir.resolve("node_modules").createDirectories()
            projectDir.resolve("node_modules/package.json").writeText("{}")

            val sources =
                lstBuilder().build(projectDir = projectDir, includeExtensionsCli = listOf(".json"))
            assertEquals(0, sources.size, "node_modules/ files should be excluded")
        }

        test("glob excludePaths patterns are applied") {
            projectDir.resolve("src").createDirectories()
            projectDir.resolve("src/Main.java").writeText("class Main {}")
            projectDir.resolve("generated").createDirectories()
            projectDir.resolve("generated/Gen.java").writeText("class Gen {}")

            val config =
                ToolConfig(
                    parse = ParseConfig(excludePaths = listOf("generated/**")),
                    logger = NoOpRunnerLogger
                )
            val noOpDepStage =
                object : DependencyResolutionStage(
                    AetherContext.build(
                        projectDir.resolve("cache").resolve("repository"),
                        logger = NoOpRunnerLogger
                    ),
                    NoOpRunnerLogger
                ) {
                    override fun resolveClasspath(projectDir: Path) =
                        ClasspathResolutionResult(emptyList())
                }
            val builder =
                LstBuilder(
                    logger = NoOpRunnerLogger,
                    cacheDir = projectDir.resolve("cache"),
                    toolConfig = config,
                    buildToolStage = failingBuildTool,
                    depResolutionStage = noOpDepStage
                )

            val sources =
                builder.build(projectDir = projectDir, includeExtensionsCli = listOf(".java"))
            assertEquals(1, sources.size)
            assertTrue(sources.first().sourcePath.toString().contains("Main"))
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

            val sources = lstBuilder().build(projectDir = projectDir)

            val paths = sources.map { it.sourcePath.toString() }
            assertTrue(paths.any { it.endsWith(".java") }, "Java should be parsed")
            assertTrue(paths.any { it.endsWith(".kt") }, "Kotlin should be parsed")
            assertTrue(
                paths.any {
                    it.endsWith(".gradle.kts")
                },
                "Gradle Kotlin DSL should be parsed"
            )
            assertTrue(paths.any { it.endsWith(".gradle") }, "Gradle Groovy DSL should be parsed")
            assertTrue(paths.any { it.endsWith(".groovy") }, "Groovy should be parsed")
            assertTrue(paths.any { it.endsWith(".yaml") }, "YAML should be parsed")
            assertTrue(paths.any { it.endsWith(".json") }, "JSON should be parsed")
            assertTrue(
                paths.any {
                    it.endsWith(".xml")
                },
                "XML (pom.xml via MavenParser) should be parsed"
            )
            assertTrue(paths.any { it.endsWith(".properties") }, "Properties should be parsed")
            assertTrue(paths.any { it.endsWith(".toml") }, "TOML should be parsed")
            assertTrue(
                paths.any { it.endsWith(".tf") },
                "Terraform (.tf) should be parsed"
            )
            assertTrue(paths.any { it.endsWith(".proto") }, "Protobuf should be parsed")
            assertTrue(paths.any { it == "Dockerfile" }, "Dockerfile should be parsed by name")
        }

        test("gradle.kts files and plain kts files are both parsed under kts extension") {
            projectDir.resolve("build.gradle.kts").writeText("// gradle kotlin dsl")
            projectDir.resolve("settings.gradle.kts").writeText("// settings")
            projectDir.resolve("script.kts").writeText("// plain kts script")

            val sources =
                lstBuilder().build(projectDir = projectDir, includeExtensionsCli = listOf(".kts"))

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
                projectDir = projectDir,
                includeExtensionsCli = listOf(".groovy", ".gradle")
            )

            val paths = sources.map { it.sourcePath.toString() }
            assertEquals(2, sources.size, "Both .groovy and .gradle files should be parsed")
            assertTrue(paths.any { it.endsWith(".groovy") }, "Groovy file should be parsed")
            assertTrue(paths.any { it.endsWith(".gradle") }, "Gradle file should be parsed")
        }

        test("yml extension is treated same as yaml") {
            projectDir.resolve("app.yml").writeText("spring:\n  port: 8080")

            val sources =
                lstBuilder().build(projectDir = projectDir, includeExtensionsCli = listOf(".yml"))
            assertEquals(1, sources.size)
        }

        // ─── TOML / HCL / Protobuf / Docker parsers ──────────────────────────────

        test("toml files are parsed") {
            projectDir.resolve("Cargo.toml").writeText(
                "[package]\nname = \"example\"\nversion = \"0.1.0\"\n"
            )

            val sources =
                lstBuilder().build(projectDir = projectDir, includeExtensionsCli = listOf(".toml"))
            assertEquals(1, sources.size, "TOML file should be parsed")
            assertTrue(sources.first().sourcePath.toString().endsWith(".toml"))
        }

        test("hcl files are parsed") {
            projectDir.resolve("config.hcl").writeText(
                "variable \"region\" {\n  default = \"us-east-1\"\n}\n"
            )

            val sources =
                lstBuilder().build(projectDir = projectDir, includeExtensionsCli = listOf(".hcl"))
            assertEquals(1, sources.size, "HCL file should be parsed")
        }

        test("tf files are parsed") {
            projectDir.resolve("main.tf").writeText(
                "provider \"aws\" {\n  region = \"us-east-1\"\n}\n"
            )

            val sources =
                lstBuilder().build(projectDir = projectDir, includeExtensionsCli = listOf(".tf"))
            assertEquals(1, sources.size, "Terraform (.tf) file should be parsed")
        }

        test("tfvars files are parsed") {
            projectDir.resolve("terraform.tfvars").writeText("region = \"us-east-1\"\n")

            val sources =
                lstBuilder().build(
                    projectDir = projectDir,
                    includeExtensionsCli = listOf(".tfvars")
                )
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
                    projectDir = projectDir,
                    includeExtensionsCli = listOf(".hcl", ".tf", ".tfvars")
                )
            assertEquals(3, sources.size, "All three HCL-family extensions should be parsed")
        }

        test("proto files are parsed") {
            projectDir.resolve("hello.proto").writeText(
                "syntax = \"proto3\";\npackage example;\nmessage Hello {\n  string name = 1;\n}\n"
            )

            val sources =
                lstBuilder().build(projectDir = projectDir, includeExtensionsCli = listOf(".proto"))
            assertEquals(1, sources.size, "Protobuf file should be parsed")
        }

        test("dockerfile with dockerfile extension is parsed") {
            projectDir.resolve("service.dockerfile").writeText(
                "FROM ubuntu:22.04\nRUN apt-get update\n"
            )

            val sources =
                lstBuilder().build(
                    projectDir = projectDir,
                    includeExtensionsCli = listOf(".dockerfile")
                )
            assertEquals(1, sources.size, ".dockerfile extension should be parsed")
        }

        test("containerfile with containerfile extension is parsed") {
            projectDir.resolve("service.containerfile").writeText(
                "FROM ubuntu:22.04\nRUN dnf install -y bash\n"
            )

            val sources =
                lstBuilder().build(
                    projectDir = projectDir,
                    includeExtensionsCli = listOf(".containerfile")
                )
            assertEquals(1, sources.size, ".containerfile extension should be parsed")
        }

        test("Dockerfile without extension is parsed when dockerfile extension is included") {
            projectDir.resolve("Dockerfile").writeText("FROM ubuntu:22.04\nRUN echo hello\n")

            val sources =
                lstBuilder().build(
                    projectDir = projectDir,
                    includeExtensionsCli = listOf(".dockerfile")
                )
            assertEquals(1, sources.size, "Dockerfile (no extension) should be parsed by name")
            assertEquals("Dockerfile", sources.first().sourcePath.toString())
        }

        test("Dockerfile.dev is parsed by name prefix when dockerfile extension is included") {
            projectDir.resolve("Dockerfile.dev").writeText("FROM ubuntu:22.04\nENV ENV=dev\n")

            val sources =
                lstBuilder().build(
                    projectDir = projectDir,
                    includeExtensionsCli = listOf(".dockerfile")
                )
            assertEquals(1, sources.size, "Dockerfile.dev should be parsed by name prefix")
        }

        test(
            "Containerfile without extension is parsed by name when dockerfile extension included"
        ) {
            projectDir.resolve("Containerfile").writeText("FROM fedora:latest\nRUN dnf -y update\n")

            val sources =
                lstBuilder().build(
                    projectDir = projectDir,
                    includeExtensionsCli = listOf(".dockerfile")
                )
            assertEquals(1, sources.size, "Containerfile (no extension) should be parsed by name")
        }

        test("Dockerfile is NOT parsed when dockerfile extension is not in effective set") {
            projectDir.resolve("Dockerfile").writeText("FROM ubuntu:22.04\n")
            projectDir.resolve("app.properties").writeText("key=value")

            // Include only .properties, so .dockerfile is not in effective set
            val sources =
                lstBuilder().build(
                    projectDir = projectDir,
                    includeExtensionsCli = listOf(".properties")
                )
            val paths = sources.map { it.sourcePath.toString() }
            assertTrue(
                paths.none { it == "Dockerfile" },
                "Dockerfile should not be parsed when .dockerfile is excluded"
            )
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
                lstBuilder().build(projectDir = projectDir, includeExtensionsCli = listOf(".xml"))
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
                lstBuilder().build(projectDir = projectDir, includeExtensionsCli = listOf(".xml"))
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
                lstBuilder().build(projectDir = projectDir, includeExtensionsCli = listOf(".xml"))
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

        test("pom.xml is NOT parsed when xml extension is excluded") {
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
                    includeExtensionsCli = listOf(".properties")
                )
            assertTrue(
                sources.none { it.sourcePath.toString() == "pom.xml" },
                "pom.xml should not be parsed when .xml is not in effective set"
            )
        }

        // ─── 3-stage pipeline fallthrough ────────────────────────────────────────

        test("stage 1 is attempted first") {
            var stage1Called = false
            val trackingBuildTool =
                object : BuildToolStage(NoOpRunnerLogger) {
                    override fun extractClasspath(projectDir: Path): List<Path>? {
                        stage1Called = true
                        return null
                    }
                }
            lstBuilder(buildTool = trackingBuildTool).build(
                projectDir = projectDir,
                includeExtensionsCli = listOf(".java")
            )
            assertTrue(stage1Called, "Stage 1 (build tool) should always be attempted first")
        }

        test("stage 1 result is used when it returns a non-null list") {
            var stage2Called = false
            val fakeJar = projectDir.resolve("fake.jar").also { it.writeText("") }

            val successfulBuildTool =
                object : BuildToolStage(NoOpRunnerLogger) {
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
                buildToolStage = successfulBuildTool,
                depResolutionStage = trackingDepStage,
                logger = NoOpRunnerLogger
            )
                .build(projectDir = projectDir, includeExtensionsCli = listOf(".java"))

            assertTrue(!stage2Called, "Stage 2 should NOT be called when Stage 1 succeeds")
        }

        test("stage 2 is attempted when stage 1 returns null") {
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
                buildToolStage = failingBuildTool,
                depResolutionStage = trackingDepStage,
                logger = NoOpRunnerLogger
            )
                .build(projectDir = projectDir, includeExtensionsCli = listOf(".java"))

            assertTrue(stage2Called, "Stage 2 should be attempted when Stage 1 fails")
        }

        test("parsing succeeds even when all classpath stages fail") {
            // Both stage 1 and stage 2 fail → stage 3 fallback → parse with empty classpath
            projectDir.resolve("Hello.java").writeText("class Hello {}")

            val sources =
                lstBuilder().build(projectDir = projectDir, includeExtensionsCli = listOf(".java"))

            // Should still parse the file even without a resolved classpath
            assertEquals(
                1,
                sources.size,
                "Java file should be parsed even without classpath (Stage 3 fallback)"
            )
        }

        // ─── Compile-on-demand (Stage 1 enhancement) ─────────────────────────────

        test("tryCompile is called when stage 1 succeeds but no class directories exist") {
            var tryCompileCalled = false
            val fakeJar = projectDir.resolve("fake.jar").also { it.writeText("") }

            val trackingBuildTool2 =
                object : BuildToolStage(NoOpRunnerLogger) {
                    override fun extractClasspath(projectDir: Path): List<Path> = listOf(fakeJar)

                    override fun tryCompile(projectDir: Path): Boolean {
                        tryCompileCalled = true
                        return false
                    }
                }

            projectDir.resolve("Hello.java").writeText("class Hello {}")
            lstBuilder(buildTool = trackingBuildTool2).build(
                projectDir = projectDir,
                includeExtensionsCli = listOf(".java")
            )

            assertTrue(
                tryCompileCalled,
                "tryCompile should be called when Stage 1 succeeds but class dirs are absent"
            )
        }

        test("tryCompile is NOT called when class directories already exist") {
            var tryCompileCalled = false
            val fakeJar = projectDir.resolve("fake.jar").also { it.writeText("") }

            // Pre-create a class directory so the tool sees it as already compiled
            projectDir.resolve("target/classes").createDirectories()

            val trackingBuildTool2 =
                object : BuildToolStage(NoOpRunnerLogger) {
                    override fun extractClasspath(projectDir: Path): List<Path> = listOf(fakeJar)

                    override fun tryCompile(projectDir: Path): Boolean {
                        tryCompileCalled = true
                        return false
                    }
                }

            projectDir.resolve("Hello.java").writeText("class Hello {}")
            lstBuilder(buildTool = trackingBuildTool2).build(
                projectDir = projectDir,
                includeExtensionsCli = listOf(".java")
            )

            assertTrue(
                !tryCompileCalled,
                "tryCompile should NOT be called when class directories already exist"
            )
        }

        test("tryCompile failure is non-fatal and parsing continues") {
            val fakeJar = projectDir.resolve("fake.jar").also { it.writeText("") }

            val failingCompileTool =
                object : BuildToolStage(NoOpRunnerLogger) {
                    override fun extractClasspath(projectDir: Path): List<Path> = listOf(fakeJar)

                    override fun tryCompile(projectDir: Path): Boolean = false // compilation fails
                }

            projectDir.resolve("Hello.java").writeText("class Hello {}")
            val sources =
                lstBuilder(buildTool = failingCompileTool).build(
                    projectDir = projectDir,
                    includeExtensionsCli = listOf(".java")
                )

            assertEquals(
                1,
                sources.size,
                "Parsing should succeed even when tryCompile returns false"
            )
        }

        test("class dirs produced by tryCompile are included in classpath") {
            val fakeJar = projectDir.resolve("fake.jar").also { it.writeText("") }

            // Simulate a build tool that produces target/classes when tryCompile is called
            val compilingBuildTool =
                object : BuildToolStage(NoOpRunnerLogger) {
                    override fun extractClasspath(projectDir: Path): List<Path> = listOf(fakeJar)

                    override fun tryCompile(projectDir: Path): Boolean {
                        projectDir.resolve("target/classes").createDirectories()
                        return true
                    }
                }

            projectDir.resolve("Hello.java").writeText("class Hello {}")
            // No exception and file is parsed — class dir created by compile is picked up
            val sources =
                lstBuilder(buildTool = compilingBuildTool).build(
                    projectDir = projectDir,
                    includeExtensionsCli = listOf(".java")
                )

            assertEquals(
                1,
                sources.size,
                "Parsing should succeed after tryCompile creates class directories"
            )
        }

        test("tryCompile is NOT called when stage 1 returns null") {
            var tryCompileCalled = false

            val nullReturningBuildTool =
                object : BuildToolStage(NoOpRunnerLogger) {
                    override fun extractClasspath(projectDir: Path): List<Path>? = null

                    override fun tryCompile(projectDir: Path): Boolean {
                        tryCompileCalled = true
                        return false
                    }
                }

            lstBuilder(buildTool = nullReturningBuildTool).build(
                projectDir = projectDir,
                includeExtensionsCli = listOf(".java")
            )

            assertTrue(
                !tryCompileCalled,
                "tryCompile should NOT be called when Stage 1 returns null"
            )
        }

        // ─── Gradle DSL classpath resolution ─────────────────────────────────────

        test("resolveGradleDslClasspath returns empty list when no Gradle installation found") {
            val builder = lstBuilder()
            // Use an isolated temp dir with no wrapper and no GRADLE_HOME (env cannot be unset,
            // but the dir will have no wrapper so this tests the no-distribution-found branch
            // whenever GRADLE_HOME is not set in the test environment).
            val emptyProject = Files.createTempDirectory("gradle-dsl-test-")
            try {
                val gradleHome = System.getenv("GRADLE_HOME")
                if (gradleHome.isNullOrBlank()) {
                    // No GRADLE_HOME set — we expect empty unless ~/.gradle/wrapper/dists exists
                    val distsRoot =
                        Path.of(System.getProperty("user.home")).resolve(".gradle/wrapper/dists")
                    if (!distsRoot.toFile().exists()) {
                        val result = builder.resolveGradleDslClasspath(emptyProject)
                        assertTrue(
                            result.isEmpty(),
                            "Should return empty list when no Gradle found"
                        )
                    }
                    // If dists exist the fallback will fire; that's correct behaviour — skip assert
                }
                // If GRADLE_HOME is set the method will succeed; that's also correct.
            } finally {
                emptyProject.toFile().deleteRecursively()
            }
        }

        test("resolveGradleDslClasspath finds distribution via wrapper properties") {
            val wrapperDir = projectDir.resolve("gradle/wrapper").also {
                it.createDirectories()
            }
            // Write wrapper properties pointing at a Gradle version we know is cached
            val distsRoot =
                Path.of(System.getProperty("user.home")).resolve(".gradle/wrapper/dists")
            val cachedVersion = if (distsRoot.toFile().exists()) {
                distsRoot.toFile()
                    .listFiles { f -> f.isDirectory && f.name.startsWith("gradle-") }
                    ?.firstOrNull()
                    ?.name
                    ?.removePrefix("gradle-")
                    ?.substringBeforeLast("-") // strip "-bin" / "-all"
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
            // If no distribution is cached in CI, the test is a no-op (environment variability).
        }

        test("resolveGradleDslClasspath returns only lib/ JARs, not plugins or agents") {
            val distsRoot =
                Path.of(System.getProperty("user.home")).resolve(".gradle/wrapper/dists")
            if (!distsRoot.toFile().exists()) return@test

            val builder = lstBuilder()
            val jars = builder.resolveGradleDslClasspath(projectDir)
            if (jars.isEmpty()) return@test // no Gradle found, skip

            assertTrue(
                jars.none { it.toString().contains("/lib/plugins/") },
                "lib/plugins/ JARs should not be included"
            )
            assertTrue(
                jars.none { it.toString().contains("/lib/agents/") },
                "lib/agents/ JARs should not be included"
            )
        }
    })
