plugins {
    kotlin("jvm") apply false
    id("com.gradleup.shadow") apply false
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

subprojects {
    repositories {
        mavenCentral()
    }

    // ktlintCheck — verifies code style; wired into the standard `check` lifecycle task.
    tasks.register<JavaExec>("ktlintCheck") {
        group = "verification"
        description = "Check Kotlin code style with ktlint (Google Android code style)."
        classpath = rootProject.configurations["ktlintCli"]
        mainClass.set("com.pinterest.ktlint.Main")
        args("--reporter=plain", "src/**/*.kt")
        workingDir = projectDir
    }

    // ktlintFormat — auto-fixes style violations in-place.
    tasks.register<JavaExec>("ktlintFormat") {
        group = "formatting"
        description = "Auto-fix Kotlin code style issues with ktlint (Google Android code style)."
        classpath = rootProject.configurations["ktlintCli"]
        mainClass.set("com.pinterest.ktlint.Main")
        jvmArgs("--add-opens=java.base/java.lang=ALL-UNNAMED")
        args("--format", "src/**/*.kt")
        workingDir = projectDir
    }

    // Wire ktlintCheck into the standard check lifecycle so `./gradlew check` enforces style.
    afterEvaluate {
        tasks.findByName("check")?.dependsOn("ktlintCheck")
    }
}
