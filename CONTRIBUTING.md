# Contributing to rewrite-runner

Thank you for your interest in contributing! This document outlines the process for contributing to this project.

## Table of Contents

- [Getting Started](#getting-started)
- [Development Setup](#development-setup)
- [Making Changes](#making-changes)
- [Commit Message Convention](#commit-message-convention)
- [Pull Request Process](#pull-request-process)
- [Code Style](#code-style)
- [Testing](#testing)

## Getting Started

1. Fork the repository
2. Clone your fork locally
3. Create a new branch from `main` for your changes
4. Make your changes following the guidelines below
5. Push your branch and open a pull request

## Development Setup

**Requirements:**
- JDK 21 (Temurin recommended)
- Gradle (wrapper included — use `./gradlew`)

**Build and test:**

```bash
# Build fat JAR
./gradlew shadowJar

# Run unit tests (fast; this is what `check` runs — see the Testing section)
./gradlew test

# Run a single test class
./gradlew test --tests "io.github.skhokhlov.rewriterunner.output.ResultFormatterTest"

# Run the tool locally
java -jar cli/build/libs/cli-1.0-SNAPSHOT-all.jar --help

# Check code style (Google Android / ktlint)
./gradlew ktlintCheck

# Auto-fix code style issues
./gradlew ktlintFormat
```

## Making Changes

- Keep changes focused and scoped to the feature or bug being addressed
- Do not refactor unrelated code in the same commit
- Add or update tests for any new or changed behavior
- Ensure all tests pass before opening a pull request

## Commit Message Convention

This project uses **[Conventional Commits](https://www.conventionalcommits.org/)** for all commit messages. This enables automated changelog generation and makes the history easier to navigate.

### Format

```
<type>(<scope>): <description>

[optional body]

[optional footer(s)]
```

### Types

| Type | Description |
|------|-------------|
| `feat` | A new feature |
| `fix` | A bug fix |
| `docs` | Documentation changes only |
| `style` | Formatting, missing semicolons, etc. — no logic change |
| `refactor` | Code change that is neither a fix nor a feature |
| `test` | Adding or updating tests |
| `chore` | Build process, dependency updates, tooling changes |
| `perf` | Performance improvements |
| `ci` | CI/CD configuration changes |

### Scopes (optional)

Use a scope to indicate which part of the codebase is affected:

| Scope | Description |
|-------|-------------|
| `cli` | CLI command parsing and output (`cli/` module) |
| `core` | Core library (`core/` module) |
| `lst` | LST building pipeline (`lst/` package) |
| `recipe` | Recipe loading and execution (`recipe/` package) |
| `config` | Tool configuration (`config/` package) |
| `output` | Result formatting and output modes |
| `deps` | Dependency updates |

### Examples

```
feat(lst): add support for Scala source file parsing

fix(cli): correct default output mode when --output flag is omitted

docs: add library usage examples to README

test(recipe): add integration test for composite recipe execution

chore(deps): upgrade OpenRewrite BOM to 3.11.0

refactor(core): extract classpath resolution into separate class

ci: cache Gradle dependencies in GitHub Actions workflow
```

### Breaking Changes

If your change breaks backward compatibility, add `!` after the type/scope and include a `BREAKING CHANGE:` footer:

```
feat(core)!: rename RunResult.results to RunResult.recipeResults

BREAKING CHANGE: The `results` property on `RunResult` has been renamed to
`recipeResults` for clarity. Update all usages accordingly.
```

### Rules

- The `<description>` must be in **lowercase** and **imperative mood** ("add feature", not "added feature" or "adds feature")
- No period at the end of the description
- Keep the description under 72 characters
- Use the body to explain *what* and *why*, not *how*
- Reference issues in the footer: `Closes #123` or `Fixes #456`

## Pull Request Process

1. Ensure your branch is up to date with `main` before opening a PR
2. Fill out the pull request template with a clear description of the changes
3. Link any related issues using `Closes #<issue-number>` in the PR description
4. All CI checks (build + tests) must pass
5. At least one maintainer review is required before merging

## Code Style

This project enforces the **Google Android** Kotlin code style via **[ktlint](https://pinterest.github.io/ktlint/)** (`com.pinterest.ktlint:ktlint-cli:1.8.0`). The code style is configured in `.editorconfig` (`ktlint_code_style = android_studio`). ktlint is resolved directly from Maven Central — no third-party Gradle plugin is required.

**Check formatting:**

```bash
./gradlew ktlintCheck
```

**Auto-fix formatting:**

```bash
./gradlew ktlintFormat
```

Run `ktlintFormat` before committing to avoid CI failures. The `ktlintCheck` task is wired into Gradle's `check` lifecycle and runs automatically as part of every `./gradlew check` or `./gradlew build` invocation.

**Additional style rules:**

- Follow existing Kotlin idioms and conventions in the codebase
- Add KotlinDoc to all new public classes and methods (the project requires this — see `CLAUDE.md`)
- Do not add unnecessary comments; prefer self-documenting code
- Keep functions small and focused

## Testing

### Test lanes

Tests are split into three lanes by Gradle `Test.filter` class-name pattern (no Kotest tags). All test code lives in one source set; the filter decides which classes run in each task. CI runs the three lanes as sequential jobs so a failure surfaces at the right stage.

| Lane | Task | What it runs | Network |
|------|------|--------------|---------|
| Unit | `./gradlew test` | `core` module + `RunCommandTest`; **excludes** `*IntegrationTest`. Wired into `check`. | None |
| Integration (fake wrappers) | `./gradlew :cli:testIntegration` | All `*IntegrationTest` classes **except** `PluginRealExecutionIntegrationTest` — per-language LST coverage plus Stage 0 orchestration driven by fake `gradlew`/`mvnw` shell scripts. | None (offline-safe) |
| Integration (real plugins) | `./gradlew :cli:testRealPlugin` | `PluginRealExecutionIntegrationTest` only — exercises the real OpenRewrite Maven/Gradle plugins. `ToolchainCache` downloads the Maven/Gradle distributions on first run (cached under `cli/build/test-cache/toolchains/`) and the plugin artifacts come from Maven Central. | Maven Central + distribution mirrors |

`check` runs **only** the unit lane (plus `ktlintCheck`); it does **not** depend on the two integration tasks. Run them explicitly:

```bash
# Fast offline verification before pushing:
./gradlew check :cli:testIntegration

# Full pass including the live-plugin lane (~5 min warm, <15 min cold):
./gradlew :cli:testRealPlugin

# Single real-plugin scenario, for iteration:
./gradlew :cli:testRealPlugin --tests "*maven multi-module*"
```

The real-plugin lane skips itself (JUnit assumption, not a failure) when Maven Central is unreachable or on Windows.

### Stage 0 plugin coverage

Stage 0 (plugin-first execution) is covered by two complementary tiers that share the **same** scenario definitions in `PluginScenarios` so the fake and real lanes never drift:

- **Fake-wrapper** (`PluginFirstIntegrationTest`, `testIntegration`) — fast/offline; shell scripts simulate plugin output and validate the exact CLI flag protocol the strategies emit.
- **Real-wrapper** (`PluginRealExecutionIntegrationTest`, `testRealPlugin`) — replays the same scenarios against real Maven/Gradle, calls `RewriteRunner` directly, and asserts `RunResult.executionDiagnostics.stageUsed == UsedExecutionStage.PLUGIN` so a Stage 0 regression that silently falls through to the LST pipeline cannot pass on file-content checks alone.

### Conventions

- New integration test classes must end in `IntegrationTest` (enforced by `failOnNoMatchingTests = true` on the integration tasks — a typo surfaces immediately).
- Use `@TempDir` (JUnit 5) for temporary directories.
- Use `kotlin.test` assertions (`assertEquals`, `assertTrue`, etc.).
- Integration tests should use `BaseIntegrationTest.runCli()` to exercise the full CLI.
- Where environment variability exists (e.g., Maven not installed in CI), tests should accept both the success path and the expected fallback.

See [`docs/testing.md`](docs/testing.md) for the full test file map and patterns.
