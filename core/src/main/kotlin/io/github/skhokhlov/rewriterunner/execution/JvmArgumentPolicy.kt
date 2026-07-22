package io.github.skhokhlov.rewriterunner.execution

import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.floor
import kotlin.math.min

/** Where a runner-owned executor's effective JVM policy originated. */
enum class JvmConfigurationSource {
    RUNNER,
    PROJECT,
    ENVIRONMENT,
    AUTOMATIC,
    TOOL_DEFAULT
}

/** The resolved arguments and a safe, compact diagnostic view of their heap setting. */
internal data class EffectiveJvmArguments(
    val args: List<String>,
    val source: JvmConfigurationSource,
    val maximumHeapBytes: Long?,
    val warning: String? = null
)

/**
 * Container-aware automatic heap sizing. The JDK reports the active cgroup limit through
 * com.sun.management.OperatingSystemMXBean on supported runtimes, so no host-memory fallback is
 * used when detection is unavailable.
 */
internal object AutomaticHeapSizer {
    private const val MIB = 1024L * 1024L
    private const val GIB = 1024L * MIB
    private const val RESERVE = 512L * MIB
    private const val CAP = 16L * GIB

    fun detectedTotalMemory(): Long? =
        (ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean)
            ?.totalMemorySize
            ?.takeIf { it > 0 }

    fun automaticArgs(totalMemoryBytes: Long?): EffectiveJvmArguments {
        if (totalMemoryBytes == null || totalMemoryBytes <= 0) {
            return EffectiveJvmArguments(
                args = emptyList(),
                source = JvmConfigurationSource.TOOL_DEFAULT,
                maximumHeapBytes = null,
                warning =
                    "Could not detect container memory; retaining the JVM/build-tool default heap"
            )
        }
        val raw =
            if (totalMemoryBytes < GIB) {
                totalMemoryBytes * 0.50
            } else {
                min(
                    min(totalMemoryBytes * 0.70, (totalMemoryBytes - RESERVE).toDouble()),
                    CAP.toDouble()
                )
            }
        val bytes = floor(raw / MIB).toLong().coerceAtLeast(1) * MIB
        val warning = if (totalMemoryBytes < GIB) {
            "Low available memory (${formatBytes(
                totalMemoryBytes
            )}); automatic heap is conservative"
        } else {
            null
        }
        return EffectiveJvmArguments(
            args = listOf("-Xmx${bytes / MIB}m"),
            source = JvmConfigurationSource.AUTOMATIC,
            maximumHeapBytes = bytes,
            warning = warning
        )
    }

    internal fun formatBytes(bytes: Long): String = when {
        bytes % GIB == 0L -> "${bytes / GIB} GiB"
        else -> "${bytes / MIB} MiB"
    }
}

/** Applies the documented precedence rules without attempting to rewrite arbitrary JVM flags. */
internal object JvmArgumentPolicy {
    private val externalVariables = listOf("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS")

    fun forWorker(
        shared: List<String>,
        specific: List<String>,
        environment: Map<String, String> = System.getenv(),
        totalMemoryBytes: Long? = AutomaticHeapSizer.detectedTotalMemory()
    ): EffectiveJvmArguments = resolve(
        runnerArgs = shared + specific,
        hasProjectConfiguration = false,
        environment = environment,
        totalMemoryBytes = totalMemoryBytes
    )

    fun forPlugin(
        projectDir: Path,
        isGradle: Boolean,
        shared: List<String>,
        specific: List<String>,
        environment: Map<String, String> = System.getenv(),
        totalMemoryBytes: Long? = AutomaticHeapSizer.detectedTotalMemory()
    ): EffectiveJvmArguments = resolve(
        runnerArgs = shared + specific,
        hasProjectConfiguration = hasPluginProjectConfiguration(projectDir, isGradle, environment),
        environment = environment,
        totalMemoryBytes = totalMemoryBytes
    )

    private fun resolve(
        runnerArgs: List<String>,
        hasProjectConfiguration: Boolean,
        environment: Map<String, String>,
        totalMemoryBytes: Long?
    ): EffectiveJvmArguments {
        if (runnerArgs.isNotEmpty()) {
            return EffectiveJvmArguments(
                args = runnerArgs,
                source = JvmConfigurationSource.RUNNER,
                maximumHeapBytes = parseMaximumHeap(runnerArgs)
            )
        }
        if (externalVariables.any { environment[it].orEmpty().isNotBlank() }) {
            return EffectiveJvmArguments(
                args = emptyList(),
                source = JvmConfigurationSource.ENVIRONMENT,
                maximumHeapBytes = null
            )
        }
        if (hasProjectConfiguration) {
            return EffectiveJvmArguments(
                args = emptyList(),
                source = JvmConfigurationSource.PROJECT,
                maximumHeapBytes = null
            )
        }
        return AutomaticHeapSizer.automaticArgs(totalMemoryBytes)
    }

    private fun hasPluginProjectConfiguration(
        projectDir: Path,
        isGradle: Boolean,
        environment: Map<String, String>
    ): Boolean = if (isGradle) {
        (
            environment["GRADLE_OPTS"].orEmpty().isNotBlank() ||
                containsGradleJvmArgs(projectDir.resolve("gradle.properties"))
            )
    } else {
        (
            environment["MAVEN_OPTS"].orEmpty().isNotBlank() ||
                (
                    Files.exists(projectDir.resolve(".mvn/jvm.config")) &&
                        Files.readString(projectDir.resolve(".mvn/jvm.config")).isNotBlank()
                    )
            )
    }

    private fun containsGradleJvmArgs(file: Path): Boolean = try {
        Files.exists(file) && Files.readAllLines(file).any {
            it.trimStart().startsWith("org.gradle.jvmargs=")
        }
    } catch (_: Exception) {
        false
    }

    fun parseMaximumHeap(args: Iterable<String>): Long? = args.lastOrNull { it.startsWith("-Xmx") }
        ?.removePrefix("-Xmx")
        ?.let(::parseHeapBytes)

    private fun parseHeapBytes(value: String): Long? {
        val match = Regex("^(\\d+)([kKmMgGtT]?)$").matchEntire(value) ?: return null
        val amount = match.groupValues[1].toLongOrNull() ?: return null
        val multiplier = when (match.groupValues[2].lowercase()) {
            "k" -> 1024L
            "m" -> 1024L * 1024L
            "g" -> 1024L * 1024L * 1024L
            "t" -> 1024L * 1024L * 1024L * 1024L
            else -> 1L
        }
        return runCatching { Math.multiplyExact(amount, multiplier) }.getOrNull()
    }
}
