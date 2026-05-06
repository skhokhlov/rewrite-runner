package io.github.skhokhlov.rewriterunner.config

import java.time.Duration
import tools.jackson.core.JsonParser
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.deser.std.StdDeserializer

/** Parses user-facing timeout values such as `120s`, `10m`, `30000ms`, or `PT2M`. */
object DurationParser {
    private val humanDurationPattern = Regex("""^(\d+)(ms|s|m|h|d)$""")

    fun parse(value: String, fieldName: String = "duration"): Duration {
        val text = value.trim()
        require(text.isNotEmpty()) { "$fieldName must not be empty" }

        val duration = humanDurationPattern.matchEntire(text.lowercase())?.let { match ->
            val amount = match.groupValues[1].toLong()
            when (match.groupValues[2]) {
                "ms" -> Duration.ofMillis(amount)
                "s" -> Duration.ofSeconds(amount)
                "m" -> Duration.ofMinutes(amount)
                "h" -> Duration.ofHours(amount)
                "d" -> Duration.ofDays(amount)
                else -> error("unreachable")
            }
        } ?: parseIsoDuration(text, fieldName)

        return requirePositive(duration, fieldName)
    }

    fun requirePositive(duration: Duration, fieldName: String): Duration {
        require(!duration.isZero && !duration.isNegative) {
            "$fieldName must be greater than 0: $duration"
        }
        return duration
    }

    fun toMillisInt(duration: Duration, fieldName: String): Int {
        requirePositive(duration, fieldName)
        val millis = duration.toMillis()
        require(millis > 0) { "$fieldName must be at least 1ms: $duration" }
        require(millis <= Int.MAX_VALUE) {
            "$fieldName must be <= ${Int.MAX_VALUE}ms: $duration"
        }
        return millis.toInt()
    }

    private fun parseIsoDuration(text: String, fieldName: String): Duration {
        if (!text.startsWith("P", ignoreCase = true)) {
            throw IllegalArgumentException(
                "$fieldName must include a duration unit (ms, s, m, h, d) or use ISO-8601, " +
                    "for example 120s or PT2M"
            )
        }
        return try {
            Duration.parse(text.uppercase())
        } catch (e: Exception) {
            throw IllegalArgumentException(
                "$fieldName must include a valid duration unit (ms, s, m, h, d) or " +
                    "ISO-8601 value, for example 120s or PT2M",
                e
            )
        }
    }
}

internal class DurationConfigDeserializer : StdDeserializer<Duration>(Duration::class.java) {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): Duration {
        val fieldName = p.currentName() ?: "duration"
        val value = if (p.currentToken().isNumeric) {
            p.getLongValue().toString()
        } else {
            p.getValueAsString()
        } ?: throw IllegalArgumentException("$fieldName must be a duration string")
        return DurationParser.parse(value, fieldName)
    }
}
