# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

**Deep-dive docs** (read these before working on the relevant area):
- [`docs/architecture.md`](docs/architecture.md) — execution pipeline, 4-stage LST, file routing, version detection, module layout
- [`docs/dependency-resolution.md`](docs/dependency-resolution.md) — how/why recipe vs project deps are resolved, Aether session settings, scope pruning, local repo strategy
- [`docs/library-api.md`](docs/library-api.md) — `RewriteRunner` builder, `RunResult`, `ToolConfig` YAML schema, exit codes
- [`docs/testing.md`](docs/testing.md) — TDD requirement, test patterns, gotchas, test file map
- [`docs/build.md`](docs/build.md) — `buildSrc` convention plugins, dependency versions, CI/CD, known issues

---

## Breaking changes in vNEXT

`--include-extensions` and `--exclude-extensions` have been removed; the `Builder.includeExtensions(...)`, `Builder.excludeExtensions(...)`, and the YAML `parse.includeExtensions` / `parse.excludeExtensions` keys are gone too. The new surface aligns with the upstream OpenRewrite Gradle/Maven plugins' native exclusion primitive:

| Before | After |
|--------|-------|
| `--exclude-extensions .md,.json` | `--exclude-paths "**/*.md,**/*.json"` |
| `--include-extensions .java` | (no replacement — exclusion is the only filter, matching upstream) |
| `Builder.includeExtensions(...)` / `Builder.excludeExtensions(...)` | (removed; use `Builder.excludePaths(...)`) |
| `parse.includeExtensions` / `parse.excludeExtensions` in YAML | (removed; use `parse.excludePaths`) |

`--exclude-paths` is forwarded both to the Stage 0 plugin invocation (Maven: `-Drewrite.exclusions=…`; Gradle: `exclusion(...)` lines in the generated init script) and to the LST fallback pipeline, so the two paths apply identical filtering. When the exclusion list eliminates every JVM source file from scope, classpath resolution stages 1–4 are skipped entirely.

---

## Quick Commands

```bash
./gradlew shadowJar          # Build fat JAR → cli/build/libs/cli-1.0-SNAPSHOT-all.jar
./gradlew test               # Run all tests
./gradlew check              # tests + ktlintCheck
./gradlew ktlintFormat       # Auto-fix formatting

# Run a single test class
./gradlew test --tests "io.github.skhokhlov.rewriterunner.output.ResultFormatterTest"

# Run a single test method (use backtick-quoted name for Kotlin)
./gradlew test --tests "io.github.skhokhlov.rewriterunner.cli.RunCommandTest.default output mode is diff"

# Run the tool locally
java -jar cli/build/libs/cli-1.0-SNAPSHOT-all.jar --help
```

## CLI Options (`RunCommand`)

| Flag | Short | Description | Default |
|------|-------|-------------|---------|
| `--project-dir` | `-p` | Project root directory | `.` |
| `--active-recipe` | `-r` | Recipe name to run (required) | — |
| `--recipe-artifact` | | Maven coordinates (repeatable) | — |
| `--rewrite-config` | | Path to custom `rewrite.yaml` | — |
| `--output` | `-o` | Output mode: `diff`, `files`, `report` | `diff` |
| `--cache-dir` | | JAR cache directory | `~/.rewriterunner/cache` |
| `--config` | | Path to `rewriterunner.yml` | `<projectDir>/rewriterunner.yml`, then `~/.rewriterunner/rewriterunner.yml` |
| `--dry-run` | | Run without writing to disk | `false` |
| `--skip-plugin-run` | | Skip official plugin-first execution and use the LST pipeline directly | `false` |
| `--subprocess-run-timeout` | | Build-tool subprocess timeout for the fallback LST pipeline | `120s` |
| `--plugin-run-timeout` | | Stage 0 Gradle/Maven plugin timeout | `10m` |
| `--resolver-connect-timeout` | | Maven Resolver TCP connection timeout | `30s` |
| `--resolver-request-timeout` | | Maven Resolver socket/request timeout | `60s` |
| `--exclude-paths` | | Comma-separated glob patterns of files to skip (e.g. `**/generated/**,**/*.md`); forwarded to Stage 0 plugin and LST fallback alike | — |
| `--info` | | Enable INFO-level logging to stderr | `false` |
| `--debug` | | Enable DEBUG-level logging (overrides `--info`) | `false` |
| `--no-maven-central` | | Disable Maven Central; use only repos from config | `false` |

**Output modes**: `diff` (unified diffs) · `files` (one path per line) · `report` (JSON to `openrewrite-report.json`)

## Directory Structure

