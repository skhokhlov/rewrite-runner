package io.github.skhokhlov.rewriterunner.plugin

import java.time.Duration

internal object PluginOutputReader {
    private val estimateLine = Regex("""Estimate time saved:\s*([0-9hmsHMS ]+)""")
    private val durationPart = Regex("""(\d+)\s*([hmsHMS])""")

    fun estimatedTimeSaved(output: String): Duration? {
        val estimate = estimateLine.findAll(output).lastOrNull()?.groupValues?.get(1)
            ?: return null
        return parseEstimate(estimate)
    }

    private fun parseEstimate(estimate: String): Duration? {
        val parts = durationPart.findAll(estimate).toList()
        if (parts.isEmpty()) return null

        val leftover = durationPart.replace(estimate, "").trim()
        if (leftover.isNotEmpty()) return null

        return parts.fold(Duration.ZERO) { total, match ->
            val amount = match.groupValues[1].toLongOrNull() ?: return null
            when (match.groupValues[2].lowercase()) {
                "h" -> total.plusHours(amount)
                "m" -> total.plusMinutes(amount)
                "s" -> total.plusSeconds(amount)
                else -> return null
            }
        }
    }
}
