package io.github.skhokhlov.rewriterunner

import io.github.skhokhlov.rewriterunner.cli.RunCommand
import kotlin.system.exitProcess
import picocli.CommandLine

fun main(args: Array<String>) {
    val exitCode = CommandLine(RunCommand()).execute(*args)
    exitProcess(exitCode)
}

/**
 * Adjusts the log level for the `io.github.skhokhlov.rewriterunner` logger at runtime.
 * Requires logback-classic on the classpath (always true for the CLI fat JAR).
 */
internal fun setLogLevel(debug: Boolean) {
    val factory = org.slf4j.LoggerFactory.getILoggerFactory()
    if (factory is ch.qos.logback.classic.LoggerContext) {
        val level =
            if (debug) ch.qos.logback.classic.Level.DEBUG else ch.qos.logback.classic.Level.INFO
        factory.getLogger("io.github.skhokhlov.rewriterunner").level = level
    }
}
