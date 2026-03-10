import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication

plugins {
    id("org.jetbrains.dokka")
    id("org.jetbrains.dokka-javadoc")
    java
}

// To generate documentation in HTML
val dokkaHtmlJar by tasks.registering(Jar::class) {
    description = "A HTML Documentation JAR containing Dokka HTML"
    from(tasks.dokkaGeneratePublicationHtml.flatMap { it.outputDirectory })
    archiveClassifier.set("html-doc")
}

// To generate documentation in Javadoc
val dokkaJavadocJar by tasks.registering(Jar::class) {
    description = "A Javadoc JAR containing Dokka Javadoc"
    from(tasks.dokkaGeneratePublicationJavadoc.flatMap { it.outputDirectory })
    archiveClassifier.set("javadoc")
}

// Wire the Dokka Javadoc JAR into the Maven publication when maven-publish is applied
pluginManager.withPlugin("maven-publish") {
    extensions.configure<PublishingExtension> {
        publications.withType<MavenPublication>().configureEach {
            artifact(dokkaJavadocJar)
        }
    }
}
