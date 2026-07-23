import org.jetbrains.dokka.gradle.engine.parameters.VisibilityModifier

plugins {
    kotlin("jvm") apply false
    alias(libs.plugins.shadow) apply false
    id("org.jetbrains.dokka") // applied at root for multi-module HTML aggregation
    id("dokka-convention")
}

// Derive version from git tag (CI) or git describe (local); fall back to SNAPSHOT.
val tagVersion = System.getenv("GITHUB_REF")
    ?.takeIf { it.startsWith("refs/tags/") }
    ?.removePrefix("refs/tags/v")
val gitDescribe = runCatching {
    providers.exec { commandLine("git", "describe", "--tags", "--exact-match") }
        .standardOutput.asText.get().trim().removePrefix("v")
}.getOrNull()
val projectVersion = tagVersion ?: gitDescribe ?: "1.0-SNAPSHOT"

allprojects {
    version = projectVersion
}

dokka {
    // Sets properties for the whole project
    dokkaPublications.html {
        moduleName.set("rewrite-runner")
        includes.from("README.md")
    }

    dokkaSourceSets.configureEach {
        documentedVisibilities.set(setOf(VisibilityModifier.Public, VisibilityModifier.Protected))
        enableKotlinStdLibDocumentationLink.set(true)
        enableJdkDocumentationLink.set(true)
    }

}

// Aggregates subproject documentation
dependencies {
    dokka(project(":core"))
    dokka(project(":cli"))
}

// `check` remains the offline release signal: it includes unit tests plus the integration suite,
// whose coordinator/worker boundary and fallback-attribution Gradle builds use real processes.
// The live plugin lane stays explicit for local development but is part of the aggregate
// production gate.
tasks.named("check") {
    dependsOn(":cli:testIntegration")
}

tasks.register("productionCheck") {
    group = "verification"
    description = "Runs every configured production verification lane and builds the release fat JAR."
    dependsOn("check", ":cli:testRealPlugin", ":cli:testContainer", ":cli:shadowJar")
}
