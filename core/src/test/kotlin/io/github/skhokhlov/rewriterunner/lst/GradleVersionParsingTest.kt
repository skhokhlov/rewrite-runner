package io.github.skhokhlov.rewriterunner.lst

import io.github.skhokhlov.rewriterunner.AetherContext
import io.github.skhokhlov.rewriterunner.config.ToolConfig
import io.kotest.core.spec.style.FunSpec
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unit tests for [LstBuilder.parseGradleVersionFromWrapper].
 *
 * The method is `internal` so it can be tested directly without building a full
 * LstBuilder pipeline or requiring a local Gradle distribution to be installed.
 */
class GradleVersionParsingTest :
    FunSpec({
        var projectDir: Path = Path.of("")

        beforeEach { projectDir = Files.createTempDirectory("gvpt-") }

        afterEach { projectDir.toFile().deleteRecursively() }

        val failingBuildTool =
            object : BuildToolStage() {
                override fun extractClasspath(projectDir: Path): List<Path>? = null
            }

        fun lstBuilder(): LstBuilder {
            val noOpDepStage =
                object : DependencyResolutionStage(
                    AetherContext.build(projectDir.resolve("cache").resolve("repository"))
                ) {
                    override fun resolveClasspath(projectDir: Path): List<Path> = emptyList()
                }
            return LstBuilder(
                cacheDir = projectDir.resolve("cache"),
                toolConfig = ToolConfig(),
                buildToolStage = failingBuildTool,
                depResolutionStage = noOpDepStage
            )
        }

        fun writeWrapperProps(distributionUrl: String): Path {
            val wrapperDir = projectDir.resolve("gradle/wrapper")
            wrapperDir.toFile().mkdirs()
            val props = wrapperDir.resolve("gradle-wrapper.properties")
            props.writeText("distributionUrl=$distributionUrl\n")
            return props
        }

        // ─── stable releases ──────────────────────────────────────────────────

        test("parses two-part stable version from -bin distribution") {
            val props = writeWrapperProps(
                "https://services.gradle.org/distributions/gradle-8.7-bin.zip"
            )
            assertEquals("8.7", lstBuilder().parseGradleVersionFromWrapper(props))
        }

        test("parses two-part stable version from -all distribution") {
            val props = writeWrapperProps(
                "https://services.gradle.org/distributions/gradle-8.7-all.zip"
            )
            assertEquals("8.7", lstBuilder().parseGradleVersionFromWrapper(props))
        }

        test("parses three-part stable version") {
            val props = writeWrapperProps(
                "https://services.gradle.org/distributions/gradle-8.7.3-bin.zip"
            )
            assertEquals("8.7.3", lstBuilder().parseGradleVersionFromWrapper(props))
        }

        // ─── pre-release builds ───────────────────────────────────────────────

        test("parses RC version from distributionUrl") {
            // Before the fix, gradle-9.0-rc-1-bin.zip was not matched by the regex
            // because "-rc-1" precedes "-bin" and the old pattern only expected
            // "-bin" or "-all" immediately after the version digits.
            val props = writeWrapperProps(
                "https://services.gradle.org/distributions/gradle-9.0-rc-1-bin.zip"
            )
            assertEquals("9.0-rc-1", lstBuilder().parseGradleVersionFromWrapper(props))
        }

        test("parses milestone version from distributionUrl") {
            val props = writeWrapperProps(
                "https://services.gradle.org/distributions/gradle-9.0-milestone-1-bin.zip"
            )
            assertEquals("9.0-milestone-1", lstBuilder().parseGradleVersionFromWrapper(props))
        }

        test("parses RC version with three-part base from distributionUrl") {
            val props = writeWrapperProps(
                "https://services.gradle.org/distributions/gradle-8.7.3-rc-2-bin.zip"
            )
            assertEquals("8.7.3-rc-2", lstBuilder().parseGradleVersionFromWrapper(props))
        }

        // ─── edge cases ───────────────────────────────────────────────────────

        test("returns null when distributionUrl property is absent") {
            val wrapperDir = projectDir.resolve("gradle/wrapper")
            wrapperDir.toFile().mkdirs()
            val props = wrapperDir.resolve("gradle-wrapper.properties")
            props.writeText("# no distributionUrl here\n")
            assertNull(lstBuilder().parseGradleVersionFromWrapper(props))
        }

        test("returns null when distributionUrl does not contain a recognisable Gradle version") {
            val props = writeWrapperProps("file:///local/custom-build.zip")
            assertNull(lstBuilder().parseGradleVersionFromWrapper(props))
        }

        test("returns null when wrapper properties file does not exist") {
            val missing = projectDir.resolve("gradle/wrapper/gradle-wrapper.properties")
            // file intentionally NOT created
            assertNull(lstBuilder().parseGradleVersionFromWrapper(missing))
        }
    })
