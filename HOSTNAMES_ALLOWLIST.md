# Required Hostnames for Build & Tests - Firewall Allowlist

This document lists all external hostnames that the **rewrite-runner** project requires access to in order to run the build (`./gradlew check shadowJar`) and tests (`./gradlew test`).

## Summary for Firewall Configuration

Add these hostnames to your proxy firewall allowlist (in order of criticality):

### CRITICAL - Required for Build & Tests
```
services.gradle.org
repo1.maven.org
repo.maven.apache.org
plugins.gradle.org
maven.jetbrains.com
```

### REQUIRED - Maven Central Mirror/Alternative
```
repo.gradle.org
```

### CONDITIONAL - Only for Publishing (`./gradlew publish`)
```
oss.sonatype.org
s01.oss.sonatype.org
central.sonatype.com
```

### CONDITIONAL - Documentation Generation (Dokka, `./gradlew dokkaGenerate`)
```
github.com
docs.oracle.com
kotlin.org
shields.io
central.sonatype.com
```

### CONDITIONAL - OpenRewrite Recipes (when running recipes)
```
repo.openrewrite.org
```

### OPTIONAL - CI/CD & Status Badges
```
api.github.com
img.shields.io
```

---

## Detailed Breakdown by Use Case

### 1. **Gradle Wrapper** (CRITICAL)
Used by: `./gradlew` bootstrap
- **services.gradle.org** - Downloads Gradle 9.4.0 distribution ZIP

### 2. **Maven Central & Plugin Repositories** (CRITICAL)
Used by: Dependency resolution, build plugins
- **repo1.maven.org** - Primary Maven Central mirror (Maven Resolver preferred)
- **repo.maven.apache.org** - Alternative Maven Central mirror
- **plugins.gradle.org** - Gradle Plugin Portal (Maven interface) - for:
  - `kotlin("jvm")` plugin (v2.3.10)
  - `com.gradleup.shadow` plugin (v9.3.2)
  - `org.jetbrains.dokka` plugin (v2.1.0)
  - `com.vanniktech.maven.publish` plugin (v0.36.0)
- **repo.gradle.org** - Gradle plugin repository (alternative mirror)

### 3. **JetBrains Repositories** (CRITICAL)
Used by: Kotlin compiler, Dokka documentation generation
- **maven.jetbrains.com** - JetBrains libraries and plugins, including:
  - Kotlin stdlib (`kotlin("jvm")` v2.3.10)
  - Dokka plugins (v2.1.0, v2.1.0-javadoc)
  - Kotlin reflection libraries

### 4. **Project Dependencies from Maven Central** (CRITICAL)
Used by: Core library classpath (transitively resolved)

Key dependencies requiring Maven Central access:
- OpenRewrite BOM & modules (org.openrewrite:*)
- Apache Maven Resolver (org.apache.maven.resolver:*)
- Jackson (tools.jackson.*)
- Picocli (info.picocli:*)
- Logback (ch.qos.logback:*)
- Kotest (io.kotest:*)
- Kotlin stdlib
- JUnit Platform

### 5. **Sonatype / Maven Central Publishing** (PUBLISHING ONLY)
Used by: `./gradlew publishToMavenCentral` (requires credentials)

Note: **NOT needed for build/test**, only for publishing releases.

- **oss.sonatype.org** - Legacy Sonatype OSS staging repository
- **s01.oss.sonatype.org** - Current Sonatype S01 instance (preferred)
  - Used for: Maven artifact staging, signing, and release
- **central.sonatype.com** - Sonatype Central repository search/metadata
  - Used for: Artifact queries, Maven metadata

### 6. **Documentation Generation** (CONDITIONAL)
Used by: `./gradlew dokkaGenerate` and `./gradlew check`

- **github.com** - Source code links in generated Javadoc (remoteUrl references)
- **docs.oracle.com** - Java/JDK documentation external links
  - Referenced by: Dokka's `enableJdkDocumentationLink` setting
