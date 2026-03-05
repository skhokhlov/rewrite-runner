package org.example.lst

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests the pom.xml / build.gradle parsing logic in DependencyResolutionStage
 * without hitting the network (no actual artifact resolution).
 */
class DependencyResolutionStageTest {

    @TempDir
    lateinit var projectDir: Path

    @TempDir
    lateinit var cacheDir: Path

    private fun stage() = DependencyResolutionStage(cacheDir, emptyList())

    // ─── Maven pom.xml parsing ────────────────────────────────────────────────

    @Test
    fun `parseMavenDependencies extracts compile-scoped dependencies`() {
        projectDir.resolve("pom.xml").writeText(
            """
            <project>
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>test</artifactId>
                <version>1.0</version>
                <dependencies>
                    <dependency>
                        <groupId>org.apache.commons</groupId>
                        <artifactId>commons-lang3</artifactId>
                        <version>3.12.0</version>
                    </dependency>
                    <dependency>
                        <groupId>com.google.guava</groupId>
                        <artifactId>guava</artifactId>
                        <version>32.1.2-jre</version>
                    </dependency>
                </dependencies>
            </project>
            """.trimIndent()
        )

        val coords = stage().parseMavenDependencies(projectDir)

        assertEquals(2, coords.size)
        assertTrue(coords.contains("org.apache.commons:commons-lang3:3.12.0"))
        assertTrue(coords.contains("com.google.guava:guava:32.1.2-jre"))
    }

    @Test
    fun `parseMavenDependencies includes test-scoped dependencies`() {
        projectDir.resolve("pom.xml").writeText(
            """
            <project>
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>test</artifactId>
                <version>1.0</version>
                <dependencies>
                    <dependency>
                        <groupId>org.apache.commons</groupId>
                        <artifactId>commons-lang3</artifactId>
                        <version>3.12.0</version>
                    </dependency>
                    <dependency>
                        <groupId>org.junit.jupiter</groupId>
                        <artifactId>junit-jupiter</artifactId>
                        <version>5.10.0</version>
                        <scope>test</scope>
                    </dependency>
                </dependencies>
            </project>
            """.trimIndent()
        )

        val coords = stage().parseMavenDependencies(projectDir)

        assertEquals(2, coords.size, "Test dependency should not be excluded")
        assertEquals("org.apache.commons:commons-lang3:3.12.0", coords.first())
    }

    @Test
    fun `parseMavenDependencies excludes provided-scoped dependencies`() {
        projectDir.resolve("pom.xml").writeText(
            """
            <project>
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>app</artifactId>
                <version>1.0</version>
                <dependencies>
                    <dependency>
                        <groupId>javax.servlet</groupId>
                        <artifactId>javax.servlet-api</artifactId>
                        <version>4.0.1</version>
                        <scope>provided</scope>
                    </dependency>
                </dependencies>
            </project>
            """.trimIndent()
        )

        val coords = stage().parseMavenDependencies(projectDir)
        assertEquals(0, coords.size, "Provided dependency should be excluded")
    }

    @Test
    fun `parseMavenDependencies skips dependency with property-placeholder version`() {
        projectDir.resolve("pom.xml").writeText(
            """
            <project>
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>test</artifactId>
                <version>1.0</version>
                <dependencies>
                    <dependency>
                        <groupId>org.apache.commons</groupId>
                        <artifactId>commons-lang3</artifactId>
                        <version>${'$'}{commons.version}</version>
                    </dependency>
                    <dependency>
                        <groupId>com.google.guava</groupId>
                        <artifactId>guava</artifactId>
                        <version>32.1.2-jre</version>
                    </dependency>
                </dependencies>
            </project>
            """.trimIndent()
        )

        val coords = stage().parseMavenDependencies(projectDir)
        assertEquals(1, coords.size, "Dependency with property version should be skipped")
        assertEquals("com.google.guava:guava:32.1.2-jre", coords.first())
    }

