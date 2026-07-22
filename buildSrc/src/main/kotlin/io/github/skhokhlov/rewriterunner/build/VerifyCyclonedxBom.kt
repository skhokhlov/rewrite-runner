package io.github.skhokhlov.rewriterunner.build

import org.cyclonedx.Version
import org.cyclonedx.parsers.JsonParser
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Verification tasks have no outputs")
abstract class VerifyCyclonedxBom : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val bomFile: RegularFileProperty

    @get:Input
    abstract val expectedGroup: Property<String>

    @get:Input
    abstract val expectedName: Property<String>

    @get:Input
    abstract val expectedVersion: Property<String>

    @TaskAction
    fun verifyBom() {
        val file = bomFile.get().asFile
        val parser = JsonParser()
        val validationErrors = parser.validate(file, Version.VERSION_16)
        if (validationErrors.isNotEmpty()) {
            val details = validationErrors.take(5).joinToString(separator = "\n") {
                "- ${it.message}"
            }
            throw GradleException("Invalid CycloneDX 1.6 SBOM at $file:\n$details")
        }

        val bom = parser.parse(file)
        val component = bom.metadata?.component
            ?: throw GradleException("CycloneDX SBOM at $file has no metadata component")

        verifyValue("group", expectedGroup.get(), component.group, file)
        verifyValue("name", expectedName.get(), component.name, file)
        verifyValue("version", expectedVersion.get(), component.version, file)

        if (bom.components.isNullOrEmpty()) {
            throw GradleException("CycloneDX SBOM at $file contains no dependency components")
        }
        if (bom.dependencies.isNullOrEmpty()) {
            throw GradleException("CycloneDX SBOM at $file contains no dependency graph")
        }

        val licenseIds = bom.metadata?.licenses?.licenses.orEmpty().mapNotNull { it.id }.toSet()
        if ("Apache-2.0" !in licenseIds) {
            throw GradleException(
                "CycloneDX SBOM at $file does not identify the project license as Apache-2.0"
            )
        }

        val rootReference = component.bomRef
        if (rootReference.isNullOrBlank() || bom.dependencies.none { it.ref == rootReference }) {
            throw GradleException(
                "CycloneDX SBOM at $file has no dependency graph entry for its root component"
            )
        }
    }

    private fun verifyValue(label: String, expected: String, actual: String?, file: java.io.File) {
        if (actual != expected) {
            throw GradleException(
                "CycloneDX SBOM at $file has metadata component $label '$actual'; expected '$expected'"
            )
        }
    }
}
