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

// Real-plugin integration tests live in the same source set as the rest of the test code
// (they share BaseIntegrationTest helpers) but are partitioned by Gradle Test class-name
// filtering rather than by Kotest tags. The split is deliberate:
//  - the default `test` task EXCLUDES PluginRealExecutionIntegrationTest so the lane stays
//    fast and offline-safe;
//  - `testRealPlugin` INCLUDES ONLY that class, downloads Maven/Gradle on demand, and runs
//    against the live OpenRewrite plugin artifacts on Maven Central.
private val testRealPluginClassPattern = "*PluginRealExecutionIntegrationTest*"

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

tasks.named<Test>("test") {
    filter {
        excludeTestsMatching(testRealPluginClassPattern)
        isFailOnNoMatchingTests = false
    }
}

// Dedicated task that runs ONLY the real-plugin integration suite. First run downloads the
// Maven/Gradle distributions and OpenRewrite plugins from Maven Central; subsequent runs are
// cache hits. Expected runtime: <5 min warm, <15 min cold.
tasks.register<Test>("testRealPlugin") {
    group = "verification"
    description = "Runs Stage 0 tests against the real OpenRewrite Maven/Gradle plugins."
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform()
    filter {
        includeTestsMatching(testRealPluginClassPattern)
        isFailOnNoMatchingTests = true
    }
    // Bridge the Gradle version from the wrapper into the test JVM so ToolchainCache picks it up.
    systemProperty("rewriterunner.test.gradleVersion", realPluginGradleVersion)
    timeout.set(Duration.ofMinutes(20))
}
