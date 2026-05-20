import org.gradle.api.attributes.Bundling
import org.gradle.api.tasks.PathSensitivity
import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    kotlin("jvm")
    jacoco
}

group = "io.github.skhokhlov.rewriterunner"

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(21)
}

tasks.withType<Test> {
    useJUnitPlatform()
    jvmArgs("-Xmx2g")
    val defaultParallelForks =
        providers.environmentVariable("CI")
            .map { if (it == "true" && project.name == "core") "2" else "1" }
            .orElse("1")
    maxParallelForks =
        providers.gradleProperty("${project.name}TestMaxParallelForks")
            .orElse(providers.gradleProperty("testMaxParallelForks"))
            .orElse(defaultParallelForks)
            .map { it.toInt().coerceAtLeast(1) }
            .get()
}

// Resolve ktlint CLI locally in each subproject to avoid cross-project configuration resolution
// (required for Gradle 9 project isolation / configuration cache compatibility).
val ktlintCli by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
    attributes {
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.SHADOWED))
    }
}

dependencies {
    ktlintCli("com.pinterest.ktlint:ktlint-cli:1.8.0")
}

// ktlintCheck — verifies code style; wired into the standard `check` lifecycle task.
tasks.register<JavaExec>("ktlintCheck") {
    group = "verification"
    description = "Check Kotlin code style with ktlint (Google Android code style)."
    classpath = ktlintCli
    mainClass.set("com.pinterest.ktlint.Main")
    args("--reporter=plain", "src/**/*.kt")
    workingDir = projectDir

    val kotlinSources = project.fileTree("src") {
        include("**/*.kt")
    }
    inputs.files(kotlinSources)
        .withPropertyName("kotlinSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(ktlintCli)
        .withPropertyName("ktlintCliClasspath")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    val markerFile = layout.buildDirectory.file("reports/ktlint/ktlintCheck.marker")
    outputs.file(markerFile)
    outputs.cacheIf { true }
    doLast {
        markerFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText("ktlint passed\n")
        }
    }
}

// ktlintFormat — auto-fixes style violations in-place.
tasks.register<JavaExec>("ktlintFormat") {
    group = "formatting"
    description = "Auto-fix Kotlin code style issues with ktlint (Google Android code style)."
    classpath = ktlintCli
    mainClass.set("com.pinterest.ktlint.Main")
    jvmArgs("--add-opens=java.base/java.lang=ALL-UNNAMED")
    args("--format", "src/**/*.kt")
    workingDir = projectDir
}

// Wire ktlintCheck into the standard check lifecycle so `./gradlew check` enforces style.
// Configure JaCoCo to emit XML + HTML reports and auto-run after every test task.
afterEvaluate {
    tasks.findByName("check")?.dependsOn("ktlintCheck")

    tasks.withType<JacocoReport>().configureEach {
        reports {
            xml.required.set(true)
            html.required.set(true)
        }
    }

    tasks.findByName("test")?.finalizedBy(tasks.named("jacocoTestReport"))
}
