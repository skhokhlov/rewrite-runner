package org.example.lst

import org.example.config.ToolConfig
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.openrewrite.java.marker.JavaVersion
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Verifies that [LstBuilder] attaches the correct [JavaVersion] marker to parsed
 * Java source files by reading the project's build descriptor (pom.xml or build.gradle)
 * rather than defaulting to the running JVM version.
 *
 * Each test creates a minimal project in a temp dir with a simple Java file and
 * a specific build-file configuration, then inspects the marker on the returned LST.
 */
class JavaVersionDetectionTest {

    @TempDir lateinit var projectDir: Path

    private val failingBuildTool = object : BuildToolStage() {
        override fun extractClasspath(projectDir: Path): List<Path>? = null
    }

    private fun lstBuilder(): LstBuilder {
        val noOpDepStage = object : DependencyResolutionStage(
            cacheDir = projectDir.resolve("cache"),
            extraRepositories = emptyList(),
        ) {
            override fun resolveClasspath(projectDir: Path): List<Path> = emptyList()
        }
        return LstBuilder(
            cacheDir = projectDir.resolve("cache"),
            toolConfig = ToolConfig(),
            buildToolStage = failingBuildTool,
            depResolutionStage = noOpDepStage,
        )
    }

    private fun buildAndGetJavaVersion(): JavaVersion {
        projectDir.resolve("Hello.java").writeText("class Hello {}")
        val sources = lstBuilder().build(projectDir, includeExtensionsCli = listOf(".java"))
        val javaFile = sources.single { it.sourcePath.toString().endsWith(".java") }
        val marker = javaFile.markers.findFirst(JavaVersion::class.java).orElse(null)
        assertNotNull(marker, "JavaVersion marker must be present on parsed Java source file")
        return marker
    }

    // ─── Maven: maven-compiler-plugin <configuration> ─────────────────────────

    @Test
    fun `Maven compiler plugin release element sets source and target`() {
        projectDir.resolve("pom.xml").writeText("""
            <project>
              <build>
                <plugins>
                  <plugin>
                    <artifactId>maven-compiler-plugin</artifactId>
                    <configuration>
                      <release>17</release>
                    </configuration>
                  </plugin>
                </plugins>
              </build>
            </project>
        """.trimIndent())

        val version = buildAndGetJavaVersion()
        assertEquals("17", version.sourceCompatibility)
        assertEquals("17", version.targetCompatibility)
    }

    @Test
    fun `Maven compiler plugin source and target elements set independently`() {
        projectDir.resolve("pom.xml").writeText("""
            <project>
              <build>
                <plugins>
                  <plugin>
                    <artifactId>maven-compiler-plugin</artifactId>
                    <configuration>
                      <source>11</source>
                      <target>11</target>
                    </configuration>
                  </plugin>
                </plugins>
              </build>
            </project>
        """.trimIndent())

        val version = buildAndGetJavaVersion()
        assertEquals("11", version.sourceCompatibility)
        assertEquals("11", version.targetCompatibility)
    }

    @Test
    fun `Maven compiler plugin release takes priority over source and target elements`() {
        projectDir.resolve("pom.xml").writeText("""
            <project>
              <build>
                <plugins>
                  <plugin>
                    <artifactId>maven-compiler-plugin</artifactId>
                    <configuration>
                      <release>21</release>
                      <source>11</source>
                      <target>11</target>
                    </configuration>
                  </plugin>
                </plugins>
              </build>
            </project>
        """.trimIndent())

        val version = buildAndGetJavaVersion()
        assertEquals("21", version.sourceCompatibility)
        assertEquals("21", version.targetCompatibility)
    }

    // ─── Maven: project <properties> ─────────────────────────────────────────

    @Test
    fun `Maven maven compiler release property sets source and target`() {
        projectDir.resolve("pom.xml").writeText("""
            <project>
              <properties>
                <maven.compiler.release>17</maven.compiler.release>
              </properties>
            </project>
        """.trimIndent())

        val version = buildAndGetJavaVersion()
        assertEquals("17", version.sourceCompatibility)
        assertEquals("17", version.targetCompatibility)
    }

    @Test
    fun `Maven maven compiler source and target properties`() {
        projectDir.resolve("pom.xml").writeText("""
            <project>
              <properties>
                <maven.compiler.source>8</maven.compiler.source>
                <maven.compiler.target>8</maven.compiler.target>
              </properties>
            </project>
        """.trimIndent())

        val version = buildAndGetJavaVersion()
        assertEquals("8", version.sourceCompatibility)
        assertEquals("8", version.targetCompatibility)
    }