```
buildSrc/src/main/kotlin/          # Convention plugins (kotlin-convention, publishing-convention, dokka-convention)
core/src/
├── main/kotlin/.../rewriterunner/
│   ├── RewriteRunner.kt            # Library facade — builder API, orchestrates the full pipeline
│   ├── RunResult.kt
│   ├── config/ToolConfig.kt        # YAML config + env var interpolation
│   ├── plugin/                     # Stage 0 official Gradle/Maven plugin execution + patch parsing
│   ├── lst/
│   │   ├── LstBuilder.kt           # Orchestrates 4-stage pipeline + multi-language parsing
│   │   ├── ProjectBuildStage.kt       # Stage 1: Maven/Gradle subprocess (build-classpath/init-script)
│   │   ├── DependencyResolutionStage.kt  # Stage 2: mvn dependency:tree / gradle dependencies subprocess
│   │   ├── BuildFileParseStage.kt      # Stage 3: Static build file parse + POM traversal
│   │   ├── LocalRepositoryStage.kt     # Stage 4: Local cache scan
│   │   └── utils/
│   │       ├── FileCollector.kt        # NIO walk, excluded-dir filtering, glob exclusions, extension resolution
│   │       ├── VersionDetector.kt      # Java/Kotlin JVM-version walk-up + parseGradleVersionFromWrapper
│   │       ├── GradleDslClasspathResolver.kt  # Locate Gradle installation for DSL classpath
│   │       ├── MarkerFactory.kt        # BuildTool, GitProvenance, OperatingSystem, GradleProject markers
│   │       └── StaticBuildFileParser.kt      # Shared static parser for pom.xml, build.gradle, version catalogs
│   ├── output/ResultFormatter.kt   # diff/files/report output modes
│   └── recipe/
│       ├── RecipeArtifactResolver.kt
│       ├── RecipeLoader.kt
│       └── RecipeRunner.kt
└── test/kotlin/.../rewriterunner/
    ├── config/ToolConfigTest.kt
    ├── lst/                        # LstBuilderTest, FileCollectorTest, stage tests, version detection tests
    └── output/ResultFormatterTest.kt

cli/src/
├── main/kotlin/.../
│   ├── Main.kt                     # Entry point; setLogLevel() adjusts Logback at runtime
│   └── cli/RunCommand.kt           # Picocli root command; delegates to RewriteRunner
└── test/kotlin/.../
    ├── cli/RunCommandTest.kt
    └── integration/                # BaseIntegrationTest + per-language integration tests
```

## Development Approach

**TDD is required**: write a failing test first, then implement. See [`docs/testing.md`](docs/testing.md).

## Important Implementation Notes

- `InMemoryLargeSourceSet` is in `org.openrewrite.internal` (not the top-level package)
- `ProjectBuildStage` and `DependencyResolutionStage` are `open` with `open` methods — subclass in tests instead of mocking
- `ResultFormatter` has a secondary constructor accepting `PrintWriter`; `RunCommand` passes picocli's `@Spec` output writer
- Stage 0 tries the official Gradle/Maven OpenRewrite plugins first and returns `RunResult.rawDiffs` on success. Use `--skip-plugin-run` / `Builder.skipPluginRun(true)` when testing or debugging the in-process LST pipeline directly.
- Path exclusion: `--exclude-paths` (CLI) overrides `parse.excludePaths` (YAML) when non-empty. The resolved value is forwarded to Stage 0 and to the LST fallback so both apply identical filtering. When no JVM source survives the exclusion, classpath resolution stages 1–4 are skipped.
- OpenRewrite requires all source files in memory simultaneously — for large projects use `-Xmx6g`
- Dockerfile/Containerfile files are collected both by extension (`.dockerfile`, `.containerfile`) and by filename prefix (`Dockerfile*`, `Containerfile*`) — all go into the `.dockerfile` bucket for `DockerParser`
- `pom.xml` files are routed to `MavenParser` (full resolution — parent POMs, property interpolation, BOM imports); all other `.xml` files use `XmlParser`. This split happens in the `.xml` routing block inside `LstBuilder.build()` by filtering on `file.name == "pom.xml"`. `MavenParser` adds `MavenResolutionResult` marker, enabling the full `rewrite-maven` recipe catalog. Resolution uses `~/.m2/settings.xml` and local repo; artifacts already cached require no network access.
- `LstBuilder` delegates to `FileCollector` (file walk/filtering), `VersionDetector` (Java/Kotlin version detection), `GradleDslClasspathResolver` (Gradle installation lookup), and `MarkerFactory` (provenance/build-tool markers). These are internal helpers instantiated inside `LstBuilder`'s constructor body.
- `LstBuilder.parseGradleVersionFromWrapper` and `LstBuilder.resolveGradleDslClasspath` are `internal` thin delegations to `VersionDetector` / `GradleDslClasspathResolver` preserved for test backward compatibility.
- Parsers requiring external runtimes (Python via RPC, JavaScript/TypeScript via Node.js, C# via .NET) are **not** included — they need out-of-process services

## Logging

SLF4J + Logback. `logback.xml` in `cli/src/main/resources/` (root OFF by default, pattern `[%-5level] %message%n` on stderr). `logback-test.xml` in `core/src/test/resources/` (WARN only). See [`docs/testing.md`](docs/testing.md) for log capture patterns.

## Commit Message Convention

Format: `<type>(<scope>): <description>` (lowercase, imperative, no trailing period)

**Types**: `feat` `fix` `docs` `style` `refactor` `test` `chore` `perf` `ci`
**Scopes**: `cli` `core` `lst` `recipe` `config` `output` `deps`

```
feat(lst): add Scala source file parser
fix(cli): correct default output mode when --output is omitted
chore(deps): upgrade OpenRewrite BOM to 3.11.0
```

Breaking changes: append `!` after type/scope, add `BREAKING CHANGE:` footer. See `CONTRIBUTING.md`.

## Code Style

ktlint (`com.pinterest.ktlint:ktlint-cli:1.8.0`) with **Google Android** code style (`.editorconfig`: `ktlint_code_style = android_studio`). CI fails on violations — run `./gradlew ktlintFormat` before pushing.

## Keeping CLAUDE.md and docs/ Current

Update the relevant file whenever:
- A new feature, parser, CLI flag, or output mode is added
- An architectural or dependency decision changes
- A new test pattern or convention is established
- A recurring bug or workaround is discovered

Prefer updating existing entries over appending. Keep CLAUDE.md as an index — details belong in `docs/`.
