plugins {
    kotlin("jvm") apply false
    id("com.gradleup.shadow") apply false
    id("org.jlleitschuh.gradle.ktlint") apply false
}

subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    repositories {
        mavenCentral()
    }

    configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        android.set(true)
    }
}
