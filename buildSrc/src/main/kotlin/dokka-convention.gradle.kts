plugins {
    id("org.jetbrains.dokka")
    java
}

// To generate documentation in HTML
val dokkaHtmlJar by tasks.registering(Jar::class) {
    description = "A HTML Documentation JAR containing Dokka HTML"
    from(tasks.dokkaGeneratePublicationHtml.flatMap { it.outputDirectory })
    archiveClassifier.set("html-doc")
}

private val repoBaseUrl = "https://github.com/skhokhlov/rewrite-runner"

dokka {
    dokkaSourceSets.configureEach {
        sourceLink {
            localDirectory.set(file("src/main/kotlin"))
            remoteUrl("$repoBaseUrl/blob/main/${projectDir.relativeTo(rootDir)}/src/main/kotlin")
            remoteLineSuffix.set("#L")
        }
    }
}
