package io.kotest.provided

import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.extensions.Extension
import io.kotest.core.listeners.AfterEachListener
import io.kotest.core.test.TestCase
import io.kotest.engine.test.TestResult
import java.util.concurrent.atomic.AtomicInteger

/** A missing or misspelled lane tag must fail verification rather than pass a skipped suite. */
class ProjectConfig : AbstractProjectConfig() {
    private val executedTests = AtomicInteger()

    override val failOnEmptyTestSuite = true
    override val extensions: List<Extension> =
        listOf(
            object : AfterEachListener {
                override suspend fun afterEach(testCase: TestCase, result: TestResult) {
                    if (result !is TestResult.Ignored) executedTests.incrementAndGet()
                }
            }
        )

    override suspend fun beforeProject() {
        executedTests.set(0)
    }

    override suspend fun afterProject() {
        check(executedTests.get() > 0) {
            "No tests executed for lane expression: ${System.getProperty("kotest.tags")}"
        }
    }
}
