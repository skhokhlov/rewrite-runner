package io.github.skhokhlov.rewriterunner.lst

import io.github.skhokhlov.rewriterunner.AetherContext
import io.github.skhokhlov.rewriterunner.NoOpRunnerLogger
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
 * Verifies that [LstBuilder] attaches the correct [JavaVersion] marker (JVM target) to
 * parsed Kotlin source files by reading the project's build descriptor.
 *
 * Kotlin-specific settings (`kotlinOptions.jvmTarget`, `kotlin-maven-plugin <jvmTarget>`)
 * take priority over shared Java settings; shared settings (`jvmToolchain`,
 * `sourceCompatibility`, maven-compiler-plugin) serve as fallback.
 */
class KotlinVersionDetectionTest :
    FunSpec({
        var projectDir: Path = Path.of("")

        beforeEach { projectDir = Files.createTempDirectory("kvdt-") }

        afterEach { projectDir.toFile().deleteRecursively() }

        val failingBuildTool =
            object : BuildToolStage(NoOpRunnerLogger) {
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
                    override fun resolveClasspath(projectDir: Path): List<Path> = emptyList()
                }
            return LstBuilder(
                cacheDir = projectDir.resolve("cache"),
                toolConfig = ToolConfig(logger = NoOpRunnerLogger),
                buildToolStage = failingBuildTool,
                depResolutionStage = noOpDepStage,
                logger = NoOpRunnerLogger
            )
        }

        fun buildAndGetKotlinVersion(fileName: String = "Hello.kt"): JavaVersion {
            projectDir.resolve(fileName).writeText("fun main() {}")
            val sources = lstBuilder().build(projectDir, includeExtensionsCli = listOf(".kt"))
            val ktFile = sources.single { it.sourcePath.toString().endsWith(".kt") }
            val marker = ktFile.markers.findFirst(JavaVersion::class.java).orElse(null)
            assertNotNull(marker, "JavaVersion marker must be present on parsed Kotlin source file")
            return marker
        }

        // ─── Gradle: Kotlin-specific jvmTarget ────────────────────────────────────

        test("Gradle kotlinOptions jvmTarget sets version for Kotlin files") {
            projectDir.resolve("build.gradle.kts").writeText(
                """
                plugins { kotlin("jvm") }
                tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
                    kotlinOptions.jvmTarget = "17"
                }
                """.trimIndent()
            )

            val version = buildAndGetKotlinVersion()
            assertEquals("17", version.sourceCompatibility)
            assertEquals("17", version.targetCompatibility)
        }

        test("Gradle jvmTarget Groovy DSL sets version for Kotlin files") {
            projectDir.resolve("build.gradle").writeText(
                """
                plugins { id 'org.jetbrains.kotlin.jvm' }
                compileKotlin { kotlinOptions.jvmTarget = '11' }
                """.trimIndent()
            )

            val version = buildAndGetKotlinVersion()
            assertEquals("11", version.sourceCompatibility)
            assertEquals("11", version.targetCompatibility)
        }

        test("Gradle JvmTarget constant sets version for Kotlin files") {
            projectDir.resolve("build.gradle.kts").writeText(
                """
                plugins { kotlin("jvm") }
                tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
                    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
                }
                """.trimIndent()
            )

            val version = buildAndGetKotlinVersion()
            assertEquals("21", version.sourceCompatibility)
            assertEquals("21", version.targetCompatibility)
        }

        test("Gradle JvmTarget JVM_1_8 constant is normalized to 8") {
            projectDir.resolve("build.gradle.kts").writeText(
                """
                plugins { kotlin("jvm") }
                tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
                    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
                }
                """.trimIndent()
            )

            val version = buildAndGetKotlinVersion()
            assertEquals("8", version.sourceCompatibility)
            assertEquals("8", version.targetCompatibility)
        }

        test("Gradle jvmTarget takes precedence over jvmToolchain for Kotlin files") {
            projectDir.resolve("build.gradle.kts").writeText(
                """
                plugins { kotlin("jvm") }
                kotlin { jvmToolchain(21) }
                compileKotlin { kotlinOptions.jvmTarget = "17" }
                """.trimIndent()
            )

            val version = buildAndGetKotlinVersion()
            assertEquals("17", version.sourceCompatibility)
        }

        // ─── Gradle: fallback to shared Java settings ─────────────────────────────

        test("Gradle jvmToolchain is used when no jvmTarget is set") {
            projectDir.resolve("build.gradle.kts").writeText(
                """
                plugins { kotlin("jvm") }
                kotlin { jvmToolchain(21) }
                """.trimIndent()
            )

            val version = buildAndGetKotlinVersion()
            assertEquals("21", version.sourceCompatibility)
            assertEquals("21", version.targetCompatibility)
        }

        test("Gradle sourceCompatibility is used as fallback for Kotlin files") {
            projectDir.resolve("build.gradle").writeText(
                """
                plugins { id 'java' }
                sourceCompatibility = '17'
                targetCompatibility = '17'
                """.trimIndent()
            )

            val version = buildAndGetKotlinVersion()
            assertEquals("17", version.sourceCompatibility)
            assertEquals("17", version.targetCompatibility)
        }

        // ─── Maven: Kotlin-specific jvmTarget ─────────────────────────────────────

        test("Maven kotlin-maven-plugin jvmTarget sets version for Kotlin files") {
            projectDir.resolve("pom.xml").writeText(
                """
                <project>
                  <build>
                    <plugins>
                      <plugin>
                        <groupId>org.jetbrains.kotlin</groupId>
                        <artifactId>kotlin-maven-plugin</artifactId>
                        <configuration>
                          <jvmTarget>17</jvmTarget>
                        </configuration>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """.trimIndent()
            )

            val version = buildAndGetKotlinVersion()
            assertEquals("17", version.sourceCompatibility)
            assertEquals("17", version.targetCompatibility)
        }

        test("Maven kotlin-maven-plugin jvmTarget takes precedence over maven-compiler-plugin") {
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
                      <plugin>
                        <groupId>org.jetbrains.kotlin</groupId>
                        <artifactId>kotlin-maven-plugin</artifactId>
                        <configuration>
                          <jvmTarget>17</jvmTarget>
                        </configuration>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """.trimIndent()
            )

            val version = buildAndGetKotlinVersion()
            assertEquals("17", version.sourceCompatibility)
        }

        // ─── Maven: fallback to shared Java settings ──────────────────────────────

        test("Maven maven-compiler-plugin release is used as fallback for Kotlin files") {
            projectDir.resolve("pom.xml").writeText(
                """
                <project>
                  <build>
                    <plugins>
                      <plugin>
                        <artifactId>maven-compiler-plugin</artifactId>
                        <configuration>
                          <release>21</release>
                        </configuration>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """.trimIndent()
            )

            val version = buildAndGetKotlinVersion()
            assertEquals("21", version.sourceCompatibility)
            assertEquals("21", version.targetCompatibility)
        }

        test("Maven maven.compiler.release property is used as fallback for Kotlin files") {
            projectDir.resolve("pom.xml").writeText(
                """
                <project>
                  <properties>
                    <maven.compiler.release>17</maven.compiler.release>
                  </properties>
                </project>
                """.trimIndent()
            )

            val version = buildAndGetKotlinVersion()
            assertEquals("17", version.sourceCompatibility)
            assertEquals("17", version.targetCompatibility)
        }

        // ─── No build file fallback ───────────────────────────────────────────────

        test("no build file falls back to running JVM major version for Kotlin files") {
            val version = buildAndGetKotlinVersion()
            val jvmMajor = jvmMajorVersion()
            assertEquals(jvmMajor, version.sourceCompatibility)
            assertEquals(jvmMajor, version.targetCompatibility)
        }

        // ─── Multi-subproject resolution ──────────────────────────────────────────

        test("subproject build file sets version for Kotlin files in that subproject") {
            Files.createDirectories(projectDir.resolve("subproject1"))
            projectDir.resolve("subproject1/build.gradle.kts").writeText(
                """
                plugins { kotlin("jvm") }
                compileKotlin { kotlinOptions.jvmTarget = "17" }
                """.trimIndent()
            )

            val absPath = projectDir.resolve("subproject1/src/main/kotlin/Hello.kt")
            Files.createDirectories(absPath.parent)
            absPath.writeText("fun main() {}")
            val sources = lstBuilder().build(projectDir, includeExtensionsCli = listOf(".kt"))
            val marker = sources
                .single { it.sourcePath.toString() == "subproject1/src/main/kotlin/Hello.kt" }
                .markers.findFirst(JavaVersion::class.java).orElse(null)
            assertNotNull(marker)
            assertEquals("17", marker.sourceCompatibility)
        }

        test(
            "deeply nested Kotlin file falls back to root build file when no subproject build file"
        ) {
            projectDir.resolve("build.gradle.kts").writeText(
                """
                plugins { kotlin("jvm") }
                kotlin { jvmToolchain(11) }
                """.trimIndent()
            )

            val absPath = projectDir.resolve("subproject1/src/main/kotlin/Hello.kt")
            Files.createDirectories(absPath.parent)
            absPath.writeText("fun main() {}")
            val sources = lstBuilder().build(projectDir, includeExtensionsCli = listOf(".kt"))
            val marker = sources
                .single { it.sourcePath.toString() == "subproject1/src/main/kotlin/Hello.kt" }
                .markers.findFirst(JavaVersion::class.java).orElse(null)
            assertNotNull(marker)
            assertEquals("11", marker.sourceCompatibility)
        }

        // ─── .kts files also receive the marker ───────────────────────────────────

        test("plain kts file receives JavaVersion marker") {
            projectDir.resolve("build.gradle.kts").writeText(
                """
                plugins { kotlin("jvm") }
                kotlin { jvmToolchain(21) }
                """.trimIndent()
            )
            projectDir.resolve("script.kts").writeText("println(\"hello\")")

            val sources = lstBuilder().build(projectDir, includeExtensionsCli = listOf(".kts"))
            val ktsFile = sources.single { it.sourcePath.toString() == "script.kts" }
            val marker = ktsFile.markers.findFirst(JavaVersion::class.java).orElse(null)
            assertNotNull(marker, "JavaVersion marker must be present on .kts file")
            assertEquals("21", marker.sourceCompatibility)
        }
    })
