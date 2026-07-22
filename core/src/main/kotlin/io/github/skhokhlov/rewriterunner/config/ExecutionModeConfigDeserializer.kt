package io.github.skhokhlov.rewriterunner.config

import io.github.skhokhlov.rewriterunner.ExecutionMode
import tools.jackson.core.JsonParser
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.deser.std.StdDeserializer

/** Accepts the documented lower-case YAML spelling such as `mode: forked`. */
internal class ExecutionModeConfigDeserializer :
    StdDeserializer<ExecutionMode>(ExecutionMode::class.java) {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): ExecutionMode {
        val value = p.getValueAsString()
            ?: throw IllegalArgumentException("execution.mode must be a string")
        return ExecutionMode.parse(value)
    }
}
