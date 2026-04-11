@file:Suppress("DEPRECATION")

package io.github.skhokhlov.rewriterunner.lst

import io.github.skhokhlov.rewriterunner.AetherContext
import io.github.skhokhlov.rewriterunner.NoOpRunnerLogger
import io.github.skhokhlov.rewriterunner.lst.utils.StaticBuildFileParser
import io.kotest.core.spec.style.FunSpec
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarOutputStream
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.eclipse.aether.ConfigurationProperties
import org.eclipse.aether.repository.LocalRepository
import org.eclipse.aether.repository.RemoteRepository
import org.eclipse.aether.supplier.RepositorySystemSupplier
import org.eclipse.aether.util.graph.transformer.ClassicConflictResolver
import org.eclipse.aether.util.graph.transformer.JavaScopeDeriver
import org.eclipse.aether.util.graph.transformer.JavaScopeSelector
import org.eclipse.aether.util.graph.transformer.NearestVersionSelector
import org.eclipse.aether.util.graph.transformer.SimpleOptionalitySelector

/**
 * Tests the pom.xml / build.gradle parsing logic in DependencyResolutionStage
 * without hitting the network (no actual artifact resolution).
 */
class DependencyResolutionStageTest :
    FunSpec({
        var projectDir: Path = Path.of("")
        var cacheDir: Path = Path.of("")

        beforeEach {
            projectDir = Files.createTempDirectory("drst-project-")
            cacheDir = Files.createTempDirectory("drst-cache-")
        }

        afterEach {
            projectDir.toFile().deleteRecursively()
            cacheDir.toFile().deleteRecursively()
        }

        fun stage() = DependencyResolutionStage(
            AetherContext.build(cacheDir.resolve("repository"), logger = NoOpRunnerLogger),
            NoOpRunnerLogger
        )

        fun staticParser() = StaticBuildFileParser(NoOpRunnerLogger)

        // ─── Maven pom.xml parsing ────────────────────────────────────────────────

        test("parseMavenDependencies extracts compile-scoped dependencies") {
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

            val coords = staticParser().parseMavenDependencies(projectDir)

            assertEquals(2, coords.size)
            assertTrue(coords.contains("org.apache.commons:commons-lang3:3.12.0"))
            assertTrue(coords.contains("com.google.guava:guava:32.1.2-jre"))
        }

        test("parseMavenDependencies includes test-scoped dependencies") {
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

            val coords = staticParser().parseMavenDependencies(projectDir)

            assertEquals(2, coords.size, "Test dependency should not be excluded")
            assertEquals("org.apache.commons:commons-lang3:3.12.0", coords.first())
        }

        test("parseMavenDependencies includes provided-scoped dependencies") {
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

            val coords = staticParser().parseMavenDependencies(projectDir)
            assertEquals(
                1,
                coords.size,
                "Provided dependency must be included for compile-time type resolution"
            )
            assertTrue(coords.contains("javax.servlet:javax.servlet-api:4.0.1"))
        }

        test("parseMavenDependencies skips dependency with property-placeholder version") {
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

            val coords = staticParser().parseMavenDependencies(projectDir)
            assertEquals(1, coords.size, "Dependency with property version should be skipped")
            assertEquals("com.google.guava:guava:32.1.2-jre", coords.first())
        }

        test("parseMavenDependencies returns empty list when pom_xml absent") {
            val coords = staticParser().parseMavenDependencies(projectDir)
            assertEquals(0, coords.size)
        }

        test("parseMavenDependencies returns empty list for malformed pom_xml") {
            projectDir.resolve("pom.xml").writeText("this is not xml")
            val coords = staticParser().parseMavenDependencies(projectDir)
            assertEquals(
                0,
                coords.size,
                "Malformed pom.xml should not throw, should return empty list"
            )
        }

        test("parseMavenDependencies excludes runtime-scoped dependencies") {
            projectDir.resolve("pom.xml").writeText(
                """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>app</artifactId>
                    <version>1.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.postgresql</groupId>
                            <artifactId>postgresql</artifactId>
                            <version>42.7.0</version>
                            <scope>runtime</scope>
                        </dependency>
                    </dependencies>
                </project>
                """.trimIndent()
            )

            val coords = staticParser().parseMavenDependencies(projectDir)
            assertEquals(0, coords.size, "runtime-scoped dependency must be excluded")
        }

        // ─── Static Gradle build file parsing ────────────────────────────────────

        test("parseGradleDependenciesStatically extracts Kotlin DSL string coordinates") {
            projectDir.resolve("build.gradle.kts").writeText(
                """
                dependencies {
                    implementation("org.apache.commons:commons-lang3:3.12.0")
                    implementation("com.google.guava:guava:32.1.2-jre")
                    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
                }
                """.trimIndent()
            )

            val coords = staticParser().parseGradleDependenciesStatically(projectDir)

            assertTrue(
                coords.contains("org.apache.commons:commons-lang3:3.12.0"),
                "Should find commons-lang3"
            )
            assertTrue(coords.contains("com.google.guava:guava:32.1.2-jre"), "Should find guava")
            assertTrue(
                coords.contains("org.junit.jupiter:junit-jupiter:5.10.0"),
                "Should find junit (no scope filter)"
            )
        }

        test("parseGradleDependenciesStatically extracts Groovy DSL single-quoted coordinates") {
            projectDir.resolve("build.gradle").writeText(
                """
                dependencies {
                    implementation 'org.springframework:spring-core:6.1.0'
                    runtimeOnly 'ch.qos.logback:logback-classic:1.4.11'
                }
                """.trimIndent()
            )

            val coords = staticParser().parseGradleDependenciesStatically(projectDir)

            assertTrue(coords.contains("org.springframework:spring-core:6.1.0"))
            assertFalse(
                coords.contains("ch.qos.logback:logback-classic:1.4.11"),
                "runtimeOnly dep must not appear on compile classpath"
            )
        }

        test("parseGradleDependenciesStatically prefers build_gradle_kts over build_gradle") {
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

            val coords = staticParser().parseGradleDependenciesStatically(projectDir)

            assertTrue(
                coords.contains("com.google.guava:guava:32.1.2-jre"),
                "KTS should take precedence"
            )
            assertTrue(
                coords.none { it.contains("commons-lang3") },
                "Groovy file should not be read"
            )
        }

        test("parseGradleDependenciesStatically returns empty list when no build file") {
            val coords = staticParser().parseGradleDependenciesStatically(projectDir)
            assertEquals(0, coords.size)
        }

        test("parseGradleDependenciesStatically scans subproject build files") {
            // Root settings declares two subprojects
            projectDir.resolve("settings.gradle.kts").writeText(
                """
                include(":core", ":api")
                """.trimIndent()
            )
            projectDir.resolve("build.gradle.kts").writeText(
                """
                dependencies {
                    implementation("com.google.guava:guava:32.1.2-jre")
                }
                """.trimIndent()
            )
            val coreDir = projectDir.resolve("core").toFile().also { it.mkdirs() }
            coreDir.toPath().resolve("build.gradle.kts").writeText(
                """
                dependencies {
                    implementation("org.apache.commons:commons-lang3:3.12.0")
                }
                """.trimIndent()
            )
            val apiDir = projectDir.resolve("api").toFile().also { it.mkdirs() }
            apiDir.toPath().resolve("build.gradle").writeText(
                """
                dependencies {
                    implementation 'org.slf4j:slf4j-api:2.0.0'
                }
                """.trimIndent()
            )

            val coords = staticParser().parseGradleDependenciesStatically(projectDir)

            assertTrue(
                coords.contains("com.google.guava:guava:32.1.2-jre"),
                "Root project dependency should be found"
            )
            assertTrue(
                coords.contains("org.apache.commons:commons-lang3:3.12.0"),
                ":core subproject dependency should be found"
            )
            assertTrue(
                coords.contains("org.slf4j:slf4j-api:2.0.0"),
                ":api subproject dependency should be found"
            )
        }

        test("parseGradleDependenciesStatically deduplicates identical coordinates") {
            projectDir.resolve("build.gradle.kts").writeText(
                """
                dependencies {
                    implementation("com.google.guava:guava:32.1.2-jre")
                    implementation("com.google.guava:guava:32.1.2-jre")
                }
                """.trimIndent()
            )

            val coords = staticParser().parseGradleDependenciesStatically(projectDir)
            assertEquals(coords.distinct(), coords, "Coordinates should be deduplicated")
        }

        test("parseGradleDependenciesStatically ignores BOM and platform entries") {
            projectDir.resolve("build.gradle.kts").writeText(
                """
                dependencies {
                    implementation(platform("org.springframework.boot:spring-boot-dependencies:3.2.0"))
                    implementation("org.springframework:spring-core:6.1.0")
                }
                """.trimIndent()
            )

            val coords = staticParser().parseGradleDependenciesStatically(projectDir)
            assertTrue(
                coords.none { it.contains("platform") || it.contains("bom") },
                "BOM entries should be ignored"
            )
            assertTrue(
                coords.any { it.contains("spring-core") },
                "Regular dependency should be included"
            )
        }

        test("parseGradleDependenciesStatically excludes runtimeOnly dependencies") {
            projectDir.resolve("build.gradle.kts").writeText(
                """
                dependencies {
                    implementation("org.apache.commons:commons-lang3:3.12.0")
                    runtimeOnly("org.postgresql:postgresql:42.7.0")
                }
                """.trimIndent()
            )

            val coords = staticParser().parseGradleDependenciesStatically(projectDir)

            assertTrue(
                coords.contains("org.apache.commons:commons-lang3:3.12.0"),
                "compile-time dep must be included"
            )
            assertFalse(
                coords.contains("org.postgresql:postgresql:42.7.0"),
                "runtimeOnly dep must not appear on classpath"
            )
        }

        // ─── Gradle dependencies task output parsing ──────────────────────────────

        test("parseGradleDependencyTaskOutput extracts simple coordinates") {
            val output =
                """
                compileClasspath - Compile classpath for source set 'main'.
                +--- org.apache.commons:commons-lang3:3.12.0
                \--- com.google.guava:guava:32.1.2-jre
                """.trimIndent()

            val coords = stage().parseGradleDependencyTaskOutput(output)

            assertTrue(coords.contains("org.apache.commons:commons-lang3:3.12.0"))
            assertTrue(coords.contains("com.google.guava:guava:32.1.2-jre"))
        }

        test("parseGradleDependencyTaskOutput uses resolved version when overridden") {
            val output =
                """
                compileClasspath - Compile classpath for source set 'main'.
                +--- com.google.guava:guava:30.0-jre -> 32.1.2-jre
                \--- org.springframework:spring-core:5.3.0 -> 6.1.0
                """.trimIndent()

            val coords = stage().parseGradleDependencyTaskOutput(output)

            assertTrue(
                coords.contains("com.google.guava:guava:32.1.2-jre"),
                "Should use resolved version after ->"
            )
            assertTrue(
                coords.contains("org.springframework:spring-core:6.1.0"),
                "Should use resolved version after ->"
            )
            assertFalse(
                coords.any { it.contains("30.0") || it.contains("5.3.0") },
                "Should not contain declared version"
            )
        }

        test("parseGradleDependencyTaskOutput handles nested transitive dependencies") {
            val output =
                """
                compileClasspath - Compile classpath for source set 'main'.
                +--- org.springframework.boot:spring-boot-starter-web:3.2.0
                |    +--- org.springframework.boot:spring-boot-starter:3.2.0
                |    |    \--- org.springframework.boot:spring-boot:3.2.0
                |    \--- org.springframework:spring-webmvc:6.1.0
                \--- com.google.guava:guava:32.1.2-jre
                """.trimIndent()

            val coords = stage().parseGradleDependencyTaskOutput(output)

            assertTrue(
                coords.contains("org.springframework.boot:spring-boot-starter-web:3.2.0")
            )
            assertTrue(coords.contains("org.springframework.boot:spring-boot-starter:3.2.0"))
            assertTrue(coords.contains("org.springframework.boot:spring-boot:3.2.0"))
            assertTrue(coords.contains("org.springframework:spring-webmvc:6.1.0"))
            assertTrue(coords.contains("com.google.guava:guava:32.1.2-jre"))
        }

        test("parseGradleDependencyTaskOutput deduplicates across configurations") {
            val output =
                """
                compileClasspath - Compile classpath for source set 'main'.
                \--- com.google.guava:guava:32.1.2-jre

                runtimeClasspath - Runtime classpath of source set 'main'.
                \--- com.google.guava:guava:32.1.2-jre
                """.trimIndent()

            val coords = stage().parseGradleDependencyTaskOutput(output)

            assertEquals(1, coords.size, "Duplicate coordinates should be deduplicated")
            assertTrue(coords.contains("com.google.guava:guava:32.1.2-jre"))
        }

        test("parseGradleDependencyTaskOutput skips FAILED resolutions") {
            val output =
                """
                compileClasspath - Compile classpath for source set 'main'.
                +--- com.example:missing-artifact:1.0 FAILED
                \--- com.google.guava:guava:32.1.2-jre
                """.trimIndent()

            val coords = stage().parseGradleDependencyTaskOutput(output)

            assertFalse(
                coords.any { it.contains("FAILED") },
                "FAILED entries should be excluded"
            )
            assertTrue(coords.contains("com.google.guava:guava:32.1.2-jre"))
        }

        test("parseGradleDependencyTaskOutput returns empty list for empty output") {
            val coords = stage().parseGradleDependencyTaskOutput("")
            assertEquals(0, coords.size)
        }

        test("parseGradleDependencyTaskOutput aggregates dependencies from multiple projects") {
            // Simulates output from running both root and subproject dependencies tasks
            val output =
                """
                > Task :dependencies

                compileClasspath - Compile classpath for source set 'main'.
                \--- org.apache.commons:commons-lang3:3.12.0

                > Task :api:dependencies

                compileClasspath - Compile classpath for source set 'main'.
                \--- com.google.guava:guava:32.1.2-jre
                """.trimIndent()

            val coords = stage().parseGradleDependencyTaskOutput(output)

            assertTrue(coords.contains("org.apache.commons:commons-lang3:3.12.0"))
            assertTrue(coords.contains("com.google.guava:guava:32.1.2-jre"))
        }

        test(
            "parseGradleDependencyTaskOutput includes dependencies from testCompileClasspath configuration"
        ) {
            val output =
                """
                testCompileClasspath - Compile classpath for source set 'test'.
                \--- junit:junit:4.13.2
                """.trimIndent()

            val coords = stage().parseGradleDependencyTaskOutput(output)

            assertTrue(
                coords.contains("junit:junit:4.13.2"),
                "test compile classpath dep must be included"
            )
        }

        test(
            "parseGradleDependencyTaskOutput excludes dependencies from runtimeClasspath configuration"
        ) {
            val output =
                """
                compileClasspath - Compile classpath for source set 'main'.
                \--- org.apache.commons:commons-lang3:3.12.0

                runtimeClasspath - Runtime classpath for source set 'main'.
                \--- org.postgresql:postgresql:42.7.0
                """.trimIndent()

            val coords = stage().parseGradleDependencyTaskOutput(output)

            assertTrue(coords.contains("org.apache.commons:commons-lang3:3.12.0"))
            assertFalse(
                coords.contains("org.postgresql:postgresql:42.7.0"),
                "runtime-only dependency must not appear on compile classpath"
            )
        }

        test(
            "parseGradleDependencyTaskOutput excludes dependencies from testRuntimeClasspath configuration"
        ) {
            val output =
                """
                testCompileClasspath - Compile classpath for source set 'test'.
                \--- junit:junit:4.13.2

                testRuntimeClasspath - Runtime classpath for source set 'test'.
                \--- ch.qos.logback:logback-classic:1.4.0
                """.trimIndent()

            val coords = stage().parseGradleDependencyTaskOutput(output)

            assertTrue(coords.contains("junit:junit:4.13.2"))
            assertFalse(
                coords.contains("ch.qos.logback:logback-classic:1.4.0"),
                "test runtime-only dependency must not appear on classpath"
            )
        }

        // ─── Maven dependency:tree output parsing ─────────────────────────────────

        test("parseMavenDependencyTreeOutput includes compile-scoped dependencies") {
            val output = "[INFO] +- org.apache.commons:commons-lang3:jar:3.12.0:compile"
            val coords = stage().parseMavenDependencyTreeOutput(output)
            assertTrue(coords.contains("org.apache.commons:commons-lang3:3.12.0"))
        }

        test("parseMavenDependencyTreeOutput includes provided-scoped dependencies") {
            val output = "[INFO] +- javax.servlet:javax.servlet-api:jar:4.0.1:provided"
            val coords = stage().parseMavenDependencyTreeOutput(output)
            assertTrue(
                coords.contains("javax.servlet:javax.servlet-api:4.0.1"),
                "provided-scoped dep must be included for compile-time type resolution"
            )
        }

        test("parseMavenDependencyTreeOutput includes test-scoped dependencies") {
            val output = "[INFO] +- junit:junit:jar:4.13.2:test"
            val coords = stage().parseMavenDependencyTreeOutput(output)
            assertTrue(
                coords.contains("junit:junit:4.13.2"),
                "test-scoped dep must be included for test source type resolution"
            )
        }

        test("parseMavenDependencyTreeOutput excludes runtime-scoped dependencies") {
            val output = "[INFO] +- org.postgresql:postgresql:jar:42.7.0:runtime"
            val coords = stage().parseMavenDependencyTreeOutput(output)
            assertFalse(
                coords.contains("org.postgresql:postgresql:42.7.0"),
                "runtime-scoped dependency must not appear on compile classpath"
            )
        }

        test("parseMavenDependencyTreeOutput excludes system-scoped dependencies") {
            val output = "[INFO] +- com.sun:tools:jar:1.8:system"
            val coords = stage().parseMavenDependencyTreeOutput(output)
            assertFalse(
                coords.contains("com.sun:tools:1.8"),
                "system-scoped dependency must not appear on compile classpath"
            )
        }

        // ─── Subproject discovery ─────────────────────────────────────────────────

        test("discoverSubprojects finds subprojects from Kotlin DSL settings") {
            projectDir.resolve("settings.gradle.kts").writeText(
                """
                rootProject.name = "my-app"
                include(":api")
                include(":core", ":web")
                """.trimIndent()
            )

            val subs = staticParser().discoverSubprojects(projectDir)

            assertTrue(subs.contains(":api"))
            assertTrue(subs.contains(":core"))
            assertTrue(subs.contains(":web"))
        }

        test("discoverSubprojects normalizes Kotlin DSL include without leading colon") {
            projectDir.resolve("settings.gradle.kts").writeText(
                """
                rootProject.name = "my-app"
                include("api")
                include("core", "web")
                """.trimIndent()
            )

            val subs = staticParser().discoverSubprojects(projectDir)

            assertEquals(listOf(":api", ":core", ":web"), subs)
        }

        test("discoverSubprojects normalizes nested Kotlin DSL include path") {
            projectDir.resolve("settings.gradle.kts").writeText(
                """
                include("services:api")
                """.trimIndent()
            )

            val subs = staticParser().discoverSubprojects(projectDir)

            assertEquals(listOf(":services:api"), subs)
        }

        test("discoverSubprojects finds subprojects from Groovy DSL settings") {
            projectDir.resolve("settings.gradle").writeText(
                """
                rootProject.name = 'my-app'
                include ':service'
                include ':common'
                """.trimIndent()
            )

            val subs = staticParser().discoverSubprojects(projectDir)

            assertTrue(subs.contains(":service"))
            assertTrue(subs.contains(":common"))
        }

        test("discoverSubprojects prefers settings_gradle_kts over settings_gradle") {
            projectDir.resolve("settings.gradle.kts").writeText(
                """
                include(":from-kts")
                """.trimIndent()
            )
            projectDir.resolve("settings.gradle").writeText(
                """
                include ':from-groovy'
                """.trimIndent()
            )

            val subs = staticParser().discoverSubprojects(projectDir)

            assertTrue(subs.contains(":from-kts"), "KTS settings should take precedence")
            assertFalse(subs.contains(":from-groovy"), "Groovy settings should not be read")
        }

        test("discoverSubprojects returns empty list when no settings file") {
            val subs = staticParser().discoverSubprojects(projectDir)
            assertEquals(0, subs.size)
        }

        // ─── Version catalog parsing ──────────────────────────────────────────────

        test("parseCatalogLibraryEntry resolves module + version.ref") {
            val versions = mapOf("guava" to "32.1.2-jre")
            val line = """guava = { module = "com.google.guava:guava", version.ref = "guava" }"""
            assertEquals(
                "com.google.guava:guava:32.1.2-jre",
                staticParser().parseCatalogLibraryEntry(line, versions)
            )
        }

        test("parseCatalogLibraryEntry resolves module + inline version") {
            val line = """
                logback = { module = "ch.qos.logback:logback-classic", version = "1.4.11" }
            """.trimIndent()
            assertEquals(
                "ch.qos.logback:logback-classic:1.4.11",
                staticParser().parseCatalogLibraryEntry(line, emptyMap())
            )
        }

        test("parseCatalogLibraryEntry resolves group+name + version.ref") {
            val versions = mapOf("spring" to "6.1.0")
            val line = """
                spring-core = { group = "org.springframework", name = "spring-core", version.ref = "spring" }
            """.trimIndent()
            assertEquals(
                "org.springframework:spring-core:6.1.0",
                staticParser().parseCatalogLibraryEntry(line, versions)
            )
        }

        test("parseCatalogLibraryEntry resolves group+name + inline version") {
            val line = """
                spring-web = { group = "org.springframework", name = "spring-web", version = "6.1.0" }
            """.trimIndent()
            assertEquals(
                "org.springframework:spring-web:6.1.0",
                staticParser().parseCatalogLibraryEntry(line, emptyMap())
            )
        }

        test("parseCatalogLibraryEntry resolves string literal form") {
            val line = """utils = "com.example:utils:1.0.0""""
            assertEquals(
                "com.example:utils:1.0.0",
                staticParser().parseCatalogLibraryEntry(line, emptyMap())
            )
        }

        test("parseCatalogLibraryEntry returns null when version.ref has no mapping") {
            val line = """lib = { module = "com.example:lib", version.ref = "missing" }"""
            assertFalse(
                staticParser().parseCatalogLibraryEntry(line, emptyMap()) != null,
                "Unknown version.ref should yield null"
            )
        }

        test("parseCatalogLibraryEntry returns null when no version present") {
            val line = """lib = { module = "com.example:lib" }"""
            assertEquals(null, staticParser().parseCatalogLibraryEntry(line, emptyMap()))
        }

        test("parseVersionCatalogs reads libs.versions.toml and resolves all library forms") {
            val gradleDir = projectDir.resolve("gradle").toFile().also { it.mkdirs() }
            gradleDir.resolve("libs.versions.toml").writeText(
                """
                [versions]
                guava = "32.1.2-jre"
                spring = "6.1.0"

                [libraries]
                # module + version.ref
                guava = { module = "com.google.guava:guava", version.ref = "guava" }
                # module + inline version
                logback = { module = "ch.qos.logback:logback-classic", version = "1.4.11" }
                # group + name + version.ref
                spring-core = { group = "org.springframework", name = "spring-core", version.ref = "spring" }
                # string literal
                commons = "org.apache.commons:commons-lang3:3.12.0"

                [bundles]
                web = ["guava", "spring-core"]
                """.trimIndent()
            )

            val coords = staticParser().parseVersionCatalogs(projectDir)

            assertTrue(coords.contains("com.google.guava:guava:32.1.2-jre"))
            assertTrue(coords.contains("ch.qos.logback:logback-classic:1.4.11"))
            assertTrue(coords.contains("org.springframework:spring-core:6.1.0"))
            assertTrue(coords.contains("org.apache.commons:commons-lang3:3.12.0"))
        }

        test("parseVersionCatalogs returns empty when no gradle/ directory") {
            assertEquals(emptyList(), staticParser().parseVersionCatalogs(projectDir))
        }

        test("parseVersionCatalogs ignores comments and blank lines") {
            val gradleDir = projectDir.resolve("gradle").toFile().also { it.mkdirs() }
            gradleDir.resolve("libs.versions.toml").writeText(
                """
                # top comment
                [versions]
                # version comment
                guava = "32.1.2-jre"

                [libraries]
                # library comment
                guava = { module = "com.google.guava:guava", version.ref = "guava" }
                """.trimIndent()
            )

            val coords = staticParser().parseVersionCatalogs(projectDir)
            assertEquals(listOf("com.google.guava:guava:32.1.2-jre"), coords)
        }

        test("parseGradleDependenciesStatically includes version catalog coordinates") {
            projectDir.resolve("build.gradle.kts").writeText(
                """
                dependencies {
                    implementation(libs.guava)
                    implementation("org.apache.commons:commons-lang3:3.12.0")
                }
                """.trimIndent()
            )
            val gradleDir = projectDir.resolve("gradle").toFile().also { it.mkdirs() }
            gradleDir.resolve("libs.versions.toml").writeText(
                """
                [versions]
                guava = "32.1.2-jre"

                [libraries]
                guava = { module = "com.google.guava:guava", version.ref = "guava" }
                """.trimIndent()
            )

            val coords = staticParser().parseGradleDependenciesStatically(projectDir)

            assertTrue(
                coords.contains("com.google.guava:guava:32.1.2-jre"),
                "Catalog-resolved dependency should be present"
            )
            assertTrue(
                coords.contains("org.apache.commons:commons-lang3:3.12.0"),
                "Literal dependency should still be present"
            )
        }

        // ─── Maven Resolver session configuration ─────────────────────────────────

        test(
            "resolveClasspath resolves artifact from local repository without 'No local repository manager' error"
        ) {
            // Regression test: newSession() previously closed the bootstrap session via .use{}
            // which could invalidate the LocalRepositoryManager, resulting in
            // "No local repository manager or local repositories set on session" for every
            // artifact — so Stage 2 always produced an empty classpath.
            //
            // We seed a minimal JAR into cacheDir/repository at the expected Maven path,
            // declare it as a dependency in pom.xml, and verify resolveClasspath finds it.

            val group = "com.example.test"
            val artifact = "fake-lib"
            val version = "1.0"
            val groupPath = group.replace('.', '/')
            val artifactDir = cacheDir.resolve("repository/$groupPath/$artifact/$version").toFile()
            artifactDir.mkdirs()
            // Create a minimal valid JAR (ZIP with MANIFEST.MF)
            val jarFile = java.io.File(artifactDir, "$artifact-$version.jar")
            java.util.zip.ZipOutputStream(jarFile.outputStream()).use { zip ->
                zip.putNextEntry(java.util.zip.ZipEntry("META-INF/MANIFEST.MF"))
                zip.write("Manifest-Version: 1.0\n".toByteArray())
                zip.closeEntry()
            }
            // Also seed a minimal POM so Maven Resolver can find the artifact metadata
            val pomFile = java.io.File(artifactDir, "$artifact-$version.pom")
            pomFile.writeText(
                """<project>
                       <modelVersion>4.0.0</modelVersion>
                       <groupId>$group</groupId>
                       <artifactId>$artifact</artifactId>
                       <version>$version</version>
                   </project>"""
            )

            projectDir.resolve("pom.xml").writeText(
                """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>my-app</artifactId>
                    <version>1.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>$group</groupId>
                            <artifactId>$artifact</artifactId>
                            <version>$version</version>
                        </dependency>
                    </dependencies>
                </project>
                """.trimIndent()
            )

            val resolved = stage().resolveClasspath(projectDir)
            assertTrue(
                resolved.classpath.any { it.fileName.toString() == "$artifact-$version.jar" },
                "Should resolve the local artifact; resolved: ${resolved.classpath}"
            )
        }

        // ─── Conflict resolution (single-pass batch resolution) ──────────────────

        test(
            "resolveClasspath resolves only declared direct deps — transitive shared-util is absent"
        ) {
            // resolveClasspath uses resolveArtifactsDirectly for all coordinate sources,
            // so only the explicitly declared dep JARs are downloaded (no POM traversal).
            // Transitive dependencies (like shared-util) are intentionally omitted to
            // avoid the overhead of POM downloads; OpenRewrite handles JavaType.Unknown for
            // missing transitives. Conflict resolution for transitives is therefore not
            // applicable in this path.
            val group = "conflict.test"
            val groupPath = group.replace('.', '/')
            val fakeRemote = cacheDir.resolve("fake-remote")

            fun publishArtifact(
                artifactId: String,
                version: String,
                deps: List<Pair<String, String>> = emptyList()
            ) {
                val dir = fakeRemote.resolve("$groupPath/$artifactId/$version")
                Files.createDirectories(dir)
                val depsXml =
                    deps.joinToString("\n") { (a, v) ->
                        "<dependency><groupId>$group</groupId>" +
                            "<artifactId>$a</artifactId><version>$v</version></dependency>"
                    }
                dir
                    .resolve("$artifactId-$version.pom")
                    .toFile()
                    .writeText(
                        """<?xml version="1.0"?>
<project>
  <modelVersion>4.0.0</modelVersion>
  <groupId>$group</groupId>
  <artifactId>$artifactId</artifactId>
  <version>$version</version>
  ${if (depsXml.isNotEmpty()) "<dependencies>$depsXml</dependencies>" else ""}
</project>"""
                    )
                JarOutputStream(
                    dir.resolve("$artifactId-$version.jar").toFile().outputStream()
                ).close()
            }

            publishArtifact("shared-util", "1.0")
            publishArtifact("shared-util", "2.0")
            publishArtifact("dep-alpha", "1.0", listOf("shared-util" to "1.0"))
            publishArtifact("dep-beta", "1.0", listOf("shared-util" to "2.0"))

            projectDir.resolve("pom.xml").writeText(
                """<project>
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.example</groupId>
  <artifactId>my-app</artifactId>
  <version>1.0</version>
  <dependencies>
    <dependency><groupId>$group</groupId><artifactId>dep-alpha</artifactId><version>1.0</version></dependency>
    <dependency><groupId>$group</groupId><artifactId>dep-beta</artifactId><version>1.0</version></dependency>
  </dependencies>
</project>"""
            )

            val system = RepositorySystemSupplier().get()
            val localCache = cacheDir.resolve("local-cache")
            Files.createDirectories(localCache)
            val session =
                system
                    .createSessionBuilder()
                    .withLocalRepositories(LocalRepository(localCache))
                    .setSystemProperties(System.getProperties())
                    .setDependencyGraphTransformer(
                        @Suppress("DEPRECATION")
                        ClassicConflictResolver(
                            NearestVersionSelector(),
                            JavaScopeSelector(),
                            SimpleOptionalitySelector(),
                            JavaScopeDeriver()
                        )
                    )
                    .setConfigProperty(ConfigurationProperties.CONNECT_TIMEOUT, 5_000)
                    .setConfigProperty(ConfigurationProperties.REQUEST_TIMEOUT, 5_000)
                    .setConfigProperty(
                        "aether.remoteRepositoryFilter.prefixes.resolvePrefixFiles",
                        false
                    )
                    .setIgnoreArtifactDescriptorRepositories(true)
                    .build()
            val fakeRemoteRepo =
                listOf(
                    RemoteRepository.Builder(
                        "fake-central",
                        "default",
                        fakeRemote.toUri().toString()
                    )
                        .build()
                )
            val ctx = AetherContext(system, session, fakeRemoteRepo)

            val resolved = DependencyResolutionStage(
                ctx,
                NoOpRunnerLogger
            ).resolveClasspath(projectDir)

            // Only the two direct deps should be resolved — no transitive shared-util
            val depAlphaJars = resolved.classpath.filter {
                it.fileName.toString().startsWith("dep-alpha")
            }
            val depBetaJars = resolved.classpath.filter {
                it.fileName.toString().startsWith("dep-beta")
            }
            val sharedUtilJars = resolved.classpath.filter {
                it.fileName.toString().startsWith("shared-util")
            }
            assertEquals(1, depAlphaJars.size, "dep-alpha should be resolved as a direct dep")
            assertEquals(1, depBetaJars.size, "dep-beta should be resolved as a direct dep")
            assertEquals(
                0,
                sharedUtilJars.size,
                "shared-util is a transitive dep and should NOT be resolved (direct-only mode)"
            )
        }

        test("discoverSubprojects deduplicates repeated entries") {
            projectDir.resolve("settings.gradle.kts").writeText(
                """
                include(":api")
                include(":api")
                include(":core")
                """.trimIndent()
            )

            val subs = staticParser().discoverSubprojects(projectDir)
            assertEquals(subs.distinct(), subs, "Subprojects should be deduplicated")
            assertEquals(2, subs.size)
        }

        // ─── parseGradleDependencyTaskOutputByProject ────────────────────────────

        test("parseGradleDependencyTaskOutputByProject splits single-project output") {
            val output =
                """
                > Task :dependencies

                compileClasspath - Compile classpath for source set 'main'.
                +--- org.apache.commons:commons-lang3:3.12.0
                \--- com.google.guava:guava:32.1.2-jre

                runtimeClasspath - Runtime classpath.
                \--- org.apache.commons:commons-lang3:3.12.0
                """.trimIndent()

            val result = stage().parseGradleDependencyTaskOutputByProject(output)

            assertEquals(1, result.size, "Should have one project entry for root")
            assertTrue(result.containsKey(":"), "Root project should be keyed as ':'")
            val rootData = result[":"]!!
            assertTrue(
                rootData.configurationsByName.containsKey("compileClasspath"),
                "Should have compileClasspath configuration"
            )
            assertTrue(
                rootData.configurationsByName.containsKey("runtimeClasspath"),
                "Should have runtimeClasspath configuration"
            )
        }

        test("parseGradleDependencyTaskOutputByProject splits multi-project output") {
            val output =
                """
                > Task :dependencies

                compileClasspath - Compile classpath for source set 'main'.
                \--- org.apache.commons:commons-lang3:3.12.0

                > Task :api:dependencies

                compileClasspath - Compile classpath for source set 'main'.
                \--- com.google.guava:guava:32.1.2-jre

                > Task :core:util:dependencies

                compileClasspath - Compile classpath for source set 'main'.
                \--- org.slf4j:slf4j-api:2.0.0
                """.trimIndent()

            val result = stage().parseGradleDependencyTaskOutputByProject(output)

            assertEquals(3, result.size, "Should have three project entries")
            assertTrue(result.containsKey(":"), "Root should be keyed as ':'")
            assertTrue(result.containsKey(":api"), "Subproject should be keyed as ':api'")
            assertTrue(
                result.containsKey(":core:util"),
                "Nested subproject should be keyed as ':core:util'"
            )

            val rootConfig = result[":"]!!.configurationsByName["compileClasspath"]
            assertFalse(rootConfig == null, "Root should have compileClasspath")
            assertTrue(
                rootConfig.requested.contains("org.apache.commons:commons-lang3:3.12.0"),
                "Root requested should contain commons-lang3"
            )

            val apiConfig = result[":api"]!!.configurationsByName["compileClasspath"]
            assertFalse(apiConfig == null, ":api should have compileClasspath")
            assertTrue(
                apiConfig.requested.contains("com.google.guava:guava:32.1.2-jre"),
                ":api requested should contain guava"
            )
        }

        test("parseGradleDependencyTaskOutputByProject captures resolved versions") {
            val output =
                """
                > Task :dependencies

                compileClasspath - Compile classpath for source set 'main'.
                +--- com.google.guava:guava:30.0-jre -> 32.1.2-jre
                \--- org.springframework:spring-core:5.3.0 -> 6.1.0
                """.trimIndent()

            val result = stage().parseGradleDependencyTaskOutputByProject(output)
            val config = result[":"]!!.configurationsByName["compileClasspath"]!!

            assertTrue(
                config.requested.contains("com.google.guava:guava:30.0-jre"),
                "Requested should use declared version"
            )
            assertTrue(
                config.resolved.contains("com.google.guava:guava:32.1.2-jre"),
                "Resolved should use final version after ->"
            )
            assertTrue(
                config.requested.contains("org.springframework:spring-core:5.3.0"),
                "Requested should use declared version for spring"
            )
            assertTrue(
                config.resolved.contains("org.springframework:spring-core:6.1.0"),
                "Resolved should use final version after -> for spring"
            )
        }

        test(
            "parseGradleDependencyTaskOutputByProject returns root for output without task headers"
        ) {
            val output =
                """
                compileClasspath - Compile classpath for source set 'main'.
                \--- com.google.guava:guava:32.1.2-jre
                """.trimIndent()

            val result = stage().parseGradleDependencyTaskOutputByProject(output)

            assertEquals(1, result.size, "Should produce one entry even without task headers")
            assertTrue(result.containsKey(":"), "Should be keyed as root ':'")
        }

        // ─── resolveClasspath returns ClasspathResolutionResult ──────────────────

        test("resolveClasspath returns ClasspathResolutionResult for Maven project") {
            val group = "com.example.test"
            val artifact = "fake-maven-lib"
            val version = "1.0"
            val groupPath = group.replace('.', '/')
            val artifactDir = cacheDir.resolve("repository/$groupPath/$artifact/$version").toFile()
            artifactDir.mkdirs()
            val jarFile = java.io.File(artifactDir, "$artifact-$version.jar")
            java.util.zip.ZipOutputStream(jarFile.outputStream()).use { zip ->
                zip.putNextEntry(java.util.zip.ZipEntry("META-INF/MANIFEST.MF"))
                zip.write("Manifest-Version: 1.0\n".toByteArray())
                zip.closeEntry()
            }
            val pomFile = java.io.File(artifactDir, "$artifact-$version.pom")
            pomFile.writeText(
                """<project><modelVersion>4.0.0</modelVersion>
                   <groupId>$group</groupId><artifactId>$artifact</artifactId><version>$version</version></project>"""
            )

            projectDir.resolve("pom.xml").writeText(
                """
                <project><modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId><artifactId>my-app</artifactId><version>1.0</version>
                  <dependencies>
                    <dependency><groupId>$group</groupId><artifactId>$artifact</artifactId><version>$version</version></dependency>
                  </dependencies>
                </project>
                """.trimIndent()
            )

            val result = stage().resolveClasspath(projectDir)
            assertTrue(
                result.classpath.any { it.fileName.toString() == "$artifact-$version.jar" },
                "Classpath should contain the resolved JAR"
            )
            assertEquals(
                null,
                result.gradleProjectData,
                "Maven project should have null gradleProjectData"
            )
        }
    })
