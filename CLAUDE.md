# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Test Commands

```bash
# Build fat JAR
./gradlew shadowJar
# Output: build/libs/openrewrite-runner-1.0-SNAPSHOT-all.jar

# Run all tests
./gradlew test

# Run a single test class
./gradlew test --tests "org.example.output.ResultFormatterTest"

# Run a single test method (use backtick-quoted name for Kotlin)
./gradlew test --tests "org.example.cli.RunCommandTest.default output mode is diff"

# Run the tool locally
java -jar build/libs/openrewrite-runner-1.0-SNAPSHOT-all.jar --help
```

## Architecture

The tool is a fat JAR CLI that runs OpenRewrite recipes against arbitrary project directories without requiring a working build system.

**Entry point**: `Main.kt` → `CommandLine(RunCommand()).execute(*args)`. `RunCommand` is the **root command** (not a subcommand) — all options are passed directly without a "run" prefix.

**Execution pipeline** (orchestrated by `OpenRewriteRunner.run()`, delegated to by `RunCommand.call()`):
1. Load tool config (`ToolConfig`)
2. Resolve recipe JARs from Maven coordinates (`RecipeArtifactResolver`)
3. Load recipe from JARs + optional `rewrite.yaml` (`RecipeLoader`)
4. Build LST — parse source files into OpenRewrite's tree representation (`LstBuilder`)
5. Run the recipe (`RecipeRunner`)
6. Optionally write changes to disk (unless `--dry-run` / `dryRun = true`)
7. Return `RunResult`; `RunCommand.call()` then formats output via `ResultFormatter`

## Library API

**`OpenRewriteRunner`** (`org.example`) — programmatic entry point; use `OpenRewriteRunner.builder()` to configure and `.build().run()` to execute. Returns a `RunResult`.

**`RunResult`** (`org.example`) — holds `results: List<Result>`, `changedFiles: List<Path>`, `projectDir: Path`, and convenience properties `hasChanges`, `changeCount`.

**`RunCommand.call()`** now delegates entirely to `OpenRewriteRunner`, then passes `runResult.results` to `ResultFormatter` for CLI output. All orchestration logic lives in `OpenRewriteRunner.run()`.

**Builder options** mirror CLI flags 1:1 (see `OpenRewriteRunner.Builder` KDoc and README Library Usage section for the full table).

**KotlinDoc** is present on all public classes and methods in: `OpenRewriteRunner`, `RunResult`, `RecipeArtifactResolver`, `RecipeLoader`, `RecipeRunner`, `LstBuilder`, `ToolConfig`, `ParseConfig`, `RepositoryConfig`, `ResultFormatter`, `OutputMode`.

**Build artifacts**:
- `core/build/libs/core-1.0-SNAPSHOT.jar` — library JAR (no embedded deps)
- `core/build/libs/core-1.0-SNAPSHOT-sources.jar` — sources JAR
- `cli/build/libs/cli-1.0-SNAPSHOT-all.jar` — fat JAR for CLI use

**3-stage LST building** (`lst/` package):
- **Stage 1** (`BuildToolStage`): Subprocess Maven/Gradle to extract compile classpath. Falls through on failure.
- **Stage 2** (`DependencyResolutionStage`): Parse `pom.xml`/`build.gradle` + Maven Resolver to download JARs. Falls through on failure.
- **Stage 3** (`DirectParseStage`): Scan `~/.m2` and `~/.gradle/caches` for already-cached JARs matching declared deps. Always succeeds (possibly with empty list).

`LstBuilder` routes files by extension to the correct OpenRewrite parser. Excludes `build/`, `target/`, `node_modules/`, `.git/`, `.gradle/`, `.idea/`, `out/`, `dist/` by default.

**Supported file types and parsers**:
- `.java` → `JavaParser` (with full classpath)
- `.kt` → `KotlinParser`
- `.groovy` → `GroovyParser`
- `.yaml` / `.yml` → `YamlParser`
- `.json` → `JsonParser`
- `.xml` → `XmlParser`
- `.properties` → `PropertiesParser`

