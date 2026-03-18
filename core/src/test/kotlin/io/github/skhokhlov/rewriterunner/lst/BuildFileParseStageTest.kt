package io.github.skhokhlov.rewriterunner.lst

import io.github.skhokhlov.rewriterunner.AetherContext
import io.github.skhokhlov.rewriterunner.NoOpRunnerLogger
import io.kotest.core.spec.style.FunSpec
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [BuildFileParseStage]: static build-file coordinate discovery and
 * POM-traversal delegation.
 *
 * Resolution calls are intercepted via subclassing of [BuildFileParseStage] so
 * no network access is required.
 */
class BuildFileParseStageTest :
    FunSpec({
        var projectDir: Path = Path.of("")
        var cacheDir: Path = Path.of("")

        beforeEach {
            projectDir = Files.createTempDirectory("bfrs-project-")
            cacheDir = Files.createTempDirectory("bfrs-cache-")
        }

        afterEach {
            projectDir.toFile().deleteRecursively()
            cacheDir.toFile().deleteRecursively()
        }

        // ─── Helper: fake BuildFileParseStage ───────────────────────────────────

        var capturedCoords: List<String> = emptyList()
        var traversalCalled = false

        fun resetTracking() {
            capturedCoords = emptyList()
            traversalCalled = false
        }

        fun fakeStage(traversalResult: List<Path> = emptyList()): BuildFileParseStage =
            object : BuildFileParseStage(
                AetherContext.build(cacheDir.resolve("repo"), logger = NoOpRunnerLogger),
                NoOpRunnerLogger
            ) {
                override fun resolveWithPomTraversal(coordinates: List<String>): List<Path> {
                    traversalCalled = true
                    capturedCoords = coordinates
                    return traversalResult
                }
            }

        // ─── Maven single-module ──────────────────────────────────────────────────

        test("Maven single-module: parses pom.xml and calls resolveWithPomTraversal") {
            resetTracking()
            projectDir.resolve("pom.xml").writeText(
                """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>single</artifactId>
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
                            <version>32.0.0-jre</version>
                        </dependency>
                    </dependencies>
                </project>
                """.trimIndent()
            )

            fakeStage().resolveClasspath(projectDir)

            assertTrue(traversalCalled, "resolveWithPomTraversal should be called")
            assertTrue(
                capturedCoords.any { it.contains("commons-lang3") },
                "commons-lang3 should appear in captured coords"
            )
            assertTrue(
                capturedCoords.any { it.contains("guava") },
                "guava should appear in captured coords"
            )
        }

        // ─── Maven multi-module ───────────────────────────────────────────────────

        test("Maven multi-module: discovers and parses module pom.xml files") {
            resetTracking()
            // Root aggregator POM with two modules declared
            projectDir.resolve("pom.xml").writeText(
                """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>parent</artifactId>
                    <version>1.0</version>
                    <packaging>pom</packaging>
                    <modules>
                        <module>api</module>
                        <module>impl</module>
                    </modules>
                </project>
                """.trimIndent()
            )
            projectDir.resolve("api").createDirectories()
            projectDir.resolve("api/pom.xml").writeText(
                """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>api</artifactId>
                    <version>1.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.apache.commons</groupId>
                            <artifactId>commons-lang3</artifactId>
                            <version>3.12.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """.trimIndent()
            )
            projectDir.resolve("impl").createDirectories()
            projectDir.resolve("impl/pom.xml").writeText(
                """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>impl</artifactId>
                    <version>1.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>com.google.guava</groupId>
                            <artifactId>guava</artifactId>
                            <version>32.0.0-jre</version>
                        </dependency>
                    </dependencies>
                </project>
                """.trimIndent()
            )

            fakeStage().resolveClasspath(projectDir)

            assertTrue(traversalCalled)
            assertTrue(capturedCoords.any { it.contains("commons-lang3") })
            assertTrue(capturedCoords.any { it.contains("guava") })
        }

        // ─── Maven: subdir discovery (no root pom.xml) ────────────────────────────

        test("Maven: no pom.xml in root but present in subdir → discovers it") {
            resetTracking()
            val appDir = projectDir.resolve("app").also { it.createDirectories() }
            appDir.resolve("pom.xml").writeText(
                """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>app</artifactId>
                    <version>1.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.apache.commons</groupId>
                            <artifactId>commons-lang3</artifactId>
                            <version>3.12.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """.trimIndent()
            )

            fakeStage().resolveClasspath(projectDir)

            assertTrue(traversalCalled)
            assertTrue(capturedCoords.any { it.contains("commons-lang3") })
        }

        // ─── Maven: traversal returns JARs ────────────────────────────────────────

        test("Maven: resolveWithPomTraversal returns JARs → resolveClasspath returns them") {
            resetTracking()
            val fakeJar = cacheDir.resolve("fake.jar").also { it.writeText("") }
            projectDir.resolve("pom.xml").writeText(
                """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>test</artifactId>
                    <version>1.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.slf4j</groupId>
                            <artifactId>slf4j-api</artifactId>
                            <version>2.0.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """.trimIndent()
            )

            val result = fakeStage(listOf(fakeJar)).resolveClasspath(projectDir)

            assertEquals(listOf(fakeJar), result)
        }

        // ─── Maven: traversal returns empty ───────────────────────────────────────

        test("Maven: resolveWithPomTraversal returns empty → stage returns empty") {
            resetTracking()
            projectDir.resolve("pom.xml").writeText(
                """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>test</artifactId>
                    <version>1.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.slf4j</groupId>
                            <artifactId>slf4j-api</artifactId>
                            <version>2.0.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """.trimIndent()
            )

            val result = fakeStage().resolveClasspath(projectDir)

            assertTrue(result.isEmpty())
        }

        // ─── Gradle single-module ─────────────────────────────────────────────────

        test("Gradle single-module: parses build.gradle and calls resolveWithPomTraversal") {
            resetTracking()
            projectDir.resolve("build.gradle").writeText(
                """
                dependencies {
                    implementation 'org.apache.commons:commons-lang3:3.12.0'
                }
                """.trimIndent()
            )

            fakeStage().resolveClasspath(projectDir)

            assertTrue(traversalCalled)
            assertTrue(capturedCoords.any { it.contains("commons-lang3") })
        }

        // ─── Gradle multi-module ──────────────────────────────────────────────────

        test("Gradle multi-module: parses settings.gradle includes + subproject build files") {
            resetTracking()
            projectDir.resolve("settings.gradle").writeText("include ':sub'")
            projectDir.resolve("build.gradle").writeText(
                """
                dependencies {
                    implementation 'org.apache.commons:commons-lang3:3.12.0'
                }
                """.trimIndent()
            )
            val subDir = projectDir.resolve("sub").also { it.createDirectories() }
            subDir.resolve("build.gradle").writeText(
                """
                dependencies {
                    implementation 'com.google.guava:guava:32.0.0-jre'
                }
                """.trimIndent()
            )

            fakeStage().resolveClasspath(projectDir)

            assertTrue(traversalCalled)
            assertTrue(capturedCoords.any { it.contains("commons-lang3") })
            assertTrue(capturedCoords.any { it.contains("guava") })
        }

        // ─── Gradle version catalog ───────────────────────────────────────────────

        test("Gradle version catalog: resolves version.ref entries from libs.versions.toml") {
            resetTracking()
            projectDir.resolve("build.gradle").writeText("// no inline deps")
            projectDir.resolve("gradle").also { it.createDirectories() }
            projectDir.resolve("gradle/libs.versions.toml").writeText(
                """
                [versions]
                slf4j = "2.0.7"

                [libraries]
                slf4j-api = { module = "org.slf4j:slf4j-api", version.ref = "slf4j" }
                """.trimIndent()
            )

            fakeStage().resolveClasspath(projectDir)

            assertTrue(traversalCalled)
            assertTrue(capturedCoords.any { it == "org.slf4j:slf4j-api:2.0.7" })
        }

        // ─── Gradle Kotlin DSL ────────────────────────────────────────────────────

        test("Gradle: build.gradle.kts (Kotlin DSL) is parsed") {
            resetTracking()
            projectDir.resolve("build.gradle.kts").writeText(
                """
                dependencies {
                    implementation("org.apache.commons:commons-lang3:3.12.0")
                }
                """.trimIndent()
            )

            fakeStage().resolveClasspath(projectDir)

            assertTrue(traversalCalled)
            assertTrue(capturedCoords.any { it.contains("commons-lang3") })
        }

        // ─── Gradle: subdir build file discovery ──────────────────────────────────

        test("Gradle: no build file in root but present in subdir → discovers it") {
            resetTracking()
            val appDir = projectDir.resolve("app").also { it.createDirectories() }
            appDir.resolve("build.gradle").writeText(
                """
                dependencies {
                    implementation 'org.apache.commons:commons-lang3:3.12.0'
                }
                """.trimIndent()
            )

            fakeStage().resolveClasspath(projectDir)

            assertTrue(traversalCalled)
            assertTrue(capturedCoords.any { it.contains("commons-lang3") })
        }

        // ─── Mixed Maven + Gradle ─────────────────────────────────────────────────

        test("Mixed Maven+Gradle: both pom.xml and build.gradle parsed and coords combined") {
            resetTracking()
            projectDir.resolve("pom.xml").writeText(
                """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>mixed</artifactId>
                    <version>1.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.apache.commons</groupId>
                            <artifactId>commons-lang3</artifactId>
                            <version>3.12.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """.trimIndent()
            )
            projectDir.resolve("build.gradle").writeText(
                """
                dependencies {
                    implementation 'com.google.guava:guava:32.0.0-jre'
                }
                """.trimIndent()
            )

            fakeStage().resolveClasspath(projectDir)

            assertTrue(traversalCalled)
            assertTrue(
                capturedCoords.any {
                    it.contains("commons-lang3")
                },
                "Maven dep should appear"
            )
            assertTrue(capturedCoords.any { it.contains("guava") }, "Gradle dep should appear")
        }

        // ─── Edge cases ───────────────────────────────────────────────────────────

        test("No build files anywhere → returns empty without calling resolveWithPomTraversal") {
            resetTracking()
            // Empty project dir — no pom.xml, no build.gradle

            val result = fakeStage().resolveClasspath(projectDir)

            assertTrue(result.isEmpty())
            assertFalse(traversalCalled, "resolveWithPomTraversal should NOT be called")
        }

        test("resolveWithPomTraversal receives deduplicated coords across all build files") {
            resetTracking()
            // Same dep declared in root pom and submodule
            projectDir.resolve("pom.xml").writeText(
                """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>parent</artifactId>
                    <version>1.0</version>
                    <packaging>pom</packaging>
                    <modules>
                        <module>child</module>
                    </modules>
                    <dependencies>
                        <dependency>
                            <groupId>org.apache.commons</groupId>
                            <artifactId>commons-lang3</artifactId>
                            <version>3.12.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """.trimIndent()
            )
            val childDir = projectDir.resolve("child").also { it.createDirectories() }
            childDir.resolve("pom.xml").writeText(
                """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>child</artifactId>
                    <version>1.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.apache.commons</groupId>
                            <artifactId>commons-lang3</artifactId>
                            <version>3.12.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """.trimIndent()
            )

            fakeStage().resolveClasspath(projectDir)

            assertTrue(traversalCalled)
            val commonsCount = capturedCoords.count { it.contains("commons-lang3") }
            assertEquals(1, commonsCount, "Duplicate coord should appear only once")
        }

        test("stage returns empty when resolveWithPomTraversal returns empty") {
            resetTracking()
            projectDir.resolve("build.gradle").writeText(
                """
                dependencies {
                    implementation 'org.apache.commons:commons-lang3:3.12.0'
                }
                """.trimIndent()
            )

            val result = fakeStage().resolveClasspath(projectDir)

            assertTrue(result.isEmpty())
        }
    })
