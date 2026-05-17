import org.cyclonedx.model.ExternalReference
import org.cyclonedx.model.License
import org.cyclonedx.model.LicenseChoice

plugins {
    id("org.cyclonedx.bom")
}

tasks.cyclonedxDirectBom {
    jsonOutput.set(file("${project.layout.buildDirectory.get()}/reports/cyclonedx/bom.json"))
    xmlOutput.unsetConvention()
    includeConfigs = listOf("compileClasspath", "runtimeClasspath")
    includeLicenseText = false
    licenseChoice = LicenseChoice().apply {
        addLicense(License().apply {
            name = "Apache-2.0"
            url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
        })
    }
    includeBuildSystem = true
}
