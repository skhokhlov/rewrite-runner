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
    testImplementation(project(":core"))
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
