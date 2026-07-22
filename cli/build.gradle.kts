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

val cliMainClass = "io.github.skhokhlov.rewriterunner.MainKt"

// Declare the entry point on the thin jar too (not just the fat jar), so the Maven Central
// artifact is self-describing: `jbang io.github.skhokhlov.rewriterunner:cli:<version>` and any
// `java -cp` launcher can find the main class without an explicit `--main`.
tasks.jar {
    manifest {
        attributes["Main-Class"] = cliMainClass
    }
}

tasks.shadowJar {
    archiveClassifier.set("all")
    mergeServiceFiles()
    manifest {
        attributes["Main-Class"] = cliMainClass
    }
}

// The `-all` fat JAR (bundling all of core's transitive tree) is too large for Maven Central's
// upload limit and conceptually isn't a library dependency. The gradleup shadow plugin
// auto-registers `shadowRuntimeElements` into the `java` component, which vanniktech then
// publishes; skip that variant so the fat JAR stays out of the Central publication. The
// `shadowJar` task itself remains available to build the artifact for GitHub Releases (see the
// release workflow and docs/adr/0009-cli-fatjar-distribution.md). The shadow plugin registers
// the variant on the `java` component in an afterEvaluate, so the skip must be deferred too.
afterEvaluate {
    (components["java"] as AdhocComponentWithVariants)
        .withVariantsFromConfiguration(configurations["shadowRuntimeElements"]) { skip() }
}

// Test partitioning. All test code lives in one source set (shares BaseIntegrationTest helpers
// and `ToolchainCache`); the four lanes are split by Gradle `Test.filter` class-name patterns
// rather than by Kotest tags. The split mirrors the CI jobs:
//
//   :cli:test           → unit-only (RunCommandTest et al.; excludes *IntegrationTest)
//   :cli:testIntegration → fake-wrapper integration suite; offline-safe, no network needed
//   :cli:testRealPlugin → real OpenRewrite Maven/Gradle plugins from Maven Central
//   :cli:testContainer  → release fat JAR in a real Docker cgroup
//
// Integration tests follow the `*IntegrationTest` class-name convention (enforced by the
// `failOnNoMatchingTests` flags below — a typo would surface immediately).
private val integrationClassPattern = "*IntegrationTest"
private val realPluginClassPattern = "*PluginRealExecutionIntegrationTest"
private val containerIntegrationClassPattern = "*ContainerForkedDistributionIntegrationTest"

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
        excludeTestsMatching(containerIntegrationClassPattern)
        isFailOnNoMatchingTests = true
    }
    shouldRunAfter(tasks.named("test"))
    dependsOn(tasks.shadowJar)
    doFirst {
        systemProperty(
            "rewriterunner.test.fatJar",
            tasks.shadowJar.get().archiveFile.get().asFile.absolutePath
        )
    }
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

// Real cgroup acceptance for the release-shaped fat JAR. Docker and the Java runtime image are
// intentional prerequisites: this task is selected only by the production gate and must fail if
// its environment cannot provide them.
tasks.register<Test>("testContainer") {
    group = "verification"
    description = "Runs the release fat JAR inside a Docker cgroup memory limit."
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform()
    filter {
        includeTestsMatching(containerIntegrationClassPattern)
        isFailOnNoMatchingTests = true
    }
    dependsOn(tasks.shadowJar)
    doFirst {
        systemProperty(
            "rewriterunner.test.fatJar",
            tasks.shadowJar.get().archiveFile.get().asFile.absolutePath
        )
    }
    timeout.set(Duration.ofMinutes(10))
    shouldRunAfter(tasks.named("testRealPlugin"))
}
