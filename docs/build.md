# Build & Dependencies

## buildSrc Convention Plugins

Located in `buildSrc/src/main/kotlin/`. Applied via `plugins { id("...") }` in submodule build files.

| Plugin | Applied to | What it does |
|--------|-----------|--------------|
| `kotlin-convention` | `core`, `cli` | Kotlin JVM 21 toolchain, JUnit platform, `-Xmx2g` for tests, ktlint tasks (`ktlintCheck`/`ktlintFormat`), JaCoCo XML+HTML reports auto-run after test |
| `publishing-convention` | `core` | `maven-publish`, optional signing, Dokka sources/javadoc JARs, POM template |
| `dokka-convention` | `core` | Dokka HTML docs generation |

`ktlintCheck` is wired into the standard `check` lifecycle task — `./gradlew check` always enforces style.

## Key Dependency Versions

Chosen for **Gradle 9.0.0 + JDK 25 compatibility**:

| Dependency | Version | Note |
|------------|---------|------|
| Kotlin | `2.3.0` | 2.1.x crashes on JDK 25 |
| Shadow plugin | `com.gradleup.shadow:9.0.0` | `com.github.johnrengelman.shadow` incompatible with Gradle 9 |
| Maven Resolver | `2.0.16` | |
| JVM toolchain | `21` | set via `kotlin { jvmToolchain(21) }` in `kotlin-convention` |
| OpenRewrite | via `rewrite-recipe-bom:3.28.0`; plugin-first path uses Gradle plugin `7.19.0` and Maven plugin `6.22.1` | |
| Picocli | `4.7.6` | |
| Jackson | `3.0.0` | |
| Apache Maven Model | `3.9.12` | |
| Logback | `1.5.32` | `cli`: `implementation`; `core`: `testImplementation` |
| ktlint | `1.8.0` | Google Android code style |

## Build Artifacts

| Artifact | Description |
|----------|-------------|
| `core/build/libs/core-1.0-SNAPSHOT.jar` | Library JAR (no embedded deps) |
| `core/build/libs/core-1.0-SNAPSHOT-sources.jar` | Sources JAR |
| `cli/build/libs/cli-1.0-SNAPSHOT-all.jar` | Fat JAR for CLI use |

## Useful Build Commands

```bash
./gradlew shadowJar          # Build fat JAR → cli/build/libs/cli-1.0-SNAPSHOT-all.jar
./gradlew test               # Run all tests
./gradlew check              # Run tests + ktlintCheck
./gradlew ktlintCheck        # Lint only
./gradlew ktlintFormat       # Auto-fix lint issues
./gradlew jacocoTestReport   # Generate coverage report (auto-runs after test)
```

## CI/CD

### Build workflow (`.github/workflows/build.yml`)
Triggers on push/PR to `main`/`master`. Three sequential jobs gate on each other so each failure surfaces at the right lane.

**`unit` job** (fast lane):
1. Set up JDK 21 (Temurin)
2. `./gradlew check shadowJar` (`check` = `ktlintCheck` + `test`; `test` excludes everything matching `*IntegrationTest`)
3. Fat JAR is produced as a build artifact of the same command

**`integration-fake` job** (offline integration lane, needs `unit`):
1. Set up JDK 21 (Temurin)
2. `./gradlew :cli:testIntegration` — runs every `*IntegrationTest` except `PluginRealExecutionIntegrationTest`, including the per-language LST integration tests and the fake-wrapper Stage 0 suite. No network calls; no toolchain downloads.

**`plugin-real` job** (real-plugin lane, needs `integration-fake`):
1. Set up JDK 21 (Temurin)
2. Cache `~/.m2/repository` and `cli/build/test-cache/toolchains/` (the toolchain cache key embeds `hashFiles('gradle/wrapper/gradle-wrapper.properties')` so bumping the wrapper auto-evicts the stale Gradle distribution)
3. `./gradlew :cli:testRealPlugin --info` — runs only `PluginRealExecutionIntegrationTest` against the live OpenRewrite Maven/Gradle plugins
4. Timeout: 25 minutes (covers first-run downloads from Maven Central)

Because the jobs chain via `needs:`, an early failure short-circuits the later lanes: a unit-test regression never spends CI minutes downloading Gradle/Maven distributions.

### Publish workflow (`.github/workflows/publish.yml`)
Triggers on `v*` tags — publishes `core` to Maven Central.

## Known Issues

- `WARNING: sun.misc.Unsafe::objectFieldOffset` from Kotlin compiler — benign, caused by fat JAR
- Stage 2 (`DependencyResolutionStage`) always fails on **this project itself** (Gradle project with no Maven local repo configured) — falls through to Stage 3 as expected
- Recipe JAR scanning logs are verbose at INFO level — expected behavior
