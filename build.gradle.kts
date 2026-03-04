plugins {
    kotlin("jvm") version "2.3.0"
    id("com.gradleup.shadow") version "9.0.0"
}

group = "org.example"
version = "1.0-SNAPSHOT"

kotlin {
    jvmToolchain(21)
}

repositories {
    mavenCentral()
}

dependencies {
    // OpenRewrite BOM + modules
    implementation(platform("org.openrewrite.recipe:rewrite-recipe-bom:3.10.1"))
    implementation("org.openrewrite:rewrite-core")
    implementation("org.openrewrite:rewrite-java")
    implementation("org.openrewrite:rewrite-java-17")
    implementation("org.openrewrite:rewrite-java-21")
    implementation("org.openrewrite:rewrite-yaml")
    implementation("org.openrewrite:rewrite-json")
    implementation("org.openrewrite:rewrite-xml")
    implementation("org.openrewrite:rewrite-properties")
    implementation("org.openrewrite:rewrite-kotlin")
    implementation("org.openrewrite:rewrite-groovy")

    // CLI framework
    implementation("info.picocli:picocli:4.7.6")
    annotationProcessor("info.picocli:picocli-codegen:4.7.6")

    // Maven model (pom.xml parsing in Stage 2)
    implementation("org.apache.maven:maven-model:3.9.12")

    // Maven Resolver (JAR download for Stage 2 + recipe artifacts)
    implementation("org.apache.maven.resolver:maven-resolver-api:1.9.22")
    implementation("org.apache.maven.resolver:maven-resolver-impl:1.9.22")
    implementation("org.apache.maven.resolver:maven-resolver-connector-basic:1.9.22")
    implementation("org.apache.maven.resolver:maven-resolver-transport-http:1.9.22")
    implementation("org.apache.maven:maven-resolver-provider:3.9.12")

    // Config + JSON report
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.18.2")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.18.2")

    // Tests
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
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
}
