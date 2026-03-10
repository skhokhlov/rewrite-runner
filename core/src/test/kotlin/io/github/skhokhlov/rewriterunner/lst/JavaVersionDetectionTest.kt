package io.github.skhokhlov.rewriterunner.lst

import io.github.skhokhlov.rewriterunner.AetherContext
import io.github.skhokhlov.rewriterunner.config.ToolConfig
import io.kotest.core.spec.style.FunSpec
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.openrewrite.java.marker.JavaVersion

/** Returns the major version string of the running JVM, e.g. "21". */
private fun jvmMajorVersion(): String {
    val v = System.getProperty("java.version") ?: ""
    val stripped = if (v.startsWith("1.")) v.removePrefix("1.") else v
    return stripped.substringBefore(".")
}

/**
 * Verifies that [LstBuilder] attaches the correct [JavaVersion] marker to parsed
 * Java source files by reading the project's build descriptor (pom.xml or build.gradle)
 * rather than defaulting to the running JVM version.
 *
 * Each test creates a minimal project in a temp dir with a simple Java file and
 * a specific build-file configuration, then inspects the marker on the returned LST.
 */
class JavaVersionDetectionTest :
    FunSpec({
        var projectDir: Path = Path.of("")

        beforeEach { projectDir = Files.createTempDirectory("jvdt-") }

        afterEach { projectDir.toFile().deleteRecursively() }

        val failingBuildTool =
            object : BuildToolStage() {
                override fun extractClasspath(projectDir: Path): List<Path>? = null
            }

        fun lstBuilder(): LstBuilder {
            val noOpDepStage =
                object : DependencyResolutionStage(
                    AetherContext.build(projectDir.resolve("cache"))
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

        fun buildAndGetJavaVersion(): JavaVersion {
            projectDir.resolve("Hello.java").writeText("class Hello {}")
            val sources =
                lstBuilder().build(projectDir, includeExtensionsCli = listOf(".java"))
            val javaFile = sources.single { it.sourcePath.toString().endsWith(".java") }
            val marker = javaFile.markers.findFirst(JavaVersion::class.java).orElse(null)
            assertNotNull(
                marker,
                "JavaVersion marker must be present on parsed Java source file"
            )
            return marker
        }

        // ─── Maven: maven-compiler-plugin <configuration> ─────────────────────────

        test("Maven compiler plugin release element sets source and target") {
            projectDir.resolve("pom.xml").writeText(
                """
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
                """.trimIndent()
            )

            val version = buildAndGetJavaVersion()
            assertEquals("17", version.sourceCompatibility)
            assertEquals("17", version.targetCompatibility)
        }

        test("Maven compiler plugin source and target elements set independently") {
            projectDir.resolve("pom.xml").writeText(
                """
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
                """.trimIndent()
            )

            val version = buildAndGetJavaVersion()
            assertEquals("11", version.sourceCompatibility)
            assertEquals("11", version.targetCompatibility)
        }

        test("Maven compiler plugin release takes priority over source and target elements") {
            projectDir.resolve("pom.xml").writeText(
                """
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
                """.trimIndent()
            )

            val version = buildAndGetJavaVersion()
            assertEquals("21", version.sourceCompatibility)
            assertEquals("21", version.targetCompatibility)
        }

        // ─── Maven: project <properties> ─────────────────────────────────────────

        test("Maven maven compiler release property sets source and target") {
            projectDir.resolve("pom.xml").writeText(
                """
                <project>
                  <properties>
                    <maven.compiler.release>17</maven.compiler.release>
                  </properties>
                </project>
                """.trimIndent()
            )

            val version = buildAndGetJavaVersion()
            assertEquals("17", version.sourceCompatibility)
            assertEquals("17", version.targetCompatibility)
        }

        test("Maven maven compiler source and target properties") {
            projectDir.resolve("pom.xml").writeText(
                """
                <project>
                  <properties>
                    <maven.compiler.source>8</maven.compiler.source>
                    <maven.compiler.target>8</maven.compiler.target>
                  </properties>
                </project>
                """.trimIndent()
            )

            val version = buildAndGetJavaVersion()
            assertEquals("8", version.sourceCompatibility)
            assertEquals("8", version.targetCompatibility)
        }

        test("Maven plugin configuration takes priority over project properties") {
            projectDir.resolve("pom.xml").writeText(
                """
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
                """.trimIndent()
            )

            val version = buildAndGetJavaVersion()
            assertEquals("17", version.sourceCompatibility)
            assertEquals("17", version.targetCompatibility)
        }

        test(
            "Maven property interpolation placeholders are ignored and fall back to JVM version"
        ) {
            projectDir.resolve("pom.xml").writeText(
                """
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
                """.trimIndent()
            )

            val version = buildAndGetJavaVersion()
            val jvmMajor = jvmMajorVersion()
            // Interpolation placeholder must be skipped; falls back to JVM version
            assertEquals(jvmMajor, version.sourceCompatibility)
        }

        // ─── Maven: Java 8 legacy "1.8" format ───────────────────────────────────

        test("Maven compiler plugin release 8 sets source and target") {
            projectDir.resolve("pom.xml").writeText(
                """
                <project>
                  <build>
                    <plugins>
                      <plugin>
                        <artifactId>maven-compiler-plugin</artifactId>
                        <configuration>
                          <release>8</release>
                        </configuration>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """.trimIndent()
            )

            val version = buildAndGetJavaVersion()
            assertEquals("8", version.sourceCompatibility)
            assertEquals("8", version.targetCompatibility)
        }

        test("Maven compiler plugin source 1dot8 legacy format is normalized to 8") {
            projectDir.resolve("pom.xml").writeText(
                """
                <project>
                  <build>
                    <plugins>
                      <plugin>
                        <artifactId>maven-compiler-plugin</artifactId>
                        <configuration>
                          <source>1.8</source>
                          <target>1.8</target>
                        </configuration>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """.trimIndent()
            )

            val version = buildAndGetJavaVersion()
            assertEquals(
                "8",
                version.sourceCompatibility,
                "Legacy '1.8' source must be normalized to '8'"
            )
            assertEquals(
                "8",
                version.targetCompatibility,
                "Legacy '1.8' target must be normalized to '8'"
            )
        }

        test("Maven maven compiler source 1dot8 property is normalized to 8") {
            projectDir.resolve("pom.xml").writeText(
                """
                <project>
                  <properties>
                    <maven.compiler.source>1.8</maven.compiler.source>
                    <maven.compiler.target>1.8</maven.compiler.target>
                  </properties>
                </project>
                """.trimIndent()
            )

            val version = buildAndGetJavaVersion()
            assertEquals(
                "8",
                version.sourceCompatibility,
                "Legacy '1.8' property must be normalized to '8'"
            )
            assertEquals(
                "8",
                version.targetCompatibility,
                "Legacy '1.8' property must be normalized to '8'"
            )
        }

        // ─── Maven: Java 11 ───────────────────────────────────────────────────────

        test("Maven compiler plugin release element for Java 11") {
            projectDir.resolve("pom.xml").writeText(
                """
                <project>
                  <build>
                    <plugins>
                      <plugin>
                        <artifactId>maven-compiler-plugin</artifactId>
                        <configuration>
                          <release>11</release>
                        </configuration>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """.trimIndent()
            )

            val version = buildAndGetJavaVersion()
            assertEquals("11", version.sourceCompatibility)
            assertEquals("11", version.targetCompatibility)
        }

        test("Maven maven compiler release property for Java 11") {
            projectDir.resolve("pom.xml").writeText(
                """
                <project>
                  <properties>
                    <maven.compiler.release>11</maven.compiler.release>
                  </properties>
                </project>
                """.trimIndent()
            )

            val version = buildAndGetJavaVersion()
            assertEquals("11", version.sourceCompatibility)
            assertEquals("11", version.targetCompatibility)
        }

        // ─── Maven: Java 21 ───────────────────────────────────────────────────────

        test("Maven maven compiler release property for Java 21") {
            projectDir.resolve("pom.xml").writeText(
                """
                <project>
                  <properties>
                    <maven.compiler.release>21</maven.compiler.release>
                  </properties>
                </project>
                """.trimIndent()
            )

            val version = buildAndGetJavaVersion()
            assertEquals("21", version.sourceCompatibility)
            assertEquals("21", version.targetCompatibility)
        }

        // ─── Maven: Java 25 ───────────────────────────────────────────────────────

        test("Maven compiler plugin release element for Java 25") {
            projectDir.resolve("pom.xml").writeText(
                """
                <project>
                  <build>
                    <plugins>
                      <plugin>
                        <artifactId>maven-compiler-plugin</artifactId>
                        <configuration>
                          <release>25</release>
                        </configuration>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """.trimIndent()
            )

            val version = buildAndGetJavaVersion()
            assertEquals("25", version.sourceCompatibility)
            assertEquals("25", version.targetCompatibility)
        }

        test("Maven maven compiler release property for Java 25") {
            projectDir.resolve("pom.xml").writeText(
                """
                <project>
                  <properties>
                    <maven.compiler.release>25</maven.compiler.release>
                  </properties>
                </project>
                """.trimIndent()
            )

            val version = buildAndGetJavaVersion()
            assertEquals("25", version.sourceCompatibility)
            assertEquals("25", version.targetCompatibility)
        }

        test("Maven compiler plugin source and target elements for Java 25") {
            projectDir.resolve("pom.xml").writeText(
                """
                <project>
                  <build>
                    <plugins>
                      <plugin>
                        <artifactId>maven-compiler-plugin</artifactId>
                        <configuration>
                          <source>25</source>
                          <target>25</target>
                        </configuration>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """.trimIndent()
            )

            val version = buildAndGetJavaVersion()
            assertEquals("25", version.sourceCompatibility)
            assertEquals("25", version.targetCompatibility)
        }

        // ─── Gradle ───────────────────────────────────────────────────────────────

        test("Gradle sourceCompatibility Groovy DSL quoted string") {
            projectDir.resolve("build.gradle").writeText(
                """
                plugins { id 'java' }
                sourceCompatibility = '17'
                targetCompatibility = '17'
                """.trimIndent()
            )

            val version = buildAndGetJavaVersion()
            assertEquals("17", version.sourceCompatibility)
            assertEquals("17", version.targetCompatibility)
        }

        test("Gradle sourceCompatibility Kotlin DSL JavaVersion constant") {
            projectDir.resolve("build.gradle.kts").writeText(
                """
                plugins { java }
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
                """.trimIndent()
            )

            val version = buildAndGetJavaVersion()
            assertEquals("17", version.sourceCompatibility)
            assertEquals("17", version.targetCompatibility)
        }

        test("Gradle jvmToolchain sets source and target") {
            projectDir.resolve("build.gradle.kts").writeText(
                """
                plugins { kotlin("jvm") }
                kotlin { jvmToolchain(21) }
                """.trimIndent()
            )

            val version = buildAndGetJavaVersion()
            assertEquals("21", version.sourceCompatibility)
            assertEquals("21", version.targetCompatibility)
        }

        test("Gradle java toolchain block with JavaLanguageVersion of") {
            projectDir.resolve("build.gradle.kts").writeText(
                """
                plugins { java }
                java {
                    toolchain {
                        languageVersion = JavaLanguageVersion.of(17)
                    }
                }
                """.trimIndent()
            )

            val version = buildAndGetJavaVersion()
            assertEquals("17", version.sourceCompatibility)
            assertEquals("17", version.targetCompatibility)
        }

        // ─── Gradle: Java 8 ──────────────────────────────────────────────────────

        test("Gradle sourceCompatibility 1dot8 legacy format is normalized to 8") {
            projectDir.resolve("build.gradle").writeText(
                """
                plugins { id 'java' }
                sourceCompatibility = '1.8'
                targetCompatibility = '1.8'
                """.trimIndent()
            )

            val version = buildAndGetJavaVersion()
            assertEquals(
                "8",
                version.sourceCompatibility,
                "Legacy '1.8' Gradle format must be normalized to '8'"
            )
            assertEquals(
                "8",
                version.targetCompatibility,
                "Legacy '1.8' Gradle format must be normalized to '8'"
            )
        }

        test("Gradle sourceCompatibility VERSION_1_8 constant is normalized to 8") {
            projectDir.resolve("build.gradle.kts").writeText(
                """
                plugins { java }
                sourceCompatibility = JavaVersion.VERSION_1_8
                targetCompatibility = JavaVersion.VERSION_1_8
                """.trimIndent()
            )

            val version = buildAndGetJavaVersion()
            assertEquals(
                "8",
                version.sourceCompatibility,
                "JavaVersion.VERSION_1_8 must be normalized to '8'"
            )
            assertEquals(
                "8",
                version.targetCompatibility,
                "JavaVersion.VERSION_1_8 must be normalized to '8'"
            )
        }

        test("Gradle jvmToolchain 8 sets source and target") {
            projectDir.resolve("build.gradle.kts").writeText(
                """
                plugins { kotlin("jvm") }
                kotlin { jvmToolchain(8) }
                """.trimIndent()
            )

            val version = buildAndGetJavaVersion()
            assertEquals("8", version.sourceCompatibility)
            assertEquals("8", version.targetCompatibility)
        }

        test("Gradle JavaLanguageVersion of 8 sets source and target") {
            projectDir.resolve("build.gradle.kts").writeText(
                """
                plugins { java }
                java {
                    toolchain {
                        languageVersion = JavaLanguageVersion.of(8)
                    }
                }
                """.trimIndent()
            )

            val version = buildAndGetJavaVersion()
            assertEquals("8", version.sourceCompatibility)
            assertEquals("8", version.targetCompatibility)
        }

        // ─── Gradle: Java 11 ─────────────────────────────────────────────────────

        test("Gradle sourceCompatibility Groovy DSL for Java 11") {
            projectDir.resolve("build.gradle").writeText(
                """
                plugins { id 'java' }
                sourceCompatibility = '11'
                targetCompatibility = '11'
                """.trimIndent()
            )

            val version = buildAndGetJavaVersion()
            assertEquals("11", version.sourceCompatibility)
            assertEquals("11", version.targetCompatibility)
        }

        test("Gradle jvmToolchain for Java 11") {
            projectDir.resolve("build.gradle.kts").writeText(
                """
                plugins { kotlin("jvm") }
                kotlin { jvmToolchain(11) }
                """.trimIndent()
            )

            val version = buildAndGetJavaVersion()
            assertEquals("11", version.sourceCompatibility)
            assertEquals("11", version.targetCompatibility)
        }

        test("Gradle JavaLanguageVersion of 11") {
            projectDir.resolve("build.gradle.kts").writeText(
                """
                plugins { java }
                java {
                    toolchain {
                        languageVersion = JavaLanguageVersion.of(11)
                    }
                }
                """.trimIndent()
            )

            val version = buildAndGetJavaVersion()
            assertEquals("11", version.sourceCompatibility)
            assertEquals("11", version.targetCompatibility)
        }

        // ─── Gradle: Java 21 ─────────────────────────────────────────────────────

        test("Gradle sourceCompatibility Groovy DSL for Java 21") {
            projectDir.resolve("build.gradle").writeText(
                """
                plugins { id 'java' }
                sourceCompatibility = '21'
                targetCompatibility = '21'
                """.trimIndent()
            )

            val version = buildAndGetJavaVersion()
            assertEquals("21", version.sourceCompatibility)
            assertEquals("21", version.targetCompatibility)
        }

        test("Gradle JavaLanguageVersion of 21") {
            projectDir.resolve("build.gradle.kts").writeText(
                """
                plugins { java }
                java {
                    toolchain {
                        languageVersion = JavaLanguageVersion.of(21)
                    }
                }
                """.trimIndent()
            )

            val version = buildAndGetJavaVersion()
            assertEquals("21", version.sourceCompatibility)
            assertEquals("21", version.targetCompatibility)
        }

        // ─── Gradle: Java 25 ─────────────────────────────────────────────────────

        test("Gradle sourceCompatibility for Java 25") {
            projectDir.resolve("build.gradle").writeText(
                """
                plugins { id 'java' }
                sourceCompatibility = '25'
                targetCompatibility = '25'
                """.trimIndent()
            )

            val version = buildAndGetJavaVersion()
            assertEquals("25", version.sourceCompatibility)
            assertEquals("25", version.targetCompatibility)
        }

        test("Gradle jvmToolchain for Java 25") {
            projectDir.resolve("build.gradle.kts").writeText(
                """
                plugins { kotlin("jvm") }
                kotlin { jvmToolchain(25) }
                """.trimIndent()
            )

            val version = buildAndGetJavaVersion()
            assertEquals("25", version.sourceCompatibility)
            assertEquals("25", version.targetCompatibility)
        }

        test("Gradle JavaLanguageVersion of 25") {
            projectDir.resolve("build.gradle.kts").writeText(
                """
                plugins { java }
                java {
                    toolchain {
                        languageVersion = JavaLanguageVersion.of(25)
                    }
                }
                """.trimIndent()
            )

            val version = buildAndGetJavaVersion()
            assertEquals("25", version.sourceCompatibility)
            assertEquals("25", version.targetCompatibility)
        }

        test("Gradle options release takes priority over sourceCompatibility") {
            projectDir.resolve("build.gradle").writeText(
                """
                plugins { id 'java' }
                sourceCompatibility = '11'
                targetCompatibility = '11'
                compileJava.options.release = 17
                """.trimIndent()
            )

            val version = buildAndGetJavaVersion()
            assertEquals("17", version.sourceCompatibility)
            assertEquals("17", version.targetCompatibility)
        }

        // ─── No build file fallback ───────────────────────────────────────────────

        test("no build file falls back to running JVM major version") {
            // No pom.xml or build.gradle in projectDir
            val version = buildAndGetJavaVersion()
            val jvmMajor = jvmMajorVersion()
            assertEquals(jvmMajor, version.sourceCompatibility)
            assertEquals(jvmMajor, version.targetCompatibility)
        }

        // ─── Marker metadata ──────────────────────────────────────────────────────

        test("JavaVersion marker carries JVM runtime version and vendor") {
            projectDir.resolve("pom.xml").writeText("<project/>")

            val version = buildAndGetJavaVersion()
            assertNotNull(
                version.createdBy,
                "createdBy must be set from java.runtime.version system property"
            )
            assertNotNull(
                version.vmVendor,
                "vmVendor must be set from java.vm.vendor system property"
            )
        }
    })
