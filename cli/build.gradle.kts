import java.time.Duration

description = "CLI tool for running OpenRewrite recipes against arbitrary project directories without requiring a working build system"

plugins {
    id("kotlin-convention")
    id("publishing-convention")
    id("dokka-convention")
    alias(libs.plugins.shadow)
}

dependencies {
    implementation(project(":core"))

    // CLI framework
    implementation(libs.picocli)
    annotationProcessor(libs.picocli.codegen)

    // SLF4J backend for the CLI fat JAR
    implementation(libs.logback.classic)

    // Tests
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(kotlin("test"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // OpenRewrite needed in test scope for integration tests (Result, SourceFile, etc.)
    testImplementation(platform(libs.rewrite.recipe.bom))
    testImplementation("org.openrewrite:rewrite-core")
}

tasks.shadowJar {
    archiveClassifier.set("all")
    mergeServiceFiles()
    manifest {
        attributes["Main-Class"] = "io.github.skhokhlov.rewriterunner.MainKt"
    }
}

// Test partitioning. All test code lives in one source set (shares BaseIntegrationTest helpers
// and `ToolchainCache`); the three lanes are split by Gradle `Test.filter` class-name patterns
// rather than by Kotest tags. The split mirrors the three CI jobs:
//
//   :cli:test           → unit-only (RunCommandTest et al.; excludes *IntegrationTest)
//   :cli:testIntegration → fake-wrapper integration suite; offline-safe, no network needed
//   :cli:testRealPlugin → real OpenRewrite Maven/Gradle plugins from Maven Central
//
// Integration tests follow the `*IntegrationTest` class-name convention (enforced by the
// `failOnNoMatchingTests` flags below — a typo would surface immediately).
private val integrationClassPattern = "*IntegrationTest"
private val realPluginClassPattern = "*PluginRealExecutionIntegrationTest"

// Pin the Gradle distribution used by real-plugin tests to the same version the repo itself
// uses (see gradle/wrapper/gradle-wrapper.properties). Read eagerly at configuration time so a
// stale value can never silently slip into a cached test run.
private val realPluginGradleVersion: String = run {
    val wrapperProps = rootProject.layout.projectDirectory
        .file("gradle/wrapper/gradle-wrapper.properties").asFile
    val content = wrapperProps.readText()
    Regex("""gradle-(\d+\.\d+(?:\.\d+)?)-(?:bin|all)\.zip""")
        .find(content)?.groupValues?.get(1)
        ?: error("Could not parse Gradle version from $wrapperProps")
}

// `:cli:test` runs unit tests only. Integration suites are dedicated tasks so CI can sequence
// the three lanes (unit → fake-wrapper → real-plugin) and surface failures at the right stage.
tasks.named<Test>("test") {
    filter {
        excludeTestsMatching(integrationClassPattern)
        isFailOnNoMatchingTests = false
    }
}

// Fake-wrapper integration tests: per-language LST coverage + Stage 0 plugin-orchestration
// tests that drive fake `gradlew`/`mvnw` shell scripts. Fully offline; no toolchain downloads.
tasks.register<Test>("testIntegration") {
    group = "verification"
    description = "Runs integration tests with fake build-tool wrappers (offline-safe)."
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform()
    filter {
        includeTestsMatching(integrationClassPattern)
        excludeTestsMatching(realPluginClassPattern)
        isFailOnNoMatchingTests = true
    }
    shouldRunAfter(tasks.named("test"))
}

// Real-plugin integration tests: download Maven/Gradle distributions on first run and execute
// against the live OpenRewrite plugin artifacts from Maven Central. Expected runtime: <5 min
// warm, <15 min cold.
tasks.register<Test>("testRealPlugin") {
    group = "verification"
    description = "Runs Stage 0 tests against the real OpenRewrite Maven/Gradle plugins."
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform()
    filter {
        includeTestsMatching(realPluginClassPattern)
        isFailOnNoMatchingTests = true
    }
    // Bridge the Gradle version from the wrapper into the test JVM so ToolchainCache picks it up.
    systemProperty("rewriterunner.test.gradleVersion", realPluginGradleVersion)
    timeout.set(Duration.ofMinutes(20))
    shouldRunAfter(tasks.named("testIntegration"))
}
