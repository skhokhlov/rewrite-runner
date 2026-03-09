package org.example

import java.util.logging.ConsoleHandler
import java.util.logging.Formatter
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.system.exitProcess
import org.example.cli.RunCommand
import picocli.CommandLine

fun main(args: Array<String>) {
    configureLogging()
    val exitCode = CommandLine(RunCommand()).execute(*args)
    exitProcess(exitCode)
}

/**
 * Routes org.example INFO/WARNING/ERROR logs to stderr with a compact prefix.
 * Library callers manage their own JUL configuration; this only applies to the CLI.
 */
private fun configureLogging() {
    val handler =
        ConsoleHandler().apply {
            level = Level.ALL
            formatter =
                object : Formatter() {
                    override fun format(record: LogRecord): String {
                        val prefix =
                            when {
                                record.level.intValue() >= Level.SEVERE.intValue() -> "ERROR"
                                record.level.intValue() >= Level.WARNING.intValue() -> "WARN "
                                else -> "INFO "
                            }
                        return "[$prefix] ${record.message}\n"
                    }
                }
        }
    Logger.getLogger("org.example").apply {
        useParentHandlers = false
        addHandler(handler)
        level = Level.INFO
    }
}