- **kotlin.org** - Kotlin stdlib documentation links
  - Referenced by: Dokka's `enableKotlinStdLibDocumentationLink` setting
- **shields.io** - Status badges in README (optional, for CI display)
- **central.sonatype.com** - Maven Central badge metadata (optional)

### 7. **OpenRewrite Recipes** (CONDITIONAL)
Used by: When running actual OpenRewrite recipes (after build)

- **repo.openrewrite.org** - OpenRewrite public recipe repository
  - Note: Only accessed when executing recipes, not during build/test
  - Not strictly required for build/test phases
  - May be needed for actual recipe execution

### 8. **GitHub & CI/CD** (OPTIONAL - CI ONLY)
Used by: GitHub Actions workflows, status checks

- **api.github.com** - GitHub REST API for:
  - Release checks
  - PR status checks
  - Action environment setup
- **github.com** - Repository cloning, action downloads
  - Referenced in workflows: `actions/checkout@v6`, `gradle/actions/setup-gradle@v5`

---

## Network Requirements by Build Phase

### Phase 1: Gradle Wrapper Download (First run only)
```
services.gradle.org
```

### Phase 2: Dependency Resolution
```
repo1.maven.org (or repo.maven.apache.org as fallback)
plugins.gradle.org (for Gradle plugins)
maven.jetbrains.com (for Kotlin plugin)
<all dependency origins from Maven Central>
```

### Phase 3: Build & Compilation
```
maven.jetbrains.com (Kotlin compiler)
repo1.maven.org (transitive JARs)
```

### Phase 4: Tests (`./gradlew test`)
```
<same as Phase 2-3>
All dependencies required by test classes (JUnit, Kotest, etc.)
```

### Phase 5: ktlint Code Style Check (`./gradlew check`)
```
repo1.maven.org (for ktlint-cli:1.8.0)
```

### Phase 6: Documentation (Optional) (`./gradlew dokkaGenerate`)
```
github.com (source links)
docs.oracle.com (JDK documentation)
kotlin.org (Kotlin stdlib docs)
repo1.maven.org (Dokka dependencies)
```

### Phase 7: Publishing (Optional) (`./gradlew publishToMavenCentral`)
```
s01.oss.sonatype.org (or oss.sonatype.org)
central.sonatype.com
repo1.maven.org (for plugin/library dependencies)
```

---

## Caching & Performance Considerations

### Local Gradle Build Cache
- Caching: Enabled in `gradle.properties` (`org.gradle.caching=true`)
- First run: Full network access required
- Subsequent runs: Cache significantly reduces network requests
- Gradle processes: Parallelized in `gradle.properties` (`org.gradle.parallel=true`)

### Maven Central Retry Policy
From `dependency-resolution.md`:
- Update check: Once per day (`UPDATE_POLICY_DAILY`)
- Checksum validation: Ignored for corporate proxy compatibility
- Retry: Automatic on transient network failures
- Timeout: 30s connection, 60s request

### Gradle Wrapper Caching
- Downloaded to: `~/.gradle/wrapper/dists/`
- No re-download on subsequent runs

### JAR Caching
- Recipe artifacts: `~/.rewriterunner/cache/repository/`
- Project dependencies: `~/.m2/repository/` (Maven default)
- Gradle dependencies: `~/.gradle/caches/`

---

## All Hostnames at a Glance

