pluginManagement {
    repositories {
        maven("https://repo.maven.apache.org/maven2") // Maven Central – includes Kotlin plugin
        maven("https://plugins.gradle.org/m2")        // Gradle plugin portal via Maven interface
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "rewrite-runner"
include(":core", ":cli")
