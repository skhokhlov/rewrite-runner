plugins {
    `maven-publish`
    signing
    java
}

java {
    withSourcesJar()
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])

            pom {
                name.set(project.name)
                description.set("A CLI tool and library for running OpenRewrite recipes against arbitrary project directories without requiring a working build system.")
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
    }

    repositories {
        maven {
            name = "centralPortal"
            url = uri("https://central.sonatype.com/api/v1/publisher/upload")
            credentials {
                username = providers.gradleProperty("centralPortalUsername")
                    .orElse(providers.environmentVariable("CENTRAL_PORTAL_USERNAME")).orNull
                password = providers.gradleProperty("centralPortalToken")
                    .orElse(providers.environmentVariable("CENTRAL_PORTAL_TOKEN")).orNull
            }
        }

        maven {
            name = "mavenLocal"
            url = uri(rootProject.layout.buildDirectory.dir("local-repo"))
        }
    }
}

signing {
    val key = providers.gradleProperty("signingKey")
        .orElse(providers.environmentVariable("ORG_GRADLE_PROJECT_signingKey"))
    val pwd = providers.gradleProperty("signingPassword")
        .orElse(providers.environmentVariable("ORG_GRADLE_PROJECT_signingPassword"))
    isRequired = key.orNull != null
    useInMemoryPgpKeys(key.orNull, pwd.orNull)
    sign(publishing.publications["mavenJava"])
}
