description = "Library for running OpenRewrite recipes against arbitrary project directories without requiring a working build system"

plugins {
    id("kotlin-convention")
    id("publishing-convention")
    id("dokka-convention")
    `java-library`
}

dependencies {
    // OpenRewrite BOM + modules
    // rewrite-core is 'api' because callers use Result, SourceFile, Recipe, etc. directly
    api(platform("org.openrewrite.recipe:rewrite-recipe-bom:3.26.0"))
    api("org.openrewrite:rewrite-core")
    implementation("org.openrewrite:rewrite-java")
    implementation("org.openrewrite:rewrite-java-21")
    implementation("org.openrewrite:rewrite-java-25")
    implementation("org.openrewrite:rewrite-yaml")
    implementation("org.openrewrite:rewrite-json")
    implementation("org.openrewrite:rewrite-xml")
    implementation("org.openrewrite:rewrite-properties")
    implementation("org.openrewrite:rewrite-kotlin")
    implementation("org.openrewrite:rewrite-groovy")

    // Maven model (pom.xml parsing in Stage 2)
    implementation("org.apache.maven:maven-model:3.9.14")

    // Maven Resolver (JAR download for Stage 2 + recipe artifacts)
    implementation("org.apache.maven.resolver:maven-resolver-api:2.0.16")
    implementation("org.apache.maven.resolver:maven-resolver-impl:2.0.16")
    implementation("org.apache.maven.resolver:maven-resolver-supplier-mvn3:2.0.16")

    // Config + JSON report
    implementation("tools.jackson.dataformat:jackson-dataformat-yaml:3.1.0")
    implementation("tools.jackson.module:jackson-module-kotlin:3.1.0")

    // Tests
    testImplementation("ch.qos.logback:logback-classic:1.5.32")
    testImplementation("io.kotest:kotest-runner-junit5:6.1.6")
    testImplementation(kotlin("test"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
