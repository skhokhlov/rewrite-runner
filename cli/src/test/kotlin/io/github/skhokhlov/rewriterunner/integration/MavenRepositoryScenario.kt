package io.github.skhokhlov.rewriterunner.integration

import io.github.skhokhlov.rewriterunner.RewriteRunner
import io.github.skhokhlov.rewriterunner.UsedExecutionStage
import io.github.skhokhlov.rewriterunner.config.RepositoryConfig
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val EXECUTOR_PROBE_CLASS =
    "io.github.skhokhlov.rewriterunner.integration.probe.ExecutorProbeRecipe"

/** Real Maven acceptance fixture for repository forwarding, settings preservation and failure. */
internal fun runMavenRepositoryScenario() {
    requireMavenCentralReachable()
    val project = Files.createTempDirectory("maven-repositories-")
    val repository = project.resolve("private-repository")
    Files.createDirectories(repository)
    val group = "com.example.repository${java.util.UUID.randomUUID().toString().replace("-", "")}"
    val requests = java.util.Collections.synchronizedList(mutableListOf<String>())
    val logs = java.util.Collections.synchronizedList(mutableListOf<String>())
    val logger = object : io.github.skhokhlov.rewriterunner.RunnerLogger {
        override fun lifecycle(message: String) {
            logs.add(message)
        }
        override fun info(message: String) {
            logs.add(message)
        }
        override fun debug(message: String) {
            logs.add(message)
        }
        override fun warn(message: String) {
            logs.add(message)
        }
        override fun error(message: String, cause: Throwable?) {
            logs.add(message)
        }
    }
    val credentials = "repo-user:repo-password"
    val authorization =
        "Basic " + java.util.Base64.getEncoder().encodeToString(credentials.toByteArray())
    val server = com.sun.net.httpserver.HttpServer.create(
        java.net.InetSocketAddress("127.0.0.1", 0),
        0
    )
    server.createContext("/") { exchange ->
        if (exchange.requestHeaders.getFirst("Authorization") != authorization) {
            exchange.responseHeaders.add("WWW-Authenticate", "Basic realm=repository")
            exchange.sendResponseHeaders(401, -1)
        } else {
            requests.add(exchange.requestURI.path)
            val file = repository.resolve(exchange.requestURI.path.removePrefix("/"))
            if (Files.isRegularFile(file)) {
                val bytes = Files.readAllBytes(file)
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.write(bytes)
            } else {
                exchange.sendResponseHeaders(404, -1)
            }
        }
        exchange.close()
    }
    server.start()
    try {
        fun publish(
            artifact: String,
            version: String,
            className: String? = null,
            plugin: Boolean = false
        ) {
            val directory = repository.resolve("${group.replace('.', '/')}/$artifact/$version")
            Files.createDirectories(directory)
            JarOutputStream(
                Files.newOutputStream(directory.resolve("$artifact-$version.jar"))
            ).use { jar ->
                if (className != null) {
                    val resource = className.replace('.', '/') + ".class"
                    jar.putNextEntry(JarEntry(resource))
                    requireNotNull(
                        Thread.currentThread().contextClassLoader.getResourceAsStream(resource)
                    ).use {
                        it.copyTo(jar)
                    }
                    jar.closeEntry()
                }
                if (plugin) {
                    jar.putNextEntry(JarEntry("META-INF/maven/plugin.xml"))
                    jar.write(
                        """
                        <plugin><name>Repository probe</name><groupId>$group</groupId>
                        <artifactId>$artifact</artifactId><version>$version</version><goalPrefix>repository-probe</goalPrefix>
                        <mojos><mojo><goal>probe</goal><implementation>$className</implementation>
                        <language>java</language><instantiationStrategy>per-lookup</instantiationStrategy>
                        <requiresProject>true</requiresProject><threadSafe>true</threadSafe>
                        <parameters><parameter><name>basedir</name><type>java.io.File</type><required>true</required></parameter>
                        <parameter><name>globalSetting</name><type>java.lang.String</type><required>true</required></parameter></parameters>
                        <configuration><basedir implementation="java.io.File" default-value="${'$'}{project.basedir}"/>
                        <globalSetting implementation="java.lang.String" default-value="${'$'}{global.setting.preserved}"/></configuration></mojo></mojos></plugin>
                        """.trimIndent().toByteArray()
                    )
                    jar.closeEntry()
                }
            }
            directory.resolve("$artifact-$version.pom").writeText(
                """
                <project><modelVersion>4.0.0</modelVersion><groupId>$group</groupId>
                <artifactId>$artifact</artifactId><version>$version</version></project>
                """.trimIndent()
            )
        }
        publish("private-recipe", "1.0", EXECUTOR_PROBE_CLASS)
        publish("private-recipe", "2.0-SNAPSHOT", EXECUTOR_PROBE_CLASS)
        val snapshotDirectory = repository.resolve(
            "${group.replace('.', '/')}/private-recipe/2.0-SNAPSHOT"
        )
        listOf("jar", "pom").forEach { extension ->
            Files.move(
                snapshotDirectory.resolve("private-recipe-2.0-SNAPSHOT.$extension"),
                snapshotDirectory.resolve("private-recipe-2.0-20261003.000000-1.$extension")
            )
        }
        snapshotDirectory.resolve("maven-metadata.xml").writeText(
            """
            <metadata><groupId>$group</groupId><artifactId>private-recipe</artifactId><version>2.0-SNAPSHOT</version>
            <versioning><snapshot><timestamp>20261003.000000</timestamp><buildNumber>1</buildNumber></snapshot>
            <lastUpdated>20261003000000</lastUpdated><snapshotVersions>
            <snapshotVersion><extension>jar</extension><value>2.0-20261003.000000-1</value><updated>20261003000000</updated></snapshotVersion>
            <snapshotVersion><extension>pom</extension><value>2.0-20261003.000000-1</value><updated>20261003000000</updated></snapshotVersion>
            </snapshotVersions></versioning></metadata>
            """.trimIndent()
        )
        publish("private-dependency", "1.0")
        publish(
            "private-plugin",
            "1.0",
            "io.github.skhokhlov.rewriterunner.integration.probe.RepositoryProbeMojo",
            true
        )
        Files.walk(repository).use { paths ->
            paths.filter { Files.isRegularFile(it) }.toList().forEach { file ->
                val digest = java.security.MessageDigest.getInstance(
                    "SHA-1"
                ).digest(Files.readAllBytes(file))
                file.resolveSibling(
                    "${file.fileName}.sha1"
                ).writeText(java.util.HexFormat.of().formatHex(digest))
            }
        }
        PluginScenarios.mavenSingleFile.setUpProject(project)
        val pom = project.resolve("pom.xml")
        pom.writeText(
            pom.readText().replace(
                "</project>",
                """
            <dependencies><dependency><groupId>$group</groupId><artifactId>private-dependency</artifactId><version>1.0</version></dependency></dependencies>
            <build><plugins><plugin><groupId>$group</groupId><artifactId>private-plugin</artifactId><version>1.0</version>
            <executions><execution><phase>validate</phase><goals><goal>probe</goal></goals></execution></executions>
            </plugin></plugins></build></project>
                """.trimIndent()
            )
        )
        val userSettings = project.resolve("user-settings.xml")
        val globalSettings = project.resolve("global-settings.xml")
        // The mirror proves runner sources pass through Maven's own mirror selector. Its
        // credentials come from user settings; a separate global profile must remain active.
        val url = "http://127.0.0.1:${server.address.port}"
        userSettings.writeText(
            """
            <settings><mirrors><mirror><id>private-mirror</id><mirrorOf>*,!central</mirrorOf><url>$url</url></mirror></mirrors>
            <servers><server><id>private-mirror</id><username>repo-user</username><password>repo-password</password></server></servers></settings>
            """.trimIndent()
        )
        globalSettings.writeText(
            """
            <settings><profiles><profile><id>global</id><properties><global.setting.preserved>1.0</global.setting.preserved></properties></profile></profiles>
            <activeProfiles><activeProfile>global</activeProfile></activeProfiles></settings>
            """.trimIndent()
        )
        installRealWrapper(project)
        val wrapper = project.resolve("mvnw")
        wrapper.writeText(
            wrapper.readText().replace(
                "\"\$@\"",
                "-s \"$userSettings\" -gs \"$globalSettings\" \"\$@\""
            )
        )
        val source = project.resolve("src/main/java/App.java")
        val original = source.readText()
        val originalRecipe = project.resolve("rewrite.yaml").readText()
        listOf("1.0", "2.0-SNAPSHOT").forEach { version ->
            source.writeText(original)
            project.resolve("rewrite.yaml").writeText(originalRecipe)
            val configuredUrl = if (version == "1.0") url else "https://invalid.example/repository"
            if (version == "1.0") {
                userSettings.writeText("<settings/>")
            } else {
                userSettings.writeText(
                    """
                    <settings><mirrors><mirror><id>private-mirror</id><mirrorOf>*,!central</mirrorOf><url>$url</url></mirror></mirrors>
                    <servers><server><id>private-mirror</id><username>repo-user</username><password>repo-password</password></server></servers></settings>
                    """.trimIndent()
                )
            }
            val builder = RewriteRunner.builder()
                .projectDir(project)
                .activeRecipe(PluginScenarios.mavenSingleFile.activeRecipe)
                .recipeArtifact("$group:private-recipe:$version")
                .artifactRepository(RepositoryConfig(configuredUrl, "repo-user", "repo-password"))
                .logger(logger)
                .includeMavenCentral(false)
                .cacheDir(project.resolve("runner-cache"))
                .executorJvmArgs(listOf("-Xmx512m"))
                .rewriteConfig(project.resolve("rewrite.yaml"))
            val preview = builder.dryRun(true).build().run()
            assertEquals(
                UsedExecutionStage.PLUGIN,
                preview.executionDiagnostics.stageUsed,
                logs.joinToString("\n")
            )
            assertTrue(preview.rawDiffs.isNotEmpty())
            assertEquals(original, source.readText())
            val result = builder.dryRun(false).build().run()
            assertEquals(
                UsedExecutionStage.PLUGIN,
                result.executionDiagnostics.stageUsed,
                logs.joinToString("\n")
            )
            assertTrue(
                source.readText() != original,
                "Apply left source unchanged: ${logs.joinToString("\n")}"
            )
        }
        assertTrue(
            logs.none { "repo-user" in it || "repo-password" in it },
            "Runner logs leaked credentials"
        )
        assertTrue(
            project.resolve("repository-plugin-ran").exists(),
            "Custom plugin did not execute: ${logs.joinToString("\n")}"
        )
        listOf(
            "private-recipe/1.0/",
            "private-recipe/2.0-SNAPSHOT/",
            "private-plugin/1.0/",
            "private-dependency/1.0/"
        ).forEach { artifact ->
            assertTrue(
                requests.any {
                    artifact in it && it.endsWith(".jar")
                },
                "Maven did not download $artifact"
            )
        }
        // A second invocation must recognize the first invocation's cached releases. The
        // random profile may change, but Maven's _remote.repositories source IDs must not.
        val onlineWrapper = wrapper.readText()
        wrapper.writeText(onlineWrapper.replace("\"\$@\"", "--offline \"\$@\""))
        userSettings.writeText("<settings/>")
        source.writeText(original)
        project.resolve("rewrite.yaml").writeText(originalRecipe)
        val requestsBeforeOffline = requests.size
        val cached = RewriteRunner.builder()
            .projectDir(project)
            .activeRecipe(PluginScenarios.mavenSingleFile.activeRecipe)
            .recipeArtifact("$group:private-recipe:1.0")
            .artifactRepository(RepositoryConfig(url, "repo-user", "repo-password"))
            .includeMavenCentral(false)
            .executorJvmArgs(listOf("-Xmx512m"))
            .logger(logger)
            .dryRun(true)
            .rewriteConfig(project.resolve("rewrite.yaml"))
            .build().run()
        assertEquals(
            UsedExecutionStage.PLUGIN,
            cached.executionDiagnostics.stageUsed,
            logs.joinToString("\n")
        )
        assertTrue(cached.rawDiffs.isNotEmpty())
        assertEquals(original, source.readText())
        assertEquals(
            requestsBeforeOffline,
            requests.size,
            "Cached releases caused another repository request"
        )
        wrapper.writeText(onlineWrapper)
        // Maven swallows EventSpy exceptions. Verify the lifecycle guard aborts before
        // any build goal when repository transport cannot be read, including on apply.
        val extension = project.resolve("guard-test-extension.jar")
        requireNotNull(
            Thread.currentThread().contextClassLoader.getResourceAsStream(
                "maven-repositories-extension.jar"
            )
        )
            .use { input -> Files.newOutputStream(extension).use { input.copyTo(it) } }
        val invalidConfig = project.resolve("invalid-repositories.properties")
        invalidConfig.writeText("prefix=guard-test\ncount=invalid\n")
        Files.deleteIfExists(project.resolve("repositories-ready"))
        Files.deleteIfExists(project.resolve("repository-plugin-ran"))
        val process = ProcessBuilder(
            wrapper.toString(),
            "-Dmaven.ext.class.path=$extension",
            "-Drewrite.runner.repositories=$invalidConfig",
            "validate"
        ).directory(project.toFile()).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertTrue(process.waitFor() != 0, "Maven accepted broken repository transport")
        assertTrue(output.contains("Maven repository integration did not initialize"), output)
        assertFalse(project.resolve("repository-plugin-ran").exists())
    } finally {
        server.stop(0)
        project.toFile().deleteRecursively()
    }
}
