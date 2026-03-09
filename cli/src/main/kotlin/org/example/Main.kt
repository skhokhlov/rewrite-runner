package org.example

import kotlin.system.exitProcess
import org.example.cli.RunCommand
import picocli.CommandLine

fun main(args: Array<String>) {
    val exitCode = CommandLine(RunCommand()).execute(*args)
    exitProcess(exitCode)
}

/**
 * Adjusts the log level for the `org.example` logger at runtime.
 * Requires logback-classic on the classpath (always true for the CLI fat JAR).
 */
internal fun setLogLevel(debug: Boolean) {
    val factory = org.slf4j.LoggerFactory.getILoggerFactory()
    if (factory is ch.qos.logback.classic.LoggerContext) {
        val level =
            if (debug) ch.qos.logback.classic.Level.DEBUG else ch.qos.logback.classic.Level.INFO
        factory.getLogger("org.example").level = level
    }
}
