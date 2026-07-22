package io.github.skhokhlov.rewriterunner.integration

import io.kotest.core.spec.style.FunSpec
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Runs the release-shaped CLI inside a real Docker cgroup. This lane is deliberately separate
 * from the offline integration suite: Docker and the Java 21 runtime image are prerequisites and
 * an unavailable prerequisite is a failure, not a skip.
 */
class ContainerForkedDistributionIntegrationTest :
    FunSpec({
        var root: Path = Path.of("")

        beforeEach { root = Files.createTempDirectory("forked-container-") }
        afterEach { root.toFile().deleteRecursively() }

        test("2 GiB cgroup drives automatic worker heap sizing and respects an explicit override") {
            val fatJarProperty = requireNotNull(System.getProperty("rewriterunner.test.fatJar")) {
                "testContainer must provide the built fat JAR"
            }
            val fatJar = Path.of(fatJarProperty)
            assertTrue(Files.isRegularFile(fatJar), "Missing fat JAR at $fatJar")

            val automatic = runContainer(fatJar, root.resolve("automatic"))
            assertEquals(0, automatic.exitCode, automatic.output)
            assertEquals("new\n", automatic.projectDir.resolve("sample.txt").readText())
            val automaticWorker = assertNotNull(automatic.worker, automatic.output)
            assertEquals("AUTOMATIC", automaticWorker.stringField("jvmConfigurationSource"))
            val expectedAutomaticHeap = 1433L * MIB
            assertTrue(
                automaticWorker.numberField("observedMaximumHeapBytes") in
                    (expectedAutomaticHeap - 4 * MIB)..(expectedAutomaticHeap + 4 * MIB),
                "Expected a roughly 1.4 GiB worker heap under --memory=2g: $automaticWorker"
            )

            val explicit = runContainer(
                fatJar,
                root.resolve("explicit"),
                extraArgs = listOf("--executor-jvm-arg=-Xmx768m")
            )
            assertEquals(0, explicit.exitCode, explicit.output)
            assertEquals("new\n", explicit.projectDir.resolve("sample.txt").readText())
            val explicitWorker = assertNotNull(explicit.worker, explicit.output)
            assertEquals("RUNNER", explicitWorker.stringField("jvmConfigurationSource"))
            assertEquals(768L * MIB, explicitWorker.numberField("observedMaximumHeapBytes"))
        }
    })

private const val MIB = 1024L * 1024L

private data class ContainerRun(
    val projectDir: Path,
    val exitCode: Int,
    val output: String,
    val worker: String?
)

private fun runContainer(
    fatJar: Path,
    projectDir: Path,
    extraArgs: List<String> = emptyList()
): ContainerRun {
    Files.createDirectories(projectDir)
    projectDir.resolve("rewrite.yaml").writeText(
        """
        ---
        type: specs.openrewrite.org/v1beta/recipe
        name: com.example.ReplaceOld
        recipeList:
          - org.openrewrite.text.FindAndReplace:
              find: old
              replace: new
              regex: false
              filePattern: "**/*.txt"
              plaintextOnly: true
        """.trimIndent()
    )
    projectDir.resolve("sample.txt").writeText("old\n")

    val process =
        ProcessBuilder(
            "docker",
            "run",
            "--rm",
            "--memory=2g",
            "--memory-swap=2g",
            "--user=${projectOwner(projectDir)}",
            "--mount",
            "type=bind,src=${projectDir.toAbsolutePath()},dst=/project",
            "--mount",
            "type=bind,src=${fatJar.toAbsolutePath()},dst=/app/rewrite-runner-all.jar,readonly",
            "eclipse-temurin:21-jre",
            "java",
            "-jar",
            "/app/rewrite-runner-all.jar",
            "--project-dir=/project",
            "--active-recipe=com.example.ReplaceOld",
            "--skip-plugin-run",
            "--plain-text-masks=**/*.txt",
            "--cache-dir=/project/.cache",
            "--output=report",
            *extraArgs.toTypedArray()
        )
            .redirectErrorStream(true)
            .start()
    val output = StringBuilder()
    val drain = thread(name = "rewrite-runner-container-output", isDaemon = true) {
        process.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { output.appendLine(it) }
        }
    }
    val finished = process.waitFor(180, TimeUnit.SECONDS)
    if (!finished) process.destroyForcibly()
    drain.join(1_000)
    assertTrue(finished, "Container run timed out:\n$output")

    val reportFile = projectDir.resolve("openrewrite-report.json")
    val worker = if (Files.isRegularFile(reportFile)) {
        Regex("""(?s)\{[^{}]*"executor"\s*:\s*"LST_WORKER"[^{}]*}""")
            .find(reportFile.readText())
            ?.value
    } else {
        null
    }
    return ContainerRun(projectDir, process.exitValue(), output.toString(), worker)
}

private fun projectOwner(projectDir: Path): String {
    val uid = Files.getAttribute(projectDir, "unix:uid")
    val gid = Files.getAttribute(projectDir, "unix:gid")
    return "$uid:$gid"
}

private fun String.numberField(name: String): Long = Regex("\\\"$name\\\"\\s*:\\s*(\\d+)")
    .find(this)
    ?.groupValues
    ?.get(1)
    ?.toLong()
    ?: error("Missing numeric $name in $this")

private fun String.stringField(name: String): String =
    Regex("\\\"$name\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
        .find(this)
        ?.groupValues
        ?.get(1)
        ?: error("Missing string $name in $this")
