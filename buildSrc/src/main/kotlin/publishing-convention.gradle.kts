plugins {
    id("com.vanniktech.maven.publish")
    java
}

java {
    withSourcesJar()
}

mavenPublishing {
    publishToMavenCentral()

    signAllPublications()

    pom {
        name.set(project.name)
        description.set(project.description)
        url.set("https://github.com/skhokhlov/rewrite-runner")

        licenses {
            license {
                name.set("Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0")
            }
        }

        developers {
            developer {
                id.set("skhokhlov")
                name.set("Sergey Khokhlov")
                url.set("https://github.com/skhokhlov")
            }
        }

        scm {
            connection.set("scm:git:git://github.com/skhokhlov/rewrite-runner.git")
            developerConnection.set("scm:git:ssh://github.com/skhokhlov/rewrite-runner.git")
            url.set("https://github.com/skhokhlov/rewrite-runner")
        }
    }
}
