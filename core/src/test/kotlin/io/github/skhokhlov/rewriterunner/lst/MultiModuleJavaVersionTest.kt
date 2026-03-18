package io.github.skhokhlov.rewriterunner.lst

import io.github.skhokhlov.rewriterunner.AetherContext
import io.github.skhokhlov.rewriterunner.NoOpRunnerLogger
import io.github.skhokhlov.rewriterunner.config.ToolConfig
import io.github.skhokhlov.rewriterunner.lst.utils.ClasspathResolutionResult
import io.kotest.core.spec.style.FunSpec
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.openrewrite.java.marker.JavaVersion

/**
 * Verifies that [LstBuilder] detects Java versions **per submodule** in multi-module
 * Maven and Gradle projects.
 *
 * In a multi-module build, each submodule can independently declare its Java source/target
 * version. A file inside `module-a` must receive the marker derived from
 * `module-a/pom.xml` (or `module-a/build.gradle`), not from the root build descriptor.
 *
 * Walk-up semantics tested here:
 * - Nearest ancestor build file with an explicit version wins.
 * - A build file that exists but carries no explicit version is skipped; the walk continues
 *   upward (enabling submodule → parent inheritance).
 * - Files in the project root always pick up the root build file's version.
 * - Single-module projects remain unaffected (backward compatibility).
 */
