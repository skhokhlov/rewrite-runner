# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

**Deep-dive docs** (read these before working on the relevant area):
- [`docs/architecture.md`](docs/architecture.md) — execution pipeline, 3-stage LST, file routing, version detection, module layout
- [`docs/dependency-resolution.md`](docs/dependency-resolution.md) — how/why recipe vs project deps are resolved, Aether session settings, scope pruning, local repo strategy
- [`docs/library-api.md`](docs/library-api.md) — `RewriteRunner` builder, `RunResult`, `ToolConfig` YAML schema, exit codes
- [`docs/testing.md`](docs/testing.md) — TDD requirement, test patterns, gotchas, test file map
- [`docs/build.md`](docs/build.md) — `buildSrc` convention plugins, dependency versions, CI/CD, known issues

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
| `--include-extensions` | | Comma-separated file types to parse | — |
| `--exclude-extensions` | | Comma-separated file types to skip | — |
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
│   ├── lst/
│   │   ├── LstBuilder.kt           # Orchestrates 3-stage pipeline + multi-language parsing
│   │   ├── BuildToolStage.kt       # Stage 1: Maven/Gradle subprocess
│   │   ├── DependencyResolutionStage.kt  # Stage 2: Maven Resolver download
│   │   └── DirectParseStage.kt     # Stage 3: Local cache scan
│   ├── output/ResultFormatter.kt   # diff/files/report output modes
│   └── recipe/
│       ├── RecipeArtifactResolver.kt
│       ├── RecipeLoader.kt
│       └── RecipeRunner.kt
└── test/kotlin/.../rewriterunner/
    ├── config/ToolConfigTest.kt
    ├── lst/                        # LstBuilderTest, stage tests, JavaVersionDetectionTest, KotlinVersionDetectionTest
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
- `BuildToolStage` and `DependencyResolutionStage` are `open` with `open` methods — subclass in tests instead of mocking
- `ResultFormatter` has a secondary constructor accepting `PrintWriter`; `RunCommand` passes picocli's `@Spec` output writer
- Extension filtering: CLI flags take precedence over config file settings
- OpenRewrite requires all source files in memory simultaneously — for large projects use `-Xmx6g`

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
