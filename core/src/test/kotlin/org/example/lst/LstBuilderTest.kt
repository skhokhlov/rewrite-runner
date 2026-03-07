package org.example.lst

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.config.ParseConfig
import org.example.config.ToolConfig
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LstBuilderTest {

    @TempDir
    lateinit var projectDir: Path

    private val toolConfig = ToolConfig()

    /** A BuildToolStage that always returns null (simulates broken build tool). */
    private val failingBuildTool = object : BuildToolStage() {
        override fun extractClasspath(projectDir: Path): List<Path>? = null
    }

    /** A BuildToolStage that returns an empty classpath (simulates successful but empty). */
    private val emptyBuildTool = object : BuildToolStage() {
        override fun extractClasspath(projectDir: Path): List<Path> = emptyList()
    }

    /** A BuildToolStage that captures whether it was called. */
    private var stage1Called = false
    private val trackingBuildTool = object : BuildToolStage() {
        override fun extractClasspath(projectDir: Path): List<Path>? {
            stage1Called = true
            return null
        }
    }

    @BeforeEach
    fun setUp() {
        stage1Called = false
    }

    private fun lstBuilder(buildTool: BuildToolStage = failingBuildTool): LstBuilder {
        val noOpDepStage = object : DependencyResolutionStage(
            cacheDir = projectDir.resolve("cache"),
            extraRepositories = emptyList()
        ) {
            override fun resolveClasspath(projectDir: Path): List<Path> = emptyList()
        }
        return LstBuilder(
            cacheDir = projectDir.resolve("cache"),
            toolConfig = toolConfig,
            buildToolStage = buildTool,
            depResolutionStage = noOpDepStage
        )
    }

    // ─── Extension filtering ──────────────────────────────────────────────────

    @Test
    fun `includeExtensions CLI flag restricts parsed file types`() {
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

    @Test
    fun `excludeExtensions CLI flag removes specific types from defaults`() {
        projectDir.resolve("Hello.java").writeText("class Hello {}")
        projectDir.resolve("config.xml").writeText("<root/>")
        projectDir.resolve("app.properties").writeText("key=value")

        val sources = lstBuilder().build(
            projectDir = projectDir,
            excludeExtensionsCli = listOf(".xml", ".properties")
        )

        val paths = sources.map { it.sourcePath.toString() }
        assertTrue(paths.none { it.endsWith(".xml") }, "XML files should be excluded")
        assertTrue(paths.none { it.endsWith(".properties") }, "Properties files should be excluded")
        assertTrue(paths.any { it.endsWith(".java") }, "Java files should still be included")
    }

    @Test
    fun `includeExtensions in config file is respected when no CLI flag given`() {
        projectDir.resolve("Hello.java").writeText("class Hello {}")
        projectDir.resolve("config.yaml").writeText("key: value")

        val config = ToolConfig(parse = ParseConfig(includeExtensions = listOf(".yaml")))
        val noOpDepStage = object : DependencyResolutionStage(projectDir, emptyList()) {
            override fun resolveClasspath(projectDir: Path) = emptyList<Path>()
        }
        val builder = LstBuilder(
            cacheDir = projectDir.resolve("cache"),
            toolConfig = config,
            buildToolStage = failingBuildTool,
            depResolutionStage = noOpDepStage
        )

        val sources = builder.build(projectDir = projectDir)
        assertEquals(1, sources.size)
        assertTrue(sources.first().sourcePath.toString().endsWith(".yaml"))
    }

    @Test
    fun `CLI includeExtensions overrides config file`() {
        projectDir.resolve("Hello.java").writeText("class Hello {}")
        projectDir.resolve("config.yaml").writeText("key: value")

        val config = ToolConfig(parse = ParseConfig(includeExtensions = listOf(".yaml")))
        val noOpDepStage = object : DependencyResolutionStage(projectDir, emptyList()) {
            override fun resolveClasspath(projectDir: Path) = emptyList<Path>()
        }
        val builder = LstBuilder(
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

    @Test
    fun `files inside build directory are excluded`() {
        projectDir.resolve("Hello.java").writeText("class Hello {}")
        projectDir.resolve("build").createDirectories()
        projectDir.resolve("build/Generated.java").writeText("class Generated {}")

        val sources = lstBuilder().build(
            projectDir = projectDir,
            includeExtensionsCli = listOf(".java")
        )
        assertEquals(1, sources.size, "build/ files should be excluded")
        assertTrue(sources.first().sourcePath.toString() == "Hello.java")
    }

    @Test
    fun `files inside target directory are excluded`() {
        projectDir.resolve("Hello.java").writeText("class Hello {}")
        projectDir.resolve("target").createDirectories()
        projectDir.resolve("target/Compiled.java").writeText("class Compiled {}")

        val sources = lstBuilder().build(
            projectDir = projectDir,
            includeExtensionsCli = listOf(".java")
        )
        assertEquals(1, sources.size, "target/ files should be excluded")
    }

    @Test
    fun `files inside node_modules are excluded`() {
        projectDir.resolve("config.yaml").writeText("key: value")
        projectDir.resolve("node_modules").createDirectories()
        projectDir.resolve("node_modules/package.json").writeText("{}")

        val sources = lstBuilder().build(
            projectDir = projectDir,
            includeExtensionsCli = listOf(".json")
        )
        assertEquals(0, sources.size, "node_modules/ files should be excluded")
    }

    @Test
    fun `glob excludePaths patterns are applied`() {
        projectDir.resolve("src").createDirectories()
        projectDir.resolve("src/Main.java").writeText("class Main {}")
        projectDir.resolve("generated").createDirectories()
        projectDir.resolve("generated/Gen.java").writeText("class Gen {}")

        val config = ToolConfig(parse = ParseConfig(excludePaths = listOf("generated/**")))
        val noOpDepStage = object : DependencyResolutionStage(projectDir, emptyList()) {
            override fun resolveClasspath(projectDir: Path) = emptyList<Path>()
        }
        val builder = LstBuilder(
            cacheDir = projectDir.resolve("cache"),
            toolConfig = config,
            buildToolStage = failingBuildTool,
            depResolutionStage = noOpDepStage
        )

        val sources = builder.build(projectDir = projectDir, includeExtensionsCli = listOf(".java"))
        assertEquals(1, sources.size)
        assertTrue(sources.first().sourcePath.toString().contains("Main"))
    }

    // ─── Multi-language parsing ───────────────────────────────────────────────

    @Test
    fun `all supported file types are parsed by default`() {
        projectDir.resolve("Hello.java").writeText("class Hello {}")
        projectDir.resolve("config.yaml").writeText("key: value")
        projectDir.resolve("data.json").writeText("{}")
        projectDir.resolve("pom.xml").writeText("<project/>")
        projectDir.resolve("app.properties").writeText("key=value")

        val sources = lstBuilder().build(projectDir = projectDir)

        val paths = sources.map { it.sourcePath.toString() }
        assertTrue(paths.any { it.endsWith(".java") }, "Java should be parsed")
        assertTrue(paths.any { it.endsWith(".yaml") }, "YAML should be parsed")
        assertTrue(paths.any { it.endsWith(".json") }, "JSON should be parsed")
        assertTrue(paths.any { it.endsWith(".xml") }, "XML should be parsed")
        assertTrue(paths.any { it.endsWith(".properties") }, "Properties should be parsed")
    }

    @Test
    fun `yml extension is treated same as yaml`() {
        projectDir.resolve("app.yml").writeText("spring:\n  port: 8080")

        val sources = lstBuilder().build(
            projectDir = projectDir,
            includeExtensionsCli = listOf(".yml")
        )
        assertEquals(1, sources.size)
    }

    // ─── 3-stage pipeline fallthrough ────────────────────────────────────────

    @Test
    fun `stage 1 is attempted first`() {
        lstBuilder(buildTool = trackingBuildTool).build(
            projectDir = projectDir,
            includeExtensionsCli = listOf(".java")
        )
        assertTrue(stage1Called, "Stage 1 (build tool) should always be attempted first")
    }

    @Test
    fun `stage 1 result is used when it returns a non-null list`() {
        var stage2Called = false
        val capturedClasspath = mutableListOf<Path>()
        val fakeJar = projectDir.resolve("fake.jar").also { it.writeText("") }

        val successfulBuildTool = object : BuildToolStage() {
            override fun extractClasspath(projectDir: Path): List<Path> = listOf(fakeJar)
        }
        val trackingDepStage = object : DependencyResolutionStage(projectDir, emptyList()) {
            override fun resolveClasspath(projectDir: Path): List<Path> {
                stage2Called = true
                return emptyList()
            }
        }

        projectDir.resolve("Hello.java").writeText("class Hello {}")
        LstBuilder(
            cacheDir = projectDir.resolve("cache"),
            toolConfig = toolConfig,
            buildToolStage = successfulBuildTool,
            depResolutionStage = trackingDepStage
        ).build(projectDir = projectDir, includeExtensionsCli = listOf(".java"))

        assertTrue(!stage2Called, "Stage 2 should NOT be called when Stage 1 succeeds")
    }

    @Test
    fun `stage 2 is attempted when stage 1 returns null`() {
        var stage2Called = false
        val trackingDepStage = object : DependencyResolutionStage(projectDir, emptyList()) {
            override fun resolveClasspath(projectDir: Path): List<Path> {
                stage2Called = true
                return emptyList()
            }
        }

        LstBuilder(
            cacheDir = projectDir.resolve("cache"),
            toolConfig = toolConfig,
            buildToolStage = failingBuildTool,
            depResolutionStage = trackingDepStage
        ).build(projectDir = projectDir, includeExtensionsCli = listOf(".java"))

        assertTrue(stage2Called, "Stage 2 should be attempted when Stage 1 fails")
    }

    @Test
    fun `parsing succeeds even when all classpath stages fail`() {
        // Both stage 1 and stage 2 fail → stage 3 fallback → parse with empty classpath
        projectDir.resolve("Hello.java").writeText("class Hello {}")

        val sources = lstBuilder().build(
            projectDir = projectDir,
            includeExtensionsCli = listOf(".java")
        )

        // Should still parse the file even without a resolved classpath
        assertEquals(
            1,
            sources.size,
            "Java file should be parsed even without classpath (Stage 3 fallback)"
        )
    }

    // ─── Compile-on-demand (Stage 1 enhancement) ─────────────────────────────

    @Test
    fun `tryCompile is called when stage 1 succeeds but no class directories exist`() {
        var tryCompileCalled = false
        val fakeJar = projectDir.resolve("fake.jar").also { it.writeText("") }

        val trackingBuildTool = object : BuildToolStage() {
            override fun extractClasspath(projectDir: Path): List<Path> = listOf(fakeJar)
            override fun tryCompile(projectDir: Path): Boolean {
                tryCompileCalled = true
                return false
            }
        }

        projectDir.resolve("Hello.java").writeText("class Hello {}")
        lstBuilder(buildTool = trackingBuildTool).build(
            projectDir = projectDir,
            includeExtensionsCli = listOf(".java")
        )

        assertTrue(
            tryCompileCalled,
            "tryCompile should be called when Stage 1 succeeds but class dirs are absent"
        )
    }

    @Test
    fun `tryCompile is NOT called when class directories already exist`() {
        var tryCompileCalled = false
        val fakeJar = projectDir.resolve("fake.jar").also { it.writeText("") }

        // Pre-create a class directory so the tool sees it as already compiled
        projectDir.resolve("target/classes").createDirectories()

        val trackingBuildTool = object : BuildToolStage() {
            override fun extractClasspath(projectDir: Path): List<Path> = listOf(fakeJar)
            override fun tryCompile(projectDir: Path): Boolean {
                tryCompileCalled = true
                return false
            }
        }

        projectDir.resolve("Hello.java").writeText("class Hello {}")
        lstBuilder(buildTool = trackingBuildTool).build(
            projectDir = projectDir,
            includeExtensionsCli = listOf(".java")
        )

        assertTrue(
            !tryCompileCalled,
            "tryCompile should NOT be called when class directories already exist"
        )
    }

    @Test
    fun `tryCompile failure is non-fatal and parsing continues`() {
        val fakeJar = projectDir.resolve("fake.jar").also { it.writeText("") }

        val failingCompileTool = object : BuildToolStage() {
            override fun extractClasspath(projectDir: Path): List<Path> = listOf(fakeJar)
            override fun tryCompile(projectDir: Path): Boolean = false // compilation fails
        }

        projectDir.resolve("Hello.java").writeText("class Hello {}")
        val sources = lstBuilder(buildTool = failingCompileTool).build(
            projectDir = projectDir,
            includeExtensionsCli = listOf(".java")
        )

        assertEquals(1, sources.size, "Parsing should succeed even when tryCompile returns false")
    }

    @Test
    fun `class dirs produced by tryCompile are included in classpath`() {
        val fakeJar = projectDir.resolve("fake.jar").also { it.writeText("") }

        // Simulate a build tool that produces target/classes when tryCompile is called
        val compilingBuildTool = object : BuildToolStage() {
            override fun extractClasspath(projectDir: Path): List<Path> = listOf(fakeJar)
            override fun tryCompile(projectDir: Path): Boolean {
                projectDir.resolve("target/classes").createDirectories()
                return true
            }
        }

        projectDir.resolve("Hello.java").writeText("class Hello {}")
        // No exception and file is parsed — class dir created by compile is picked up
        val sources = lstBuilder(buildTool = compilingBuildTool).build(
            projectDir = projectDir,
            includeExtensionsCli = listOf(".java")
        )

        assertEquals(
            1,
            sources.size,
            "Parsing should succeed after tryCompile creates class directories"
        )
    }

    @Test
    fun `tryCompile is NOT called when stage 1 returns null`() {
        var tryCompileCalled = false

        val nullReturningBuildTool = object : BuildToolStage() {
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

        assertTrue(!tryCompileCalled, "tryCompile should NOT be called when Stage 1 returns null")
    }
}
