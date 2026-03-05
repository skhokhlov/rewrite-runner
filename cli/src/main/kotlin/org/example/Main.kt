package org.example

import org.example.cli.RunCommand
import picocli.CommandLine
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val exitCode = CommandLine(RunCommand()).execute(*args)
    exitProcess(exitCode)
}
