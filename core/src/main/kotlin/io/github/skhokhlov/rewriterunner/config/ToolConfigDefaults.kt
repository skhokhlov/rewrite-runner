package io.github.skhokhlov.rewriterunner.config

import java.time.Duration

/** Central defaults used by [ToolConfig] and its nested configuration types. */
object ToolConfigDefaults {
    const val CACHE_DIR: String = "~/.rewriterunner/cache"
    const val INCLUDE_MAVEN_CENTRAL: Boolean = true
    const val ARTIFACT_DOWNLOAD_THREADS: Int = 5

    val SUBPROCESS_RUN_TIMEOUT: Duration = Duration.ofSeconds(120)
    val PLUGIN_RUN_TIMEOUT: Duration = Duration.ofMinutes(10)
    val ARTIFACT_RESOLVER_CONNECT_TIMEOUT: Duration = Duration.ofSeconds(30)
    val ARTIFACT_RESOLVER_REQUEST_TIMEOUT: Duration = Duration.ofSeconds(60)

    val REWRITE_GRADLE_PLUGIN_VERSION: String = BuildPluginVersions.REWRITE_GRADLE_PLUGIN_VERSION
    val REWRITE_MAVEN_PLUGIN_VERSION: String = BuildPluginVersions.REWRITE_MAVEN_PLUGIN_VERSION

    /** No JVM args are injected into the Stage 0 plugin subprocess by default. */
    val PLUGIN_JVM_ARGS: List<String> = emptyList()

    val DEFAULT_PLAIN_TEXT_MASKS: List<String> = listOf(
        "**/.gitattributes",
        "**/.gitignore",
        "**/.java-version",
        "**/.sdkmanrc",
        "**/[mM]akefile",
        "**/*.adoc",
        "**/*.aj",
        "**/*.bash",
        "**/*.bat",
        "**/*.config",
        "**/*.css",
        "**/*.env",
        "**/*.htm*",
        "**/*.jelly",
        "**/*.jsp",
        "**/*.ksh",
        "**/*.lock",
        "**/*.md",
        "**/*.mf",
        "**/*.qute.java",
        "**/*.sh",
        "**/*.sql",
        "**/*.svg",
        "**/*.tsx",
        "**/*.txt",
        "**/CODEOWNERS",
        "**/Dockerfile*",
        "**/gradlew",
        "**/lombok.config",
        "**/META-INF/services/**",
        "**/META-INF/spring.factories",
        "**/META-INF/spring/**",
        "**/mvnw"
    )
}
