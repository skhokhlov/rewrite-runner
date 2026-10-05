import org.gradle.api.attributes.Bundling
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
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
    testLogging.exceptionFormat = TestExceptionFormat.FULL
}

// The default lane runs untagged tests. Kotest owns tag selection (these are Kotest
// specs, not Jupiter tests); task-local properties keep aggregate runs independent.
tasks.named<Test>("test") {
    systemProperty("kotest.tags", "!integration & !worker & !real-plugin & !container")
}

tasks.register<Test>("testWorker") {
    group = "verification"
    description = "Runs tests tagged worker, including cross-platform forked execution."
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    systemProperty("kotest.tags", "worker")
    shouldRunAfter(tasks.named("test"))
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
