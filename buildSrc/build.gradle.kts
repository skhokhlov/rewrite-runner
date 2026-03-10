plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    implementation(kotlin("gradle-plugin", "2.3.10"))
    implementation("org.jetbrains.dokka:dokka-gradle-plugin:2.0.0")
}
