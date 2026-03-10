pluginManagement {
    repositories {
        maven("https://repo.maven.apache.org/maven2") // Maven Central – includes Kotlin plugin
        maven("https://plugins.gradle.org/m2")        // Gradle plugin portal via Maven interface
    }
    plugins {
        kotlin("jvm") version "2.3.10"
        id("com.gradleup.shadow") version "9.3.2"
        id("org.jetbrains.dokka") version "2.0.0" apply false
    }
}

rootProject.name = "rewrite-runner"
include(":core", ":cli")
