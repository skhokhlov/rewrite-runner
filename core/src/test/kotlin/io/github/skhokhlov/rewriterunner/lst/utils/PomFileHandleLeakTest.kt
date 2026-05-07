package io.github.skhokhlov.rewriterunner.lst.utils

import io.github.skhokhlov.rewriterunner.NoOpRunnerLogger
import io.kotest.core.spec.style.FunSpec
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertTrue

/**
 * Regression tests guarding against file-descriptor leaks when reading `pom.xml`
 * via `MavenXpp3Reader`. The reader does not close streams it is given, so each
 * call site must wrap the input stream in `.use { }`.
 *
 * Strategy: count entries under `/proc/self/fd` (Linux) or `/dev/fd` (macOS)
 * before and after a tight loop. If a call site leaks one FD per invocation,
 * the count grows by [LOOP_COUNT]; with the fix it stays within a small constant.
 */
class PomFileHandleLeakTest :
    FunSpec({
        // Per-process FD directory. Null on platforms without one (e.g. Windows);
        // tests below short-circuit when null.
        val fdDir =
            listOf(Path.of("/proc/self/fd"), Path.of("/dev/fd"))
                .firstOrNull { Files.exists(it) }

        val pomXml =
            """
            <project>
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>leak-probe</artifactId>
                <version>1.0</version>
                <build>
                    <plugins>
                        <plugin>
                            <artifactId>maven-compiler-plugin</artifactId>
                            <configuration>
                                <release>17</release>
                            </configuration>
                        </plugin>
                        <plugin>
                            <artifactId>kotlin-maven-plugin</artifactId>
                            <configuration>
                                <jvmTarget>17</jvmTarget>
                            </configuration>
                        </plugin>
                    </plugins>
                </build>
            </project>
            """.trimIndent()

        fun openFdCount(): Int = Files.list(fdDir!!).use { it.count().toInt() }

        var projectDir: Path = Path.of("")
        beforeEach {
            projectDir = Files.createTempDirectory("pomleak-")
            projectDir.resolve("pom.xml").writeText(pomXml)
        }
        afterEach { projectDir.toFile().deleteRecursively() }

        // Allow some slack: GC, JIT, classloader caches, etc. may open a few FDs.
        val tolerance = 25
        val loopCount = 200

        test("StaticBuildFileParser.parseMavenDependencies does not leak file handles") {
            if (fdDir == null) return@test
            val parser = StaticBuildFileParser(NoOpRunnerLogger)
            // Warm up so any one-shot caches are populated before measuring.
            repeat(5) { parser.parseMavenDependencies(projectDir) }

            val before = openFdCount()
            repeat(loopCount) { parser.parseMavenDependencies(projectDir) }
            val after = openFdCount()

            assertTrue(
                after - before < tolerance,
                "Expected FD count to stay bounded; before=$before after=$after"
            )
        }

        test("VersionDetector reads pom.xml without leaking file handles") {
            if (fdDir == null) return@test
            val detector = VersionDetector(NoOpRunnerLogger)
            val sourceFile = projectDir.resolve("src/main/java/Demo.java")
            Files.createDirectories(sourceFile.parent)
            sourceFile.writeText("class Demo {}")

            // Warm up.
            repeat(5) {
                detector.detectJavaVersionForFile(sourceFile, projectDir, mutableMapOf())
                detector.detectKotlinVersionForFile(sourceFile, projectDir, mutableMapOf())
            }

            val before = openFdCount()
            repeat(loopCount) {
                // Fresh cache each time so the pom.xml is actually re-read.
                detector.detectJavaVersionForFile(sourceFile, projectDir, mutableMapOf())
                detector.detectKotlinVersionForFile(sourceFile, projectDir, mutableMapOf())
            }
            val after = openFdCount()

            assertTrue(
                after - before < tolerance,
                "Expected FD count to stay bounded; before=$before after=$after"
            )
        }
    })