    @Test
    fun `parseMavenDependencies returns empty list when pom_xml absent`() {
        val coords = stage().parseMavenDependencies(projectDir)
        assertEquals(0, coords.size)
    }

    @Test
    fun `parseMavenDependencies returns empty list for malformed pom_xml`() {
        projectDir.resolve("pom.xml").writeText("this is not xml")
        val coords = stage().parseMavenDependencies(projectDir)
        assertEquals(0, coords.size, "Malformed pom.xml should not throw, should return empty list")
    }

    // ─── Gradle build file parsing ────────────────────────────────────────────

    @Test
    fun `parseGradleDependencies extracts Kotlin DSL string coordinates`() {
        projectDir.resolve("build.gradle.kts").writeText(
            """
            dependencies {
                implementation("org.apache.commons:commons-lang3:3.12.0")
                implementation("com.google.guava:guava:32.1.2-jre")
                testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
            }
            """.trimIndent()
        )

        val coords = stage().parseGradleDependencies(projectDir)

        assertTrue(coords.contains("org.apache.commons:commons-lang3:3.12.0"), "Should find commons-lang3")
        assertTrue(coords.contains("com.google.guava:guava:32.1.2-jre"), "Should find guava")
        assertTrue(coords.contains("org.junit.jupiter:junit-jupiter:5.10.0"), "Should find junit (no scope filter)")
    }

    @Test
    fun `parseGradleDependencies extracts Groovy DSL single-quoted coordinates`() {
        projectDir.resolve("build.gradle").writeText(
            """
            dependencies {
                implementation 'org.springframework:spring-core:6.1.0'
                runtimeOnly 'ch.qos.logback:logback-classic:1.4.11'
            }
            """.trimIndent()
        )

        val coords = stage().parseGradleDependencies(projectDir)

        assertTrue(coords.contains("org.springframework:spring-core:6.1.0"))
        assertTrue(coords.contains("ch.qos.logback:logback-classic:1.4.11"))
    }

    @Test
    fun `parseGradleDependencies prefers build_gradle_kts over build_gradle`() {
        projectDir.resolve("build.gradle.kts").writeText(
            """
            dependencies {
                implementation("com.google.guava:guava:32.1.2-jre")
            }
            """.trimIndent()
        )
        projectDir.resolve("build.gradle").writeText(
            """
            dependencies {
                implementation 'org.apache.commons:commons-lang3:3.12.0'
            }
            """.trimIndent()
        )

        val coords = stage().parseGradleDependencies(projectDir)

        assertTrue(coords.contains("com.google.guava:guava:32.1.2-jre"), "KTS should take precedence")
        assertTrue(coords.none { it.contains("commons-lang3") }, "Groovy file should not be read")
    }

    @Test
    fun `parseGradleDependencies returns empty list when no build file`() {
        val coords = stage().parseGradleDependencies(projectDir)
        assertEquals(0, coords.size)
    }

    @Test
    fun `parseGradleDependencies deduplicates identical coordinates`() {
        projectDir.resolve("build.gradle.kts").writeText(
            """
            dependencies {
                implementation("com.google.guava:guava:32.1.2-jre")
                implementation("com.google.guava:guava:32.1.2-jre")
            }
            """.trimIndent()
        )

        val coords = stage().parseGradleDependencies(projectDir)
        assertEquals(coords.distinct(), coords, "Coordinates should be deduplicated")
    }

    @Test
    fun `parseGradleDependencies ignores BOM and platform entries`() {
        projectDir.resolve("build.gradle.kts").writeText(
            """
            dependencies {
                implementation(platform("org.springframework.boot:spring-boot-dependencies:3.2.0"))
                implementation("org.springframework:spring-core:6.1.0")
            }
            """.trimIndent()
        )

        val coords = stage().parseGradleDependencies(projectDir)
        assertTrue(coords.none { it.contains("platform") || it.contains("bom") }, "BOM entries should be ignored")
        assertTrue(coords.any { it.contains("spring-core") }, "Regular dependency should be included")
    }
}
