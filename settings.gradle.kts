pluginManagement {
    repositories {
        maven("https://repo.maven.apache.org/maven2") // Maven Central – includes Kotlin plugin
        maven("https://plugins.gradle.org/m2")        // Gradle plugin portal via Maven interface
    }
    plugins {
        kotlin("jvm") version "2.3.20"
        id("com.gradleup.shadow") version "9.4.1"
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "rewrite-runner"
include(":core", ":cli")
