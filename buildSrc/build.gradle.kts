plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.org.jetbrains.dokka.gradle.plugin)
    implementation(libs.org.jetbrains.dokka.javadoc.gradle.plugin)
    implementation(libs.com.vanniktech.maven.publish.gradle.plugin)
}
