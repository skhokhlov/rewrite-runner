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

Tests use class-level Kotest `@Tags` annotations. The first build stage runs untagged tests by default; each later stage selects its own tag. Class names do not select a lane.

| Lane | Class tag | Task | Requirements |
|------|-----------|------|--------------|
| Default | None | `./gradlew check` | Untagged tests and lint |
| Offline integration | `integration` | `./gradlew :cli:testIntegration` | Fake wrappers and nested Gradle fallback |
| Worker | `worker` | `./gradlew :core:testWorker :cli:testWorker` | Native JVM/process and filesystem contracts on Linux and Windows |
| Real plugins | `real-plugin` | `./gradlew :cli:testRealPlugin` | Network access and downloaded toolchains |
| Container | `container` | `./gradlew :cli:testContainer` | Docker |

Run `./gradlew productionCheck` for all lanes and the release fat JAR. Run `./gradlew check :cli:testIntegration` for default and offline integration verification.

Gradle validates every compiled spec before filtering tests: unknown tags, multiple lane tags, and untagged specs in the integration package fail verification. Default specs remain untagged. External prerequisites must be available for the corresponding lanes; Windows-only cases use JUnit assumptions inside the test body to skip on other systems.

### Stage 0 plugin coverage

Stage 0 (plugin-first execution) is covered by two complementary tiers that share the **same** scenario definitions in `PluginScenarios` so the fake and real lanes never drift:

- **Fake-wrapper** (`PluginFirstIntegrationTest`, `testIntegration`) — fast/offline; shell scripts simulate plugin output and validate the exact CLI flag protocol the strategies emit.
- **Real-wrapper** (`PluginRealExecutionIntegrationTest`, `testRealPlugin`) — replays the same scenarios against real Maven/Gradle, calls `RewriteRunner` directly, and asserts `RunResult.executionDiagnostics.stageUsed == UsedExecutionStage.PLUGIN` so a Stage 0 regression that silently falls through to the LST pipeline cannot pass on file-content checks alone.

### Conventions

- Put integration specs in `io.github.skhokhlov.rewriterunner.integration` and annotate the class with exactly one of `@Tags("integration")`, `@Tags("worker")`, `@Tags("real-plugin")`, or `@Tags("container")`. Leave ordinary default specs untagged.
- Use Kotest class annotations, not test-body tags or OS tags. The `IntegrationTest` suffix is a naming convention only.
- Use `@TempDir` (JUnit 5) for temporary directories.
- Use `kotlin.test` assertions (`assertEquals`, `assertTrue`, etc.).
- Integration tests should use the top-level `runCli()` helper to exercise the full CLI.
- Where environment variability exists (e.g., Maven not installed in CI), tests should accept both the success path and the expected fallback.

See [`docs/testing.md`](docs/testing.md) for the full test file map and patterns.
