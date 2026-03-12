package io.github.skhokhlov.rewriterunner

interface RunnerLogger {
    fun lifecycle(message: String)
    fun info(message: String)
    fun debug(message: String)
    fun warn(message: String)
    fun error(message: String, cause: Throwable? = null)
}

object NoOpRunnerLogger : RunnerLogger {
    override fun lifecycle(message: String) = Unit
    override fun info(message: String) = Unit
    override fun debug(message: String) = Unit
    override fun warn(message: String) = Unit
    override fun error(message: String, cause: Throwable?) = Unit
}
