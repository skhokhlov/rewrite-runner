package io.github.skhokhlov.rewriterunner.cli

import io.github.skhokhlov.rewriterunner.RunnerLogger
import org.slf4j.LoggerFactory

class LogbackRunnerLogger(private val showInfo: Boolean, private val showDebug: Boolean) :
    RunnerLogger {
    private val log = LoggerFactory.getLogger("io.github.skhokhlov.rewriterunner")

    override fun lifecycle(message: String) = log.info(message)

    override fun info(message: String) {
        if (showInfo || showDebug) log.info(message)
    }

    override fun debug(message: String) {
        if (showDebug) log.debug(message)
    }

    override fun warn(message: String) = log.warn(message)

    override fun error(message: String, cause: Throwable?) {
        if (cause != null) log.error(message, cause) else log.error(message)
    }
}
