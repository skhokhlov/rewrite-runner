plugins {
    kotlin("jvm") apply false
    id("com.gradleup.shadow") apply false
}

subprojects {
    repositories {
        mavenCentral()
    }
}
