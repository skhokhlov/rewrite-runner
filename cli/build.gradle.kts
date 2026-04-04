description = "CLI tool for running OpenRewrite recipes against arbitrary project directories without requiring a working build system"

plugins {
    id("kotlin-convention")
    id("publishing-convention")
    id("dokka-convention")
    id("com.gradleup.shadow")
}

dependencies {
    implementation(project(":core"))

    // CLI framework
    implementation("info.picocli:picocli:4.7.7")
    annotationProcessor("info.picocli:picocli-codegen:4.7.7")

    // SLF4J backend for the CLI fat JAR
    implementation("ch.qos.logback:logback-classic:1.5.32")

    // Tests
    testImplementation(project(":core"))
    testImplementation("io.kotest:kotest-runner-junit5:6.1.6")
    testImplementation(kotlin("test"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // OpenRewrite needed in test scope for integration tests (Result, SourceFile, etc.)
    testImplementation(platform("org.openrewrite.recipe:rewrite-recipe-bom:3.27.0"))
    testImplementation("org.openrewrite:rewrite-core")
}

tasks.shadowJar {
    archiveClassifier.set("all")
    mergeServiceFiles()
    manifest {
        attributes["Main-Class"] = "io.github.skhokhlov.rewriterunner.MainKt"
    }
}