    @Test
    fun `Maven plugin configuration takes priority over project properties`() {
        projectDir.resolve("pom.xml").writeText("""
            <project>
              <properties>
                <maven.compiler.release>11</maven.compiler.release>
              </properties>
              <build>
                <plugins>
                  <plugin>
                    <artifactId>maven-compiler-plugin</artifactId>
                    <configuration>
                      <release>17</release>
                    </configuration>
                  </plugin>
                </plugins>
              </build>
            </project>
        """.trimIndent())

        val version = buildAndGetJavaVersion()
        assertEquals("17", version.sourceCompatibility)
        assertEquals("17", version.targetCompatibility)
    }

    @Test
    fun `Maven property interpolation placeholders are ignored and fall back to JVM version`() {
        projectDir.resolve("pom.xml").writeText("""
            <project>
              <build>
                <plugins>
                  <plugin>
                    <artifactId>maven-compiler-plugin</artifactId>
                    <configuration>
                      <release>${'$'}{java.version}</release>
                    </configuration>
                  </plugin>
                </plugins>
              </build>
            </project>
        """.trimIndent())

        val version = buildAndGetJavaVersion()
        val jvmMajor = jvmMajorVersion()
        // Interpolation placeholder must be skipped; falls back to JVM version
        assertEquals(jvmMajor, version.sourceCompatibility)
    }

    // ─── Gradle ───────────────────────────────────────────────────────────────

    @Test
    fun `Gradle sourceCompatibility Groovy DSL quoted string`() {
        projectDir.resolve("build.gradle").writeText("""
            plugins { id 'java' }
            sourceCompatibility = '17'
            targetCompatibility = '17'
        """.trimIndent())

        val version = buildAndGetJavaVersion()
        assertEquals("17", version.sourceCompatibility)
        assertEquals("17", version.targetCompatibility)
    }

    @Test
    fun `Gradle sourceCompatibility Kotlin DSL JavaVersion constant`() {
        projectDir.resolve("build.gradle.kts").writeText("""
            plugins { java }
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        """.trimIndent())

        val version = buildAndGetJavaVersion()
        assertEquals("17", version.sourceCompatibility)
        assertEquals("17", version.targetCompatibility)
    }

    @Test
    fun `Gradle jvmToolchain sets source and target`() {
        projectDir.resolve("build.gradle.kts").writeText("""
            plugins { kotlin("jvm") }
            kotlin { jvmToolchain(21) }
        """.trimIndent())

        val version = buildAndGetJavaVersion()
        assertEquals("21", version.sourceCompatibility)
        assertEquals("21", version.targetCompatibility)
    }

    @Test
    fun `Gradle java toolchain block with JavaLanguageVersion of`() {
        projectDir.resolve("build.gradle.kts").writeText("""
            plugins { java }
            java {
                toolchain {
                    languageVersion = JavaLanguageVersion.of(17)
                }
            }
        """.trimIndent())

        val version = buildAndGetJavaVersion()
        assertEquals("17", version.sourceCompatibility)
        assertEquals("17", version.targetCompatibility)
    }

    @Test
    fun `Gradle options release takes priority over sourceCompatibility`() {
        projectDir.resolve("build.gradle").writeText("""
            plugins { id 'java' }
            sourceCompatibility = '11'
            targetCompatibility = '11'
            compileJava.options.release = 17
        """.trimIndent())

        val version = buildAndGetJavaVersion()
        assertEquals("17", version.sourceCompatibility)
        assertEquals("17", version.targetCompatibility)
    }

    // ─── No build file fallback ───────────────────────────────────────────────

    @Test
    fun `no build file falls back to running JVM major version`() {
        // No pom.xml or build.gradle in projectDir
        val version = buildAndGetJavaVersion()
        val jvmMajor = jvmMajorVersion()
        assertEquals(jvmMajor, version.sourceCompatibility)
        assertEquals(jvmMajor, version.targetCompatibility)
    }

    // ─── Marker metadata ──────────────────────────────────────────────────────

    @Test
    fun `JavaVersion marker carries JVM runtime version and vendor`() {
        projectDir.resolve("pom.xml").writeText("<project/>")

        val version = buildAndGetJavaVersion()
        assertNotNull(version.createdBy, "createdBy must be set from java.runtime.version system property")
        assertNotNull(version.vmVendor, "vmVendor must be set from java.vm.vendor system property")
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /** Returns the major version string of the running JVM, e.g. "21". */
    private fun jvmMajorVersion(): String {
        val v = System.getProperty("java.version") ?: ""
        val stripped = if (v.startsWith("1.")) v.removePrefix("1.") else v
        return stripped.substringBefore(".")
    }
}
