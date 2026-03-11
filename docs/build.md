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
| OpenRewrite | via `rewrite-recipe-bom:3.10.1` | |
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
Triggers on push/PR to `main`/`master`:
1. Set up JDK 21 (Temurin)
2. `./gradlew check shadowJar` (`check` = `ktlintCheck` + `test`)
3. Upload fat JAR as build artifact

### Publish workflow (`.github/workflows/publish.yml`)
Triggers on `v*` tags — publishes `core` to Maven Central.

## Known Issues

- `WARNING: sun.misc.Unsafe::objectFieldOffset` from Kotlin compiler — benign, caused by fat JAR
- Stage 2 (`DependencyResolutionStage`) always fails on **this project itself** (Gradle project with no Maven local repo configured) — falls through to Stage 3 as expected
- Recipe JAR scanning logs are verbose at INFO level — expected behavior
