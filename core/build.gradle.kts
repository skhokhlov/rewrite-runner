plugins {
    kotlin("jvm")
    `java-library`
}

group = "org.example"
version = "1.0-SNAPSHOT"

kotlin {
    jvmToolchain(21)
}

java {
    withSourcesJar()
}

dependencies {
    // OpenRewrite BOM + modules
    // rewrite-core is 'api' because callers use Result, SourceFile, Recipe, etc. directly
    api(platform("org.openrewrite.recipe:rewrite-recipe-bom:3.26.0"))
    api("org.openrewrite:rewrite-core")
    implementation("org.openrewrite:rewrite-java")
    implementation("org.openrewrite:rewrite-java-17")
    implementation("org.openrewrite:rewrite-java-21")
    implementation("org.openrewrite:rewrite-yaml")
    implementation("org.openrewrite:rewrite-json")
    implementation("org.openrewrite:rewrite-xml")
    implementation("org.openrewrite:rewrite-properties")
    implementation("org.openrewrite:rewrite-kotlin")
    implementation("org.openrewrite:rewrite-groovy")

    // Maven model (pom.xml parsing in Stage 2)
    implementation("org.apache.maven:maven-model:3.9.13")

    // Maven Resolver (JAR download for Stage 2 + recipe artifacts)
    implementation("org.apache.maven.resolver:maven-resolver-api:2.0.16")
    implementation("org.apache.maven.resolver:maven-resolver-impl:2.0.16")
    implementation("org.apache.maven.resolver:maven-resolver-connector-basic:2.0.16")
    implementation("org.apache.maven.resolver:maven-resolver-transport-http:2.0.16")
    implementation("org.apache.maven:maven-resolver-provider:3.9.13")

    // Config + JSON report
    implementation("tools.jackson.dataformat:jackson-dataformat-yaml:3.0.0")
    implementation("tools.jackson.module:jackson-module-kotlin:3.1.0")

    // Tests
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("-Xmx2g")
}
