@file:Suppress("DEPRECATION")

package io.github.skhokhlov.rewriterunner.lst

import io.github.skhokhlov.rewriterunner.AetherContext
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

        fun stage() = DependencyResolutionStage(AetherContext.build(cacheDir.resolve("repository")))

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

            val coords = stage().parseMavenDependencies(projectDir)

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

            val coords = stage().parseMavenDependencies(projectDir)

            assertEquals(2, coords.size, "Test dependency should not be excluded")
            assertEquals("org.apache.commons:commons-lang3:3.12.0", coords.first())
        }

        test("parseMavenDependencies excludes provided-scoped dependencies") {
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

            val coords = stage().parseMavenDependencies(projectDir)
            assertEquals(1, coords.size, "Dependency with property version should be skipped")
            assertEquals("com.google.guava:guava:32.1.2-jre", coords.first())
        }

        test("parseMavenDependencies returns empty list when pom_xml absent") {
            val coords = stage().parseMavenDependencies(projectDir)
            assertEquals(0, coords.size)
        }

        test("parseMavenDependencies returns empty list for malformed pom_xml") {
            projectDir.resolve("pom.xml").writeText("this is not xml")
            val coords = stage().parseMavenDependencies(projectDir)
            assertEquals(
                0,
                coords.size,
                "Malformed pom.xml should not throw, should return empty list"
            )
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

            val coords = stage().parseGradleDependenciesStatically(projectDir)

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

            val coords = stage().parseGradleDependenciesStatically(projectDir)

            assertTrue(coords.contains("org.springframework:spring-core:6.1.0"))
            assertTrue(coords.contains("ch.qos.logback:logback-classic:1.4.11"))
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

            val coords = stage().parseGradleDependenciesStatically(projectDir)

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
            val coords = stage().parseGradleDependenciesStatically(projectDir)
            assertEquals(0, coords.size)
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

            val coords = stage().parseGradleDependenciesStatically(projectDir)
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

            val coords = stage().parseGradleDependenciesStatically(projectDir)
            assertTrue(
                coords.none { it.contains("platform") || it.contains("bom") },
                "BOM entries should be ignored"
            )
            assertTrue(
                coords.any { it.contains("spring-core") },
                "Regular dependency should be included"
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
                runtimeClasspath - Runtime classpath of source set 'main'.
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

        // ─── Subproject discovery ─────────────────────────────────────────────────

        test("discoverSubprojects finds subprojects from Kotlin DSL settings") {
            projectDir.resolve("settings.gradle.kts").writeText(
                """
                rootProject.name = "my-app"
                include(":api")
                include(":core", ":web")
                """.trimIndent()
            )

            val subs = stage().discoverSubprojects(projectDir)

            assertTrue(subs.contains(":api"))
            assertTrue(subs.contains(":core"))
            assertTrue(subs.contains(":web"))
        }

        test("discoverSubprojects finds subprojects from Groovy DSL settings") {
            projectDir.resolve("settings.gradle").writeText(
                """
                rootProject.name = 'my-app'
                include ':service'
                include ':common'
                """.trimIndent()
            )

            val subs = stage().discoverSubprojects(projectDir)

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

            val subs = stage().discoverSubprojects(projectDir)

            assertTrue(subs.contains(":from-kts"), "KTS settings should take precedence")
            assertFalse(subs.contains(":from-groovy"), "Groovy settings should not be read")
        }

        test("discoverSubprojects returns empty list when no settings file") {
            val subs = stage().discoverSubprojects(projectDir)
            assertEquals(0, subs.size)
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
                resolved.any { it.fileName.toString() == "$artifact-$version.jar" },
                "Should resolve the local artifact; resolved: $resolved"
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

            val resolved = DependencyResolutionStage(ctx).resolveClasspath(projectDir)

            // Only the two direct deps should be resolved — no transitive shared-util
            val depAlphaJars = resolved.filter { it.fileName.toString().startsWith("dep-alpha") }
            val depBetaJars = resolved.filter { it.fileName.toString().startsWith("dep-beta") }
            val sharedUtilJars = resolved.filter {
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

            val subs = stage().discoverSubprojects(projectDir)
            assertEquals(subs.distinct(), subs, "Subprojects should be deduplicated")
            assertEquals(2, subs.size)
        }
    })