**Key dependency versions** (chosen for Gradle 9.0.0 + JDK 25 compatibility):
- Kotlin: `2.3.0` (2.1.x crashes on JDK 25)
- Shadow plugin: `com.gradleup.shadow:9.0.0` (`com.github.johnrengelman.shadow` is incompatible with Gradle 9)
- Maven Resolver: `1.9.22` (version 2.x doesn't exist yet)
- JVM toolchain: 21 (set via `kotlin { jvmToolchain(21) }`)
- OpenRewrite: via `rewrite-recipe-bom:3.10.1`
- Picocli: `4.7.6`
- Jackson: `2.18.2`
- Apache Maven Model: `3.9.12`

## CLI Options (`RunCommand`)

| Flag | Short | Description | Default |
|------|-------|-------------|---------|
| `--project-dir` | `-p` | Project root directory | `.` |
| `--active-recipe` | `-r` | Recipe name to run (required) | — |
| `--recipe-artifact` | | Maven coordinates (repeatable) | — |
| `--rewrite-config` | | Path to custom `rewrite.yaml` | — |
| `--output` | `-o` | Output mode: `diff`, `files`, `report` | `diff` |
| `--cache-dir` | | JAR cache directory | — |
| `--config` | | Tool config file path | — |
| `--dry-run` | | Run without writing to disk | false |
| `--include-extensions` | | Comma-separated file types to parse | — |
| `--exclude-extensions` | | Comma-separated file types to skip | — |

**Output modes**:
- `diff` (default): Unified diffs for all changed files
- `files`: One changed file path per line, no diff markers
- `report`: Writes structured JSON to `openrewrite-report.json`

## Directory Structure

The project is split into two Gradle submodules:

```
core/src/
├── main/kotlin/org/example/
│   ├── OpenRewriteRunner.kt            # Library facade — builder API, orchestrates the full pipeline
│   ├── RunResult.kt                    # Return type for OpenRewriteRunner.run()
│   ├── config/ToolConfig.kt            # YAML config + env var interpolation
│   ├── lst/
│   │   ├── LstBuilder.kt               # Orchestrates 3-stage pipeline + multi-language parsing
│   │   ├── BuildToolStage.kt           # Stage 1: Maven/Gradle subprocess
│   │   ├── DependencyResolutionStage.kt # Stage 2: Maven Resolver download
│   │   └── DirectParseStage.kt         # Stage 3: Local cache scan
│   ├── output/ResultFormatter.kt       # diff/files/report output modes
│   └── recipe/
│       ├── RecipeArtifactResolver.kt   # Resolve Maven coordinates to JARs
│       ├── RecipeLoader.kt             # Load + activate recipe by name
│       └── RecipeRunner.kt             # Execute recipe, return Results
│
└── test/kotlin/org/example/
    ├── config/ToolConfigTest.kt
    ├── lst/
    │   ├── LstBuilderTest.kt
    │   ├── BuildToolStageTest.kt
    │   ├── DependencyResolutionStageTest.kt
    │   ├── DirectParseStageTest.kt
    │   └── JavaVersionDetectionTest.kt
    └── output/ResultFormatterTest.kt

cli/src/
├── main/kotlin/org/example/
│   ├── Main.kt                         # Entry point
│   └── cli/RunCommand.kt               # Picocli command (root, not subcommand); delegates to OpenRewriteRunner
│
└── test/kotlin/org/example/
    ├── cli/RunCommandTest.kt
    └── integration/
        ├── BaseIntegrationTest.kt      # runCli() helper, temp dir setup
        ├── JavaProjectIntegrationTest.kt
        ├── KotlinProjectIntegrationTest.kt
        ├── YamlProjectIntegrationTest.kt
        ├── JsonProjectIntegrationTest.kt
        ├── XmlProjectIntegrationTest.kt
        ├── PropertiesProjectIntegrationTest.kt
        └── MultiLanguageProjectIntegrationTest.kt
```

## Important Implementation Notes

- `InMemoryLargeSourceSet` is in `org.openrewrite.internal` (not the top-level package)
- `BuildToolStage` and `DependencyResolutionStage` are `open` classes with `open` methods to allow test subclassing
- `DependencyResolutionStage.parseMavenDependencies` and `parseGradleDependencies` are `internal` for direct test access
- `ResultFormatter` has a secondary constructor accepting `PrintWriter` for picocli integration; `RunCommand` uses `@Spec` to get picocli's output `PrintWriter` and passes it to `ResultFormatter`
- Creating OpenRewrite `Result` objects in tests **requires** running through a real recipe pipeline (`Recipe.run()` with `PlainTextVisitor`) — calling `PlainText.withText()` outside a visitor context throws `UnknownSourceFileChangeException`
- Java version detection in `LstBuilder` reads `<source>`/`<target>`/`<release>` from `pom.xml` and `jvmToolchain()`/`sourceCompatibility` from `build.gradle`
- `LstBuilder` adds project class directories (`target/classes`, `build/classes/java/main`, etc.) to the classpath for cross-module type resolution
- Extension filtering: CLI flags (`--include-extensions`, `--exclude-extensions`) take precedence over config file settings
- `ToolConfig` supports environment variable interpolation (`${VAR_NAME}`) and tilde expansion in paths at load time

## Testing Conventions

- Use `@TempDir` (JUnit 5) for temporary directories in tests
- Use `kotlin.test` assertions: `assertEquals`, `assertTrue`, `assertFalse`, `assertNull`, `assertNotEquals`
- Integration tests use `BaseIntegrationTest.runCli()` to capture stdout/stderr and exit code
- Subclass `BuildToolStage`/`DependencyResolutionStage` in tests to override behavior without mocks
- Some integration tests accept "success path OR expected fallback" to handle environment variability (e.g., no Maven installed in CI)

## CI/CD

GitHub Actions workflow (`.github/workflows/build.yml`) triggers on push/PR to `main`/`master`:
1. Sets up JDK 21 (Temurin)
2. Runs `./gradlew test shadowJar`
3. Uploads fat JAR as a build artifact
