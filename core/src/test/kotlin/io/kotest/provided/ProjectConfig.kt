package io.kotest.provided

import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.extensions.Extension
import io.kotest.core.listeners.BeforeEachListener
import io.kotest.core.test.TestCase
import java.util.concurrent.atomic.AtomicInteger

/** A missing or misspelled lane tag must fail verification rather than pass a skipped suite. */
class ProjectConfig : AbstractProjectConfig() {
    private val startedTests = AtomicInteger()

    override val failOnEmptyTestSuite = true
    override val extensions: List<Extension> =
        listOf(
            object : BeforeEachListener {
                override suspend fun beforeEach(testCase: TestCase) {
                    startedTests.incrementAndGet()
                }
            }
        )

    override suspend fun beforeProject() {
        startedTests.set(0)
    }

    override suspend fun afterProject() {
        check(startedTests.get() > 0) {
            "No tests selected for lane expression: ${System.getProperty("kotest.tags")}"
        }
    }
}
