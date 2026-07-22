import io.github.skhokhlov.rewriterunner.build.VerifyCyclonedxBom
import org.cyclonedx.gradle.CyclonedxDirectTask
import org.cyclonedx.model.License
import org.cyclonedx.model.LicenseChoice

plugins {
    id("org.cyclonedx.bom")
}

val cyclonedxDirectBom = tasks.named<CyclonedxDirectTask>("cyclonedxDirectBom") {
    jsonOutput.set(layout.buildDirectory.file("reports/cyclonedx/bom.json"))
    xmlOutput.unsetConvention()
    includeConfigs.set(listOf("compileClasspath", "runtimeClasspath"))
    includeLicenseText.set(false)
    licenseChoice.set(
        LicenseChoice().apply {
            addLicense(
                License().apply {
                    id = "Apache-2.0"
                    url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                }
            )
        }
    )
    includeBuildSystem.set(true)
}

val verifyCyclonedxBom = tasks.register<VerifyCyclonedxBom>("verifyCyclonedxBom") {
    group = "verification"
    description = "Validates the generated CycloneDX SBOM and its publication metadata."
    bomFile.set(cyclonedxDirectBom.flatMap { it.jsonOutput })
    expectedGroup.set(provider { project.group.toString() })
    expectedName.set(provider { project.name })
    expectedVersion.set(provider { project.version.toString() })
}

tasks.named("check") {
    dependsOn(verifyCyclonedxBom)
}
