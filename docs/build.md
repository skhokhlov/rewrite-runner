# Build & Dependencies

## buildSrc Convention Plugins

Located in `buildSrc/src/main/kotlin/`. Applied via `plugins { id("...") }` in submodule build files.

| Plugin | Applied to | What it does |
|--------|-----------|--------------|
| `kotlin-convention` | `core`, `cli` | Kotlin JVM 21 toolchain, JUnit platform, `-Xmx2g` for tests, ktlint tasks (`ktlintCheck`/`ktlintFormat`), JaCoCo XML+HTML reports auto-run after test |
| `publishing-convention` | `core`, `cli` | `maven-publish`, optional signing, Dokka sources/javadoc JARs, POM template. In `cli`, the shadow `-all` variant is skipped from the publication so the fat JAR never goes to Maven Central |
| `dokka-convention` | `core` | Dokka HTML docs generation |
| `sbom-convention` | `core`, `cli` | CycloneDX 1.6 JSON generation and schema/content verification. Each Maven publication includes a `cyclonedx` classifier artifact |

`ktlintCheck` is wired into the standard `check` lifecycle task — `./gradlew check` always enforces style.

## Key Dependency Versions

Chosen for **Gradle 9.x + JDK 25 compatibility**:

| Dependency | Version | Note |
|------------|---------|------|
| Kotlin | `2.3.21` | 2.1.x crashes on JDK 25 |
| Shadow plugin | `com.gradleup.shadow:9.4.1` | `com.github.johnrengelman.shadow` incompatible with Gradle 9 |
| Maven Resolver | `2.0.18` | |
| JVM toolchain | `21` | set via `kotlin { jvmToolchain(21) }` in `kotlin-convention` |
| OpenRewrite | via `rewrite-recipe-bom:3.31.0`; plugin-first path uses Gradle plugin `7.32.1` and Maven plugin `6.40.0` | |
| Picocli | `4.7.7` | |
| Jackson | `3.1.3` | |
| Apache Maven Model | `3.9.16` | |
| Logback | `1.5.32` | `cli`: `implementation`; `core`: `testImplementation` |
| ktlint | `1.8.0` | Google Android code style |
| CycloneDX Gradle plugin | `3.3.0` | Generates and validates CycloneDX 1.6 JSON SBOMs for published modules |

## Build Artifacts

| Artifact | Description |
|----------|-------------|
| `core/build/libs/core-1.0-SNAPSHOT.jar` | Library JAR (no embedded deps) |
| `core/build/libs/core-1.0-SNAPSHOT-sources.jar` | Sources JAR |
| `cli/build/libs/cli-1.0-SNAPSHOT-all.jar` | Fat JAR for CLI use |
| `core/build/reports/cyclonedx/bom.json` | CycloneDX 1.6 SBOM, published as `core-<version>-cyclonedx.json` |
| `cli/build/reports/cyclonedx/bom.json` | CycloneDX 1.6 SBOM, published as `cli-<version>-cyclonedx.json` |

## Useful Build Commands

```bash
./gradlew shadowJar          # Build fat JAR → cli/build/libs/cli-1.0-SNAPSHOT-all.jar
./gradlew test               # Run all tests
./gradlew check              # Run tests + ktlintCheck
./gradlew ktlintCheck        # Lint only
./gradlew ktlintFormat       # Auto-fix lint issues
./gradlew jacocoTestReport   # Generate coverage report (auto-runs after test)
./gradlew verifyCyclonedxBom # Generate and validate both published-module SBOMs
```

## CI/CD

### Build workflow (`.github/workflows/build.yml`)
Triggers on push/PR to `main`/`master`. Offline checks gate the Windows worker and live-plugin
jobs; container acceptance follows the live-plugin lane.

**`unit` job** (offline checks):
1. Set up JDK 21 (Temurin)
2. `./gradlew check shadowJar` — unit tests and lint plus `:cli:testIntegration`. The integration
   task includes per-language tests, fake-wrapper Stage 0 coverage, and the real nested-Gradle
   fallback-attribution fixture. Nested Gradle reuses the current distribution and runs offline.
3. Fat JAR is produced as a build artifact of the same command

**`windows-worker` job** (needs `unit`):
1. Set up JDK 21 (Temurin)
2. Run the focused core worker protocol/path tests and the fat-JAR distribution test on Windows.

**`plugin-real` job** (real-plugin lane, needs `unit`):
1. Set up JDK 21 (Temurin)
2. Cache `~/.m2/repository` and `cli/build/test-cache/toolchains/` (the toolchain cache key embeds `hashFiles('gradle/wrapper/gradle-wrapper.properties')` so bumping the wrapper auto-evicts the stale Gradle distribution)
3. `./gradlew :cli:testRealPlugin --info` — runs only `PluginRealExecutionIntegrationTest` against the live OpenRewrite Maven/Gradle plugins
4. Timeout: 25 minutes (covers first-run downloads from Maven Central)

**`container` job** (needs `plugin-real`):
1. Set up JDK 21 (Temurin)
2. Run the release fat JAR under a real 2 GiB Docker cgroup.

Because the external-environment jobs depend on earlier evidence, an offline regression never
spends CI minutes downloading plugin toolchains or starting container acceptance.

### Publish workflow (`.github/workflows/publish.yml`)
Triggers on `v*` tags. Publishes `core` and the `cli` thin JAR (with sources, javadoc, and
CycloneDX JSON SBOM classifier artifacts) to Maven Central, then builds the `cli` `-all` fat JAR and uploads it as a GitHub Release asset
(`rewrite-runner-<version>-all.jar`) — the fat JAR is too large for Central. See
[`adr/0009-cli-fatjar-distribution.md`](adr/0009-cli-fatjar-distribution.md).

## Known Issues

- `WARNING: sun.misc.Unsafe::objectFieldOffset` from Kotlin compiler — benign, caused by fat JAR
- Stage 2 (`DependencyResolutionStage`) always fails on **this project itself** (Gradle project with no Maven local repo configured) — falls through to Stage 3 as expected
- Recipe JAR scanning logs are verbose at INFO level — expected behavior
