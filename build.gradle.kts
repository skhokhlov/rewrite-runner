import org.jetbrains.dokka.gradle.engine.parameters.VisibilityModifier

plugins {
    kotlin("jvm") apply false
    id("com.gradleup.shadow") apply false
    id("org.jetbrains.dokka") // applied at root for multi-module HTML aggregation
    id("dokka-convention")
}

// Resolve ktlint CLI directly from Maven Central — no third-party Gradle plugin needed.
// This avoids the Gradle plugin portal CDN (plugins-artifacts.gradle.org) and works in
// any environment that can reach Maven Central.
val ktlintVersion = "1.8.0"

val ktlintCli by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
    attributes {
        // Request the shadow (fat) JAR so all ktlint dependencies are bundled in one artifact.
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.SHADOWED))
    }
}

repositories {
    mavenCentral()
}

dependencies {
    ktlintCli("com.pinterest.ktlint:ktlint-cli:$ktlintVersion")
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
        documentedVisibilities.set(setOf(VisibilityModifier.Public))
    }

}

// Aggregates subproject documentation
dependencies {
    dokka(project(":core"))
    dokka(project(":cli"))
}
