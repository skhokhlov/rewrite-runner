package io.github.skhokhlov.rewriterunner

import io.github.skhokhlov.rewriterunner.cli.RunCommand
import kotlin.system.exitProcess
import picocli.CommandLine

fun main(args: Array<String>) {
    val exitCode = CommandLine(RunCommand()).execute(*args)
    exitProcess(exitCode)
}
