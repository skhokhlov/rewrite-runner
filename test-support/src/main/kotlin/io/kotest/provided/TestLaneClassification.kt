package io.kotest.provided

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.Spec
import java.io.File
import java.lang.reflect.Modifier

private val lanes = setOf("integration", "worker", "real-plugin", "container")

internal fun validateLaneClassification(className: String, tags: List<String>) {
    check(tags.all { it in lanes }) {
        "$className has unsupported lane tags $tags; use exactly one of $lanes or no tag for default tests"
    }
    check(tags.size <= 1) { "$className must belong to exactly one lane; found $tags" }
    check(
        !className.startsWith("io.github.skhokhlov.rewriterunner.integration.") || tags.size == 1
    ) {
        "$className is an integration spec without a class-level @Tags lane annotation"
    }
}

/** Validate all compiled specs before Kotest filters them, without constructing their test bodies. */
internal fun validateCompiledSpecs() {
    // Gradle supplies the complete test output even when --tests selects only one spec.
    // IDE runners do not supply this property; Gradle check remains the verification gate.
    val directories = System.getProperty("rewriterunner.test.classes") ?: return
    directories.split(File.pathSeparator).map(::File).filter { it.isDirectory }.forEach { root ->
        root.walkTopDown().filter { it.isFile && it.extension == "class" }.forEach { file ->
            val name = file.relativeTo(
                root
            ).path.removeSuffix(".class").replace(File.separatorChar, '.')
            val type = Class.forName(name, false, ProjectConfig::class.java.classLoader)
            if (Spec::class.java.isAssignableFrom(type) && !Modifier.isAbstract(type.modifiers)) {
                validateLaneClassification(
                    name,
                    type.getAnnotation(Tags::class.java)?.values?.toList().orEmpty()
                )
            }
        }
    }
}