class MultiModuleJavaVersionTest :
    FunSpec({
        var projectDir: Path = Path.of("")

        beforeEach { projectDir = Files.createTempDirectory("mmjvt-") }

        afterEach { projectDir.toFile().deleteRecursively() }

        val failingBuildTool =
            object : ProjectBuildStage(NoOpRunnerLogger) {
                override fun extractClasspath(projectDir: Path): List<Path>? = null
            }

        fun lstBuilder(): LstBuilder {
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
                toolConfig = ToolConfig(logger = NoOpRunnerLogger),
                projectBuildStage = failingBuildTool,
                depResolutionStage = noOpDepStage
            )
        }

        /**
         * Builds the LST for all `.java` files under [projectDir] and returns a map from
         * simple file name to its [JavaVersion] marker.
         */
        fun buildAndGetVersionsPerFile(): Map<String, JavaVersion> {
            val sources =
                lstBuilder().build(projectDir, includeExtensionsCli = listOf(".java"))
            return sources
                .filter { it.sourcePath.toString().endsWith(".java") }
                .associateBy(
                    { it.sourcePath.fileName.toString() },
                    {
                        it.markers.findFirst(JavaVersion::class.java).orElseThrow {
                            AssertionError("JavaVersion marker missing on ${it.sourcePath}")
                        }
                    }
                )
        }

        /** Writes a minimal Maven pom.xml with an optional explicit Java release version. */
        fun Path.writeMavenPom(artifactId: String, javaVersion: String? = null) {
            val versionBlock =
                if (javaVersion != null) {
                    """
                    |  <properties>
                    |    <maven.compiler.release>$javaVersion</maven.compiler.release>
                    |  </properties>
                    """.trimMargin()
                } else {
                    ""
                }
            writeText(
                """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>$artifactId</artifactId>
                  <version>1.0</version>$versionBlock
                </project>
                """.trimIndent()
            )
        }

        /** Writes a Gradle Kotlin DSL build file with a `jvmToolchain(N)` declaration. */
        fun Path.writeGradleKtsToolchain(version: Int) {
            writeText(
                """
                plugins { kotlin("jvm") }
                kotlin { jvmToolchain($version) }
                """.trimIndent()
            )
        }

        /** Writes a Gradle Groovy DSL build file with `sourceCompatibility`. */
        fun Path.writeGradleGroovySource(version: String) {
            writeText(
                """
                plugins { id 'java' }
                sourceCompatibility = '$version'
                targetCompatibility = '$version'
                """.trimIndent()
            )
        }

        // ─── Maven multi-module ───────────────────────────────────────────────────

        test("Maven multi-module each submodule gets its own declared Java version") {
            // Root: Java 11
            projectDir.resolve("pom.xml").writeMavenPom("root", javaVersion = "11")

            // module-a: Java 8
            val modA = projectDir.resolve("module-a").also { it.createDirectories() }
            modA.resolve("pom.xml").writeMavenPom("module-a", javaVersion = "8")
            val srcA = modA.resolve("src/main/java").also { it.createDirectories() }
            srcA.resolve("ModuleA.java").writeText("class ModuleA {}")

            // module-b: Java 17
            val modB = projectDir.resolve("module-b").also { it.createDirectories() }
            modB.resolve("pom.xml").writeMavenPom("module-b", javaVersion = "17")
            val srcB = modB.resolve("src/main/java").also { it.createDirectories() }
            srcB.resolve("ModuleB.java").writeText("class ModuleB {}")

            val versions = buildAndGetVersionsPerFile()

            val vA = versions["ModuleA.java"]
            assertNotNull(vA, "ModuleA.java must be present")
            assertEquals(
                "8",
                vA.sourceCompatibility,
                "module-a declares Java 8; ModuleA.java must receive marker '8'"
            )

            val vB = versions["ModuleB.java"]
            assertNotNull(vB, "ModuleB.java must be present")
            assertEquals(
                "17",
                vB.sourceCompatibility,
                "module-b declares Java 17; ModuleB.java must receive marker '17'"
            )
        }

        test("Maven multi-module root-level Java files get root pom version") {
            // Root: Java 21
            projectDir.resolve("pom.xml").writeMavenPom("root", javaVersion = "21")

            val rootSrc =
                projectDir.resolve("src/main/java").also { it.createDirectories() }
            rootSrc.resolve("Root.java").writeText("class Root {}")

            // Also a submodule with a different version (to confirm root file isn't affected)
            val modA = projectDir.resolve("module-a").also { it.createDirectories() }
            modA.resolve("pom.xml").writeMavenPom("module-a", javaVersion = "11")
            modA.resolve("src/main/java").also { it.createDirectories() }
                .resolve("ModuleA.java").writeText("class ModuleA {}")

            val versions = buildAndGetVersionsPerFile()

            assertEquals(
                "21",
                versions["Root.java"]?.sourceCompatibility,
                "Root.java is under the root pom and must get version 21"
            )
            assertEquals(
                "11",
                versions["ModuleA.java"]?.sourceCompatibility,
                "ModuleA.java is under module-a pom and must get version 11"
            )
        }

        test("Maven multi-module submodule with no explicit version inherits root version") {
            // Root declares Java 17
            projectDir.resolve("pom.xml").writeMavenPom("root", javaVersion = "17")

            // module-a has a pom.xml but no Java version configured
            val modA = projectDir.resolve("module-a").also { it.createDirectories() }
            modA.resolve("pom.xml").writeMavenPom("module-a", javaVersion = null)
            modA.resolve("src/main/java").also { it.createDirectories() }
                .resolve("ModuleA.java").writeText("class ModuleA {}")

            val versions = buildAndGetVersionsPerFile()

            assertEquals(
                "17",
                versions["ModuleA.java"]?.sourceCompatibility,
                "module-a has no explicit Java version; must inherit root's Java 17 by walking up"
            )
        }

        test(
            "Maven multi-module three level nesting uses nearest ancestor with explicit version"
        ) {
            // Root: Java 11
            projectDir.resolve("pom.xml").writeMavenPom("root", javaVersion = "11")

            // parent-module: no explicit Java version
            val parentMod =
                projectDir.resolve("parent-module").also { it.createDirectories() }
            parentMod.resolve("pom.xml").writeMavenPom("parent-module", javaVersion = null)

            // child-module (nested under parent-module): Java 17
            val childMod = parentMod.resolve("child-module").also { it.createDirectories() }
            childMod.resolve("pom.xml").writeMavenPom("child-module", javaVersion = "17")
            childMod.resolve("src/main/java").also { it.createDirectories() }
                .resolve("Child.java").writeText("class Child {}")

            // sibling: no explicit version — should inherit root (Java 11)
            val siblingMod =
                projectDir.resolve("sibling-module").also { it.createDirectories() }
            siblingMod.resolve("pom.xml").writeMavenPom("sibling-module", javaVersion = null)
            siblingMod.resolve("src/main/java").also { it.createDirectories() }
                .resolve("Sibling.java").writeText("class Sibling {}")

            val versions = buildAndGetVersionsPerFile()

            assertEquals(
                "17",
                versions["Child.java"]?.sourceCompatibility,
                "child-module declares Java 17 explicitly; must win over parent-module " +
                    "(no version) and root (Java 11)"
            )
            assertEquals(
                "11",
                versions["Sibling.java"]?.sourceCompatibility,
                "sibling-module has no explicit version; walks up through empty parent pom to root's Java 11"
            )
        }

        test("Maven multi-module all three submodules get independent versions") {
            projectDir.resolve("pom.xml").writeMavenPom("root", javaVersion = "21")

            listOf("8" to "alpha", "11" to "beta", "17" to "gamma").forEach { (ver, name) ->
                val mod = projectDir.resolve(name).also { it.createDirectories() }
                mod.resolve("pom.xml").writeMavenPom(name, javaVersion = ver)
                mod.resolve("src/main/java").also { it.createDirectories() }
                    .resolve("${name.replaceFirstChar { it.uppercaseChar() }}.java")
                    .writeText("class ${name.replaceFirstChar { it.uppercaseChar() }} {}")
            }

            val versions = buildAndGetVersionsPerFile()

            assertEquals("8", versions["Alpha.java"]?.sourceCompatibility)
            assertEquals("11", versions["Beta.java"]?.sourceCompatibility)
            assertEquals("17", versions["Gamma.java"]?.sourceCompatibility)
        }

        // ─── Gradle multi-module ─────────────────────────────────────────────────

        test("Gradle multi-module each subproject gets its own declared Java version") {
            // Root: Java 21 via toolchain
            projectDir.resolve("build.gradle.kts").writeGradleKtsToolchain(21)
            projectDir.resolve("settings.gradle.kts").writeText(
                """
                include("module-a", "module-b")
                """.trimIndent()
            )

            // module-a: Java 11 via sourceCompatibility
            val modA = projectDir.resolve("module-a").also { it.createDirectories() }
            modA.resolve("build.gradle").writeGradleGroovySource("11")
            modA.resolve("src/main/java").also { it.createDirectories() }
                .resolve("ModuleA.java").writeText("class ModuleA {}")

            // module-b: Java 17 via toolchain
            val modB = projectDir.resolve("module-b").also { it.createDirectories() }
            modB.resolve("build.gradle.kts").writeGradleKtsToolchain(17)
            modB.resolve("src/main/java").also { it.createDirectories() }
                .resolve("ModuleB.java").writeText("class ModuleB {}")

            val versions = buildAndGetVersionsPerFile()

            assertEquals(
                "11",
                versions["ModuleA.java"]?.sourceCompatibility,
                "module-a declares Java 11; ModuleA.java must receive marker '11'"
            )
            assertEquals(
                "17",
                versions["ModuleB.java"]?.sourceCompatibility,
                "module-b declares Java 17; ModuleB.java must receive marker '17'"
            )
        }

        test("Gradle multi-module subproject without build file walks up to root") {
            // Root declares Java 21
            projectDir.resolve("build.gradle.kts").writeGradleKtsToolchain(21)

            // module-a has NO build file at all
            val modA = projectDir.resolve("module-a").also { it.createDirectories() }
            modA.resolve("src/main/java").also { it.createDirectories() }
                .resolve("ModuleA.java").writeText("class ModuleA {}")

            val versions = buildAndGetVersionsPerFile()

            assertEquals(
                "21",
                versions["ModuleA.java"]?.sourceCompatibility,
                "module-a has no build file; must walk up to root build.gradle.kts (Java 21)"
            )
        }

        test("Gradle multi-module files in root src get root build file version") {
            projectDir.resolve("build.gradle.kts").writeGradleKtsToolchain(17)

            val rootSrc =
                projectDir.resolve("src/main/java").also { it.createDirectories() }
            rootSrc.resolve("RootApp.java").writeText("class RootApp {}")

            // Submodule with different version
            val modA = projectDir.resolve("module-a").also { it.createDirectories() }
            modA.resolve("build.gradle").writeGradleGroovySource("11")
            modA.resolve("src/main/java").also { it.createDirectories() }
                .resolve("ModuleA.java").writeText("class ModuleA {}")

            val versions = buildAndGetVersionsPerFile()

            assertEquals(
                "17",
                versions["RootApp.java"]?.sourceCompatibility,
                "RootApp.java is in root src; must get root build.gradle.kts version (Java 17)"
            )
            assertEquals("11", versions["ModuleA.java"]?.sourceCompatibility)
        }

        test("Gradle multi-module mixed DSL subprojects each get correct version") {
            projectDir.resolve("build.gradle.kts").writeGradleKtsToolchain(21)

            // module-a: Groovy DSL
            val modA = projectDir.resolve("module-a").also { it.createDirectories() }
            modA.resolve("build.gradle").writeGradleGroovySource("8")
            modA.resolve("src/main/java").also { it.createDirectories() }
                .resolve("Alpha.java").writeText("class Alpha {}")

            // module-b: Kotlin DSL toolchain
            val modB = projectDir.resolve("module-b").also { it.createDirectories() }
            modB.resolve("build.gradle.kts").writeGradleKtsToolchain(11)
            modB.resolve("src/main/java").also { it.createDirectories() }
                .resolve("Beta.java").writeText("class Beta {}")

            // module-c: no build file — inherits root (21)
            val modC = projectDir.resolve("module-c").also { it.createDirectories() }
            modC.resolve("src/main/java").also { it.createDirectories() }
                .resolve("Gamma.java").writeText("class Gamma {}")

            val versions = buildAndGetVersionsPerFile()

            assertEquals("8", versions["Alpha.java"]?.sourceCompatibility)
            assertEquals("11", versions["Beta.java"]?.sourceCompatibility)
            assertEquals("21", versions["Gamma.java"]?.sourceCompatibility)
        }

        // ─── Backward compatibility ───────────────────────────────────────────────

        test("single-module Maven project behavior is unchanged") {
            projectDir.resolve("pom.xml").writeMavenPom("single", javaVersion = "17")
            val srcDir =
                projectDir.resolve("src/main/java").also { it.createDirectories() }
            srcDir.resolve("App.java").writeText("class App {}")

            val versions = buildAndGetVersionsPerFile()

            assertEquals(
                "17",
                versions["App.java"]?.sourceCompatibility,
                "Single-module project must still detect version correctly from root pom.xml"
            )
        }

        test("single-module Gradle project behavior is unchanged") {
            projectDir.resolve("build.gradle.kts").writeGradleKtsToolchain(21)
            val srcDir =
                projectDir.resolve("src/main/java").also { it.createDirectories() }
            srcDir.resolve("App.java").writeText("class App {}")

            val versions = buildAndGetVersionsPerFile()

            assertEquals(
                "21",
                versions["App.java"]?.sourceCompatibility,
                "Single-module Gradle project must still detect version correctly"
            )
        }

        test("no build file at all falls back to JVM version for all files") {
            // No pom.xml or build.gradle anywhere
            val srcDir =
                projectDir.resolve("src/main/java").also { it.createDirectories() }
            srcDir.resolve("App.java").writeText("class App {}")

            val versions = buildAndGetVersionsPerFile()

            val jvmMajor = run {
                val v = System.getProperty("java.version") ?: ""
                val stripped = if (v.startsWith("1.")) v.removePrefix("1.") else v
                stripped.substringBefore(".")
            }
            assertEquals(
                jvmMajor,
                versions["App.java"]?.sourceCompatibility,
                "No build file → must fall back to running JVM version"
            )
        }
    })
