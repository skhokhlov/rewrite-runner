plugins {
    kotlin("jvm")
    id("com.gradleup.shadow")
}

group = "org.example"
version = "1.0-SNAPSHOT"

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core"))

    // CLI framework
    implementation("info.picocli:picocli:4.7.7")
    annotationProcessor("info.picocli:picocli-codegen:4.7.7")

    // SLF4J backend for the CLI fat JAR
    implementation("ch.qos.logback:logback-classic:1.5.18")

    // Tests
    testImplementation(project(":core"))
    testImplementation("io.kotest:kotest-runner-junit5:6.1.4")
    testImplementation(kotlin("test"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // OpenRewrite needed in test scope for integration tests (Result, SourceFile, etc.)
    testImplementation(platform("org.openrewrite.recipe:rewrite-recipe-bom:3.26.0"))
    testImplementation("org.openrewrite:rewrite-core")
}

tasks.shadowJar {
    archiveClassifier.set("all")
    mergeServiceFiles()
    manifest {
        attributes["Main-Class"] = "org.example.MainKt"
    }
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("-Xmx2g")
}
