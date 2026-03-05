package org.example.lst

import org.apache.maven.model.io.xpp3.MavenXpp3Reader
import org.apache.maven.repository.internal.MavenRepositorySystemUtils
import org.eclipse.aether.RepositorySystem
import org.eclipse.aether.RepositorySystemSession
import org.eclipse.aether.artifact.DefaultArtifact
import org.eclipse.aether.collection.CollectRequest
import org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory
import org.eclipse.aether.graph.Dependency
import org.eclipse.aether.repository.LocalRepository
import org.eclipse.aether.repository.RemoteRepository
import org.eclipse.aether.resolution.DependencyRequest
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory
import org.eclipse.aether.spi.connector.transport.TransporterFactory
import org.eclipse.aether.transport.http.HttpTransporterFactory
import org.example.config.RepositoryConfig
import java.nio.file.Path
import java.util.logging.Logger
import kotlin.io.path.exists

/**
 * Stage 2: Parse the project's build descriptor and resolve declared dependencies
 * via Maven Resolver, without invoking the project's own build tool.
 */
open class DependencyResolutionStage(
    private val cacheDir: Path,
    private val extraRepositories: List<RepositoryConfig> = emptyList(),
) {
    private val log = Logger.getLogger(DependencyResolutionStage::class.java.name)

    private val system: RepositorySystem by lazy { newRepositorySystem() }
    private val session: RepositorySystemSession by lazy { newSession(system) }
    private val remoteRepos: List<RemoteRepository> by lazy { buildRemoteRepos() }

    open fun resolveClasspath(projectDir: Path): List<Path> {
        val coordinates = when {
            projectDir.resolve("pom.xml").exists() -> parseMavenDependencies(projectDir)
            hasBuildGradle(projectDir) -> parseGradleDependencies(projectDir)
            else -> emptyList()
        }

        if (coordinates.isEmpty()) {
            log.info("No dependencies found in build descriptor")
            return emptyList()
        }

        log.info("Resolving ${coordinates.size} declared dependencies via Maven Resolver")
        val resolved = mutableListOf<Path>()
        val failed = mutableListOf<String>()

        for (coord in coordinates) {
            try {
                val paths = resolveSingle(coord)
                resolved.addAll(paths)
            } catch (e: Exception) {
                failed.add("$coord (${e.message})")
            }
        }

        if (failed.isNotEmpty()) {
            log.warning("Could not resolve ${failed.size} dependencies: ${failed.joinToString(", ")}")
        }

        return resolved
    }

    // ─── Maven pom.xml parsing ────────────────────────────────────────────────

    internal fun parseMavenDependencies(projectDir: Path): List<String> {
        val pomFile = projectDir.resolve("pom.xml")
        return try {
            val model = MavenXpp3Reader().read(pomFile.toFile().inputStream())
            model.dependencies
                .filter { !listOf("provided", "system").contains(it.scope) }
                .mapNotNull { dep ->
                    val version = dep.version?.takeIf { it.isNotBlank() && !it.startsWith("\${") }
                        ?: return@mapNotNull null
                    "${dep.groupId}:${dep.artifactId}:$version"
                }
        } catch (e: Exception) {
            log.warning("Failed to parse pom.xml: ${e.message}")
            emptyList()
        }
    }

    // ─── Gradle build file parsing (best-effort regex) ───────────────────────

    internal fun parseGradleDependencies(projectDir: Path): List<String> {
        val buildFile = projectDir.resolve("build.gradle.kts").takeIf { it.exists() }
            ?: projectDir.resolve("build.gradle").takeIf { it.exists() }
            ?: return emptyList()

        return try {
            val text = buildFile.toFile().readText()
            val coordinates = mutableListOf<String>()

            // Match quoted strings like "group:artifact:version"
            val coordPattern = Regex("""["']([a-zA-Z][\w.\-]+:[a-zA-Z][\w.\-]+:[\w.\-]+)["']""")
            coordPattern.findAll(text).forEach { match ->
                val coord = match.groupValues[1]
                // Skip BOM / platform entries and test dependencies
                if (!coord.contains("bom") && !coord.contains("platform")) {
                    coordinates.add(coord)
                }
            }

            // Match Kotlin DSL form: implementation("group", "artifact", "version")
            val threeArgPattern = Regex(
                """(?:implementation|api|compileOnly|runtimeOnly)\s*\(\s*["']([^"']+)["']\s*,\s*["']([^"']+)["']\s*,\s*["']([^"']+)["']\s*\)"""
            )
            threeArgPattern.findAll(text).forEach { match ->
                coordinates.add("${match.groupValues[1]}:${match.groupValues[2]}:${match.groupValues[3]}")
            }

            coordinates.distinct()
        } catch (e: Exception) {
            log.warning("Failed to parse Gradle build file: ${e.message}")
            emptyList()
        }
    }

    // ─── Maven Resolver ───────────────────────────────────────────────────────

    private fun resolveSingle(coordinate: String): List<Path> {
        val artifact = DefaultArtifact(coordinate)
        val dep = Dependency(artifact, "runtime")
        val collectRequest = CollectRequest(dep, remoteRepos)
        val depRequest = DependencyRequest(collectRequest, null)
        val result = system.resolveDependencies(session, depRequest)
        return result.artifactResults.mapNotNull { it.artifact?.file?.toPath() }
    }

    private fun buildRemoteRepos(): List<RemoteRepository> {
        val repos = mutableListOf(
            RemoteRepository.Builder("central", "default", "https://repo.maven.apache.org/maven2").build()
        )
        extraRepositories.forEach { cfg ->
            val builder = RemoteRepository.Builder(cfg.url.hashCode().toString(), "default", cfg.url)
            if (cfg.username != null && cfg.password != null) {
                builder.setAuthentication(
                    org.eclipse.aether.util.repository.AuthenticationBuilder()
                        .addUsername(cfg.username)
                        .addPassword(cfg.password)
                        .build()
                )
            }
            repos.add(builder.build())
        }
        return repos
    }

    @Suppress("DEPRECATION")
    private fun newRepositorySystem(): RepositorySystem {
        val locator = MavenRepositorySystemUtils.newServiceLocator()
        locator.addService(RepositoryConnectorFactory::class.java, BasicRepositoryConnectorFactory::class.java)
        locator.addService(TransporterFactory::class.java, HttpTransporterFactory::class.java)
        return locator.getService(RepositorySystem::class.java)
            ?: throw IllegalStateException("Could not create RepositorySystem")
    }

    private fun newSession(system: RepositorySystem): RepositorySystemSession {
        val session = MavenRepositorySystemUtils.newSession()
        val repoDir = cacheDir.resolve("repository").toFile().also { it.mkdirs() }
        session.localRepositoryManager = system.newLocalRepositoryManager(session, LocalRepository(repoDir))
        return session
    }

    private fun hasBuildGradle(dir: Path): Boolean =
        dir.resolve("build.gradle").exists() || dir.resolve("build.gradle.kts").exists()
}
