package io.github.skhokhlov.rewriterunner.execution

import io.kotest.core.spec.style.FunSpec
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JvmArgumentPolicyTest :
    FunSpec({
        test("automatic heap sizing follows low-memory, reserve, and cap boundaries") {
            val mib = 1024L * 1024L
            val gib = 1024L * mib

            assertEquals("-Xmx256m", AutomaticHeapSizer.automaticArgs(512 * mib).args.single())
            assertEquals("-Xmx512m", AutomaticHeapSizer.automaticArgs(gib).args.single())
            assertEquals("-Xmx1433m", AutomaticHeapSizer.automaticArgs(2 * gib).args.single())
            assertEquals("-Xmx5734m", AutomaticHeapSizer.automaticArgs(8 * gib).args.single())
            assertEquals("-Xmx16384m", AutomaticHeapSizer.automaticArgs(32 * gib).args.single())
        }

        test("runner arguments are exact and suppress automatic heap sizing") {
            val resolved =
                JvmArgumentPolicy.forWorker(
                    shared = listOf("-XX:+HeapDumpOnOutOfMemoryError"),
                    specific = listOf("-Xmx3g"),
                    totalMemoryBytes = 8L * 1024L * 1024L * 1024L
                )

            assertEquals(
                listOf("-XX:+HeapDumpOnOutOfMemoryError", "-Xmx3g"),
                resolved.args
            )
            assertEquals(JvmConfigurationSource.RUNNER, resolved.source)
            assertEquals(3L * 1024L * 1024L * 1024L, resolved.maximumHeapBytes)
        }

        test("inherited JDK options preserve external policy and suppress automatic args") {
            val resolved =
                JvmArgumentPolicy.forWorker(
                    shared = emptyList(),
                    specific = emptyList(),
                    environment = mapOf("JAVA_TOOL_OPTIONS" to "-Xmx2g"),
                    totalMemoryBytes = 8L * 1024L * 1024L * 1024L
                )

            assertTrue(resolved.args.isEmpty())
            assertEquals(JvmConfigurationSource.ENVIRONMENT, resolved.source)
        }
    })