| Hostname | Purpose | Required For | Port |
|----------|---------|--------------|------|
| services.gradle.org | Gradle wrapper distribution | Build bootstrap | 443 (HTTPS) |
| repo1.maven.org | Maven Central primary mirror | Dependency resolution | 443 (HTTPS) |
| repo.maven.apache.org | Maven Central alternative | Dependency resolution (fallback) | 443 (HTTPS) |
| plugins.gradle.org | Gradle Plugin Portal | Plugin resolution | 443 (HTTPS) |
| repo.gradle.org | Gradle plugins mirror | Plugin resolution (alternative) | 443 (HTTPS) |
| maven.jetbrains.com | JetBrains Kotlin/Dokka plugins | Kotlin, Dokka | 443 (HTTPS) |
| oss.sonatype.org | Sonatype OSS repository (legacy) | Publishing only | 443 (HTTPS) |
| s01.oss.sonatype.org | Sonatype S01 repository (current) | Publishing only | 443 (HTTPS) |
| central.sonatype.com | Sonatype Central search API | Publishing, docs badges | 443 (HTTPS) |
| github.com | GitHub repository, source links | Dokka doc links | 443 (HTTPS) |
| docs.oracle.com | Oracle/Java documentation | Dokka external links | 443 (HTTPS) |
| kotlin.org | Kotlin documentation | Dokka external links | 443 (HTTPS) |
| shields.io | Status badge generation | README badges (optional) | 443 (HTTPS) |
| api.github.com | GitHub API | CI/CD, status checks (optional) | 443 (HTTPS) |
| repo.openrewrite.org | OpenRewrite recipes | Recipe execution (optional) | 443 (HTTPS) |

---

## Notes for Firewall Configuration

1. **HTTPS Only**: All connections use HTTPS (port 443). HTTP (port 80) is not required.

2. **DNS Resolution**: Ensure DNS queries can resolve these hostnames (port 53, TCP/UDP).

3. **No Outbound SSH**: The project uses HTTPS only; no git over SSH (port 22) is required for build.

4. **No Proxy Authentication Issues**: Gradle and Maven Resolver are configured to:
   - Ignore checksums on corporate proxies
   - Skip redundant rechecks within a session
   - Use standard HTTP/HTTPS proxy detection (via `http.proxyHost`, `https.proxyHost`)

5. **Repository Authentication**: If using a corporate repository mirror (e.g., Nexus), configure via:
   - `~/.gradle/gradle.properties` (Gradle projects)
   - `~/.m2/settings.xml` (Maven)
   - Environment variables: `ORG_GRADLE_PROJECT_*` (for CI/CD)

6. **First-Run Download**: The first build will download Gradle (~150 MB), then all project dependencies (~500+ MB depending on OpenRewrite modules). Subsequent builds are much faster due to caching.

7. **Test Dependencies**: Tests pull additional libraries (JUnit, Kotest) from Maven Central; these are small and cached after the first run.

8. **Git Operations**: The `git describe` command in the build (version detection) requires local `.git` access only; no network needed.

---

## Minimum Allowlist for Build & Tests Only

If you want the **smallest firewall allowlist** for just building and running tests (excluding publishing, CI/CD, and documentation):

```
services.gradle.org
repo1.maven.org
plugins.gradle.org
maven.jetbrains.com
```

This covers:
- ✅ Gradle wrapper download
- ✅ Build plugins
- ✅ Kotlin compiler
- ✅ All project dependencies
- ✅ Tests (JUnit, Kotest, etc.)
- ✅ ktlint code style check

**NOT included** (not needed for basic build/test):
- ❌ Sonatype (publishing)
- ❌ GitHub (doc links)
- ❌ Shields.io (badges)
- ❌ OpenRewrite recipes repo (execution only)

---

## Example Proxy Configuration

If using a corporate proxy, add to `~/.gradle/gradle.properties`:

```properties
# HTTP Proxy
systemProp.http.proxyHost=proxy.example.com
systemProp.http.proxyPort=3128

# HTTPS Proxy
systemProp.https.proxyHost=proxy.example.com
systemProp.https.proxyPort=3128

# Proxy credentials (if required)
systemProp.http.proxyUser=username
systemProp.http.proxyPassword=password
```

And to `~/.m2/settings.xml`:

```xml
<proxies>
  <proxy>
    <id>example-proxy</id>
    <active>true</active>
    <protocol>https</protocol>
    <host>proxy.example.com</host>
    <port>3128</port>
  </proxy>
</proxies>
```
