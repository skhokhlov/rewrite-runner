package org.example

import kotlin.system.exitProcess
import org.example.cli.RunCommand
import picocli.CommandLine

fun main(args: Array<String>) {
    val exitCode = CommandLine(RunCommand()).execute(*args)
    exitProcess(exitCode)
}
