# Architecture

## Overview

Fat JAR CLI that runs OpenRewrite recipes against arbitrary project directories **without requiring a working build system**. Also usable as a library (`core` module).

**Entry point**: `Main.kt` → `CommandLine(RunCommand()).execute(*args)`.
`RunCommand` is the **root command** (not a subcommand) — all options are passed directly without a "run" prefix.

## Execution Pipeline

Orchestrated by `RewriteRunner.run()`, delegated to by `RunCommand.call()`:

| Step | Class | Description |
|------|-------|-------------|
| 1 | `ToolConfig` | Load YAML config (env var interpolation, tilde expansion) |
| 2 | `RecipeArtifactResolver` | Resolve recipe JARs from Maven coordinates |
| 3 | `RecipeLoader` | Load recipe from JARs + optional `rewrite.yaml` |
| 4 | `LstBuilder` | Build LST via 3-stage classpath pipeline + multi-language parsing |
| 5 | `RecipeRunner` | Execute recipe, collect `List<Result>` |
| 6 | — | Write changed files to disk (skipped when `dryRun = true`) |
| 7 | `ResultFormatter` | Format output (diff / files / report) |

## Maven Local Repository Strategy

Two separate `AetherContext` instances are created per `RewriteRunner.run()` invocation, each with a distinct local Maven repository:

| Context | Local repository | Used by |
|---------|-----------------|---------|
| `recipeContext` | `<cacheDir>/repository` | `RecipeArtifactResolver` — recipe JARs |
| `projectContext` | `~/.m2/repository` (Maven default) | `DependencyResolutionStage` — project deps |

Recipe JARs are cached under the tool's own `cacheDir` so they never pollute the user's Maven local repository. Project dependencies resolve against `~/.m2/repository` so artifacts already downloaded by the project's own build (Maven/Gradle) are reused without re-downloading.

`AetherContext.build(localRepoDir)` accepts the local repository path directly — callers are responsible for choosing between the tool cache and the Maven default.

## 3-Stage LST Classpath Resolution

`LstBuilder` runs these stages in order, falling through on failure:

- **Stage 1** (`BuildToolStage`): Subprocess Maven/Gradle to extract compile classpath. Falls through on failure.
- **Stage 2** (`DependencyResolutionStage`): Parse `pom.xml`/`build.gradle` + Maven Resolver to download JARs. Uses `~/.m2/repository` as the local repo. Falls through on failure.
- **Stage 3** (`DirectParseStage`): Scan `~/.m2` and `~/.gradle/caches` for already-cached JARs. Always succeeds (possibly empty).

The resolved classpath is **shared across all language parsers** — `JavaParser`, `KotlinParser`, and `GroovyParser` all receive the same project classpath so cross-language type references resolve correctly.

`LstBuilder` also adds project class directories (`target/classes`, `build/classes/java/main`, etc.) to the classpath for cross-module type resolution.

## LST Module Structure

`LstBuilder` is an orchestrator that delegates each concern to a focused helper class:

| Class | Responsibility |
|-------|---------------|
| `LstBuilder` | Orchestration, 3-stage classpath pipeline, parser dispatch |
| `FileCollector` | NIO walk, excluded-dir filtering, glob exclusions, extension resolution |
| `VersionDetector` | Java/Kotlin JVM-version walk-up, `normalizeJvmVersion`, `parseGradleVersionFromWrapper` |
| `GradleDslClasspathResolver` | Locate Gradle installation (`GRADLE_HOME`, wrapper, `~/.gradle/wrapper/dists/`) |
| `MarkerFactory` | `BuildTool`, `GitProvenance`, `OperatingSystemProvenance`, `BuildEnvironment`, `GradleProject` markers |

`BuildToolStage` and `DependencyResolutionStage` remain injected into `LstBuilder` as `open` classes; the helper classes above are internal and are instantiated by `LstBuilder`.

## File Routing by Extension

`LstBuilder` delegates file collection to `FileCollector`. Excludes `build/`, `target/`, `node_modules/`, `.git/`, `.gradle/`, `.idea/`, `out/`, `dist/` by default.

| Extension | Parser | Classpath |
|-----------|--------|-----------|
| `.java` | `JavaParser` | Project classpath |
| `.kt` | `KotlinParser` | Project classpath |
| `*.gradle.kts` | `KotlinParser` | Project classpath + Gradle DSL |
| `.kts` (other) | `KotlinParser` | Project classpath |
| `.groovy` | `GroovyParser` | Project classpath |
| `.gradle` | `GroovyParser` | Project classpath + Gradle DSL |
| `.yaml` / `.yml` | `YamlParser` | — |
| `.json` | `JsonParser` | — |
| `pom.xml` | `MavenParser` | Fully resolved (parent POMs, property interpolation, BOM imports); adds `MavenResolutionResult` marker, enabling `rewrite-maven` recipes |
| `*.xml` (other) | `XmlParser` | — |
| `.properties` | `PropertiesParser` | — |
| `.toml` | `TomlParser` | — |
| `.hcl` / `.tf` / `.tfvars` | `HclParser` | — |
| `.proto` | `ProtoParser` | — |
| `.dockerfile` / `.containerfile` / `Dockerfile*` / `Containerfile*` | `DockerParser` | — (matched by extension **and** by filename prefix) |

## Gradle DSL Classpath

Resolved from the Gradle installation:
`GRADLE_HOME` env var → project Gradle wrapper → `~/.gradle/wrapper/dists/` best-effort fallback.

Only JARs in `lib/` (not `lib/plugins/` or `lib/agents/`) are included — provides core Gradle API types needed to parse build scripts. Applied only to `.gradle` and `*.gradle.kts` files.

## Java / Kotlin Version Detection

`VersionDetector` attaches a `JavaVersion` marker to each source file using a **walk-up mechanism**: starts at the file's directory and walks up to project root, so subproject-specific config overrides root config.

**For `.java` files** — reads from nearest build file:
- Maven: `<source>`, `<target>`, `<release>` in `maven-compiler-plugin`
- Gradle: `jvmToolchain()`, `sourceCompatibility`

**For `.kt` / `.kts` files** — Kotlin-specific settings take priority, shared settings are fallback:
- Priority: `kotlin-maven-plugin <jvmTarget>`, `kotlinOptions.jvmTarget`, `JvmTarget.JVM_N`
- Fallback: `jvmToolchain`, `sourceCompatibility`, `maven-compiler-plugin`

## Module Structure

```
settings.gradle.kts
buildSrc/                          # Convention plugins (shared build config)
│   └── src/main/kotlin/
│       ├── kotlin-convention.gradle.kts      # Kotlin JVM 21, JaCoCo, ktlint tasks
│       ├── publishing-convention.gradle.kts  # maven-publish, signing, Dokka, POM
│       └── dokka-convention.gradle.kts       # Dokka HTML docs
core/                              # Library module (no embedded deps)
│   └── src/main/kotlin/.../
│       ├── RewriteRunner.kt       # Library facade / builder API
│       ├── RunResult.kt
│       ├── config/ToolConfig.kt
│       ├── lst/                   # LstBuilder (orchestrator) + FileCollector, VersionDetector, GradleDslClasspathResolver, MarkerFactory + 3 stage classes
│       ├── output/ResultFormatter.kt
│       └── recipe/                # RecipeArtifactResolver, RecipeLoader, RecipeRunner
cli/                               # Fat JAR module
│   └── src/main/kotlin/.../
│       ├── Main.kt
│       └── cli/RunCommand.kt
```
