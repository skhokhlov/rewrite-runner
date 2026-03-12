# rewrite-runner

[![Build](https://github.com/skhokhlov/rewrite-runner/actions/workflows/build.yml/badge.svg)](https://github.com/skhokhlov/rewrite-runner/actions/workflows/build.yml)
[![CodeQL](https://github.com/skhokhlov/rewrite-runner/actions/workflows/codeql.yml/badge.svg)](https://github.com/skhokhlov/rewrite-runner/actions/workflows/codeql.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.skhokhlov.rewriterunner/core)](https://central.sonatype.com/artifact/io.github.skhokhlov.rewriterunner/core)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

A self-hosted CLI tool for running [OpenRewrite](https://docs.openrewrite.org/) recipes against arbitrary repositories — without requiring the target project's build to be working.

## Features

- Run any OpenRewrite recipe against a local project directory
- Works even when the project's build is broken, credentials are missing, or private registries are unavailable
- Automatically downloads recipe JARs from Maven coordinates — no manual dependency management
- Supports Java, Kotlin, Groovy, YAML, JSON, XML, and Properties files
- Three output modes: unified diffs, changed file paths, or a structured JSON report
- Composable recipes via `rewrite.yaml`
- Configurable Maven repositories for enterprise environments with private Nexus/Artifactory

## Installation

`rewrite-runner` is published to Maven Central. The `core` module is the library; the `cli` module ships a thin JAR plus a `-all` fat JAR for direct CLI use.

### Gradle

```kotlin
// build.gradle.kts
dependencies {
    implementation("io.github.skhokhlov.rewriterunner:core:1.0.0")
}
```

### Maven

```xml
<dependency>
    <groupId>io.github.skhokhlov.rewriterunner</groupId>
    <artifactId>core</artifactId>
    <version>1.0.0</version>
</dependency>
```

### CLI fat JAR

Download the `-all` jar directly from Maven Central:

```bash
curl -L -o rewrite-runner.jar \
  "https://repo1.maven.org/maven2/io/github/skhokhlov/rewriterunner/cli/1.0.0/cli-1.0.0-all.jar"
java -jar rewrite-runner.jar --help
```

## Getting Started

### Build

Requires JDK 21+.

```bash
./gradlew shadowJar
# Produces: cli/build/libs/cli-1.0-SNAPSHOT-all.jar
```

### Run a recipe

```bash
java -jar cli/build/libs/cli-1.0-SNAPSHOT-all.jar \
  --project-dir /path/to/your/project \
  --active-recipe org.openrewrite.java.format.AutoFormat \
  --recipe-artifact org.openrewrite.recipe:rewrite-static-analysis:LATEST
```

### Dry run (preview changes without writing to disk)

```bash
java -jar cli/build/libs/cli-1.0-SNAPSHOT-all.jar \
  --project-dir /path/to/your/project \
  --active-recipe org.openrewrite.java.migrate.UpgradeToJava21 \
  --recipe-artifact org.openrewrite.recipe:rewrite-migrate-java:LATEST \
  --dry-run \
  --output diff
```

## Library Usage

`rewrite-runner` can be used as a library from Java and Kotlin code without the CLI layer. Use the plain JAR (not the `-all` fat JAR) as a dependency.

### Adding as a dependency

```kotlin
// build.gradle.kts — Maven Central (recommended)
dependencies {
    implementation("io.github.skhokhlov.rewriterunner:core:1.0.0")
}
```

```kotlin
// build.gradle.kts — local JAR (for development)
dependencies {
    implementation(files("libs/rewrite-runner-core-1.0-SNAPSHOT.jar"))
}
```

### Kotlin usage

```kotlin
import io.github.skhokhlov.rewriterunner.RewriteRunner
import java.nio.file.Paths

fun main() {
    val result = RewriteRunner.builder()
        .projectDir(Paths.get("/path/to/project"))
        .activeRecipe("org.openrewrite.java.format.AutoFormat")
        .recipeArtifact("org.openrewrite.recipe:rewrite-static-analysis:LATEST")
        .dryRun(true)   // preview changes without writing to disk
        .build()
        .run()

    println("Changed ${result.changeCount} file(s)")
    result.results.forEach { r -> println(r.diff()) }
}
```

### Java usage

```java
import io.github.skhokhlov.rewriterunner.RewriteRunner;
import io.github.skhokhlov.rewriterunner.RunResult;
import java.nio.file.Paths;

public class Example {
    public static void main(String[] args) {
        RunResult result = RewriteRunner.builder()
            .projectDir(Paths.get("/path/to/project"))
            .activeRecipe("org.openrewrite.java.format.AutoFormat")
            .recipeArtifact("org.openrewrite.recipe:rewrite-static-analysis:LATEST")
            .dryRun(true)
            .build()
            .run();

        System.out.println("Changed " + result.getChangeCount() + " file(s)");
        result.getResults().forEach(r -> System.out.println(r.diff()));
    }
}
```

### Working with results

`RunResult` gives you raw access to everything the recipe produced:

```kotlin
val result = runner.run()

println("Changed: ${result.hasChanges}")         // true/false
println("Files changed: ${result.changeCount}")   // number of changed files

// Iterate raw OpenRewrite results
result.results.forEach { r ->
    // r.before — source file before the recipe (null for newly created files)
    // r.after  — source file after the recipe (null for deleted files)
    println(r.diff())           // unified diff string
    println(r.after?.sourcePath)  // relative path of the changed file
}

// changedFiles: paths written to disk (empty in dry-run mode)
result.changedFiles.forEach { path -> println("Written: $path") }
```

### Formatted output (ResultFormatter)

For the same three output modes as the CLI (`diff`, `files`, `report`), use `ResultFormatter`:

```kotlin
import io.github.skhokhlov.rewriterunner.output.OutputMode
import io.github.skhokhlov.rewriterunner.output.ResultFormatter

val result = runner.run()

// Print unified diffs to stdout
ResultFormatter(OutputMode.DIFF).format(result.results, result.projectDir)

// Print only the paths of changed files (one per line)
ResultFormatter(OutputMode.FILES).format(result.results, result.projectDir)

// Write openrewrite-report.json to the project directory
ResultFormatter(OutputMode.REPORT).format(result.results, result.projectDir)
```

`OutputMode` values:

| Value | Behaviour |
|-------|-----------|
| `DIFF` | Prints a unified diff for each changed file to stdout (default CLI mode) |
| `FILES` | Prints one changed-file path per line to stdout |
| `REPORT` | Writes `openrewrite-report.json` to the `reportDir` argument (defaults to `.`) |

Library consumers that only need to inspect changes programmatically can skip `ResultFormatter` entirely and work with `RunResult.results` directly.

### Builder reference

| Method | Type | Default | Description |
|--------|------|---------|-------------|
| `projectDir(Path)` | required | `.` (cwd) | Project root to analyse |
| `activeRecipe(String)` | required | — | Fully-qualified recipe name |
| `recipeArtifact(String)` | optional (repeatable) | — | Maven coordinate of a recipe JAR; may be called multiple times |
| `recipeArtifacts(List<String>)` | optional | — | Set all recipe artifact coordinates at once |
| `rewriteConfig(Path)` | optional | `<projectDir>/rewrite.yaml` | Path to a `rewrite.yaml` for custom composite recipes |
| `rewriteConfigContent(String)` | optional | — | Raw `rewrite.yaml` content as a string; takes precedence over `rewriteConfig` when both are set |
| `cacheDir(Path)` | optional | `~/.rewriterunner/cache` | Cache root for downloaded recipe JARs (stored under `<cacheDir>/repository`). Project dependencies always resolve from `~/.m2/repository`. |
| `configFile(Path)` | optional | auto-discovered | Path to `rewriterunner.yml`; auto-discovery checks `<projectDir>/rewriterunner.yml` then `~/.rewriterunner/rewriterunner.yml` |
| `dryRun(Boolean)` | optional | `false` | Analyse without writing to disk |
| `includeExtensions(List<String>)` | optional | all supported | File extensions to parse; overrides config file setting |
| `excludeExtensions(List<String>)` | optional | — | File extensions to skip; overrides config file setting |
| `excludePaths(List<String>)` | optional | — | Glob patterns (relative to project root) for paths to skip during parsing; overrides `parse.excludePaths` from config file |
| `includeMavenCentral(Boolean)` | optional | `true` | Include Maven Central as a resolution repository. Set to `false` for air-gapped or enterprise environments. |
| `repository(RepositoryConfig)` | optional (repeatable) | — | Add one extra Maven repository for artifact resolution; combined with repositories from config file |
| `repositories(List<RepositoryConfig>)` | optional | — | Set all extra Maven repositories at once; combined with repositories from config file |
| `logger(RunnerLogger)` | optional | `NoOpRunnerLogger` | Receives pipeline log events; implement to capture lifecycle, info, debug, warn, and error messages |

### Logging

By default the `core` library produces **no log output** — all logging is suppressed via `NoOpRunnerLogger`. To receive pipeline events, implement `RunnerLogger` and pass it to the builder:

```kotlin
import io.github.skhokhlov.rewriterunner.RunnerLogger

class PrintlnLogger : RunnerLogger {
    override fun lifecycle(message: String) = println("[LIFECYCLE] $message")
    override fun info(message: String)      = println("[INFO]      $message")
    override fun debug(message: String)     = println("[DEBUG]     $message")
    override fun warn(message: String)      = println("[WARN]      $message")
    override fun error(message: String, cause: Throwable?) {
        println("[ERROR]     $message")
        cause?.printStackTrace()
    }
}

val result = RewriteRunner.builder()
    .projectDir(Paths.get("/path/to/project"))
    .activeRecipe("org.openrewrite.java.format.AutoFormat")
    .logger(PrintlnLogger())   // wire your logger here
    .build()
    .run()
```

**Log levels** and what they emit:

| Level | What you receive |
|-------|-----------------|
| `lifecycle` | Pipeline stage headers and summary results (always relevant) |
| `info` | Per-language file counts, stage status, artifact resolution progress |
| `debug` | Per-file version detection, recipe JAR scanning |
| `warn` | Recoverable problems (e.g. 404 during artifact resolution) |
| `error` | Fatal failures, always with an optional `cause` |

`NoOpRunnerLogger` (the default) silently discards all messages. The CLI wires its own Logback-backed implementation; `--info` and `--debug` flags control which levels are forwarded to Logback.

---

### Enterprise and private registry setup

When Maven Central is unreachable, provide your private registry directly via the builder:

```kotlin
import io.github.skhokhlov.rewriterunner.config.RepositoryConfig

val result = RewriteRunner.builder()
    .projectDir(Paths.get("/path/to/project"))
    .activeRecipe("org.openrewrite.java.format.AutoFormat")
    .recipeArtifact("org.openrewrite.recipe:rewrite-static-analysis:LATEST")
    .repository(RepositoryConfig(
        url = "https://nexus.example.com/repository/maven-public",
        username = System.getenv("NEXUS_USER"),
        password = System.getenv("NEXUS_PASS")
    ))
    .includeMavenCentral(false)  // restrict resolution to the Nexus repository only
    .build()
    .run()
```

### Programmatic composite recipes

Instead of writing a `rewrite.yaml` file on disk, you can supply the YAML content as a string directly via `rewriteConfigContent`:

```kotlin
val recipeYaml = """
    type: specs.openrewrite.org/v1beta/recipe
    name: com.example.MyMigration
    displayName: My Custom Migration
    recipeList:
      - org.openrewrite.java.migrate.UpgradeToJava21
      - org.openrewrite.java.format.AutoFormat
""".trimIndent()

val result = RewriteRunner.builder()
    .projectDir(Paths.get("/path/to/project"))
    .activeRecipe("com.example.MyMigration")
    .recipeArtifact("org.openrewrite.recipe:rewrite-migrate-java:LATEST")
    .rewriteConfigContent(recipeYaml)  // no file required
    .build()
    .run()
```

## CLI Reference

```
Usage: rewrite-runner [-h] [--dry-run] [--info] [--debug] [--no-maven-central]
                          [--active-recipe=<recipe>]
                          [--cache-dir=<path>] [--config=<path>]
                          [--output=<mode>] [--project-dir=<path>]
                          [--rewrite-config=<path>]
                          [--exclude-extensions=<ext>[,<ext>...]]
                          [--include-extensions=<ext>[,<ext>...]]
                          [--recipe-artifact=<coord>]...
```

| Option | Description | Default |
|--------|-------------|---------|
| `--project-dir`, `-p` | Project directory to refactor | `.` (current directory) |
| `--active-recipe`, `-r` | Fully-qualified recipe name to run | *(required)* |
| `--recipe-artifact` | Maven coordinate of a recipe JAR to load (repeatable) | — |
| `--rewrite-config` | Path to `rewrite.yaml` for custom recipe compositions | `<project-dir>/rewrite.yaml` |
| `--output`, `-o` | Output mode: `diff`, `files`, or `report` | `diff` |
| `--cache-dir` | Cache root for downloaded recipe JARs (stored under `<path>/repository`). Project dependencies always resolve from `~/.m2/repository`. | `~/.rewriterunner/cache` |
| `--config` | Path to tool config file (`rewriterunner.yml`) | `<project-dir>/rewriterunner.yml`, then `~/.rewriterunner/rewriterunner.yml` |
| `--dry-run` | Run recipe but do not write changes to disk | `false` |
| `--include-extensions` | Comma-separated file extensions to parse (e.g. `.java,.kt`) | all supported |
| `--exclude-extensions` | Comma-separated file extensions to skip | — |
| `--no-maven-central` | Disable Maven Central; use only repositories from the config file | `false` |
| `--info` | Enable INFO-level logging to stderr | `false` |
| `--debug` | Enable DEBUG-level logging to stderr (overrides `--info`) | `false` |

### Output modes

**`--output diff`** (default) — prints unified diffs for each changed file:
```diff
--- a/src/main/java/Hello.java
+++ b/src/main/java/Hello.java
@@ -1,3 +1,5 @@
 public class Hello {
-    public static void main(String[]args){System.out.println("hi");}
+    public static void main(String[] args) {
+        System.out.println("hi");
+    }
 }
```

**`--output files`** — prints only the paths of changed files, one per line.

**`--output report`** — writes `openrewrite-report.json` to the project directory:
```json
{
  "totalChanged": 1,
  "results": [
    {
      "filePath": "src/main/java/Hello.java",
      "diff": "...",
      "isNewFile": false,
      "isDeletedFile": false
    }
  ]
}
```

## Recipe Artifacts

Specify recipe JARs using Maven coordinates. The `--recipe-artifact` flag can be repeated to load multiple recipe modules.

```bash
# Load a single recipe module
--recipe-artifact org.openrewrite.recipe:rewrite-spring:LATEST

# Load multiple modules
--recipe-artifact org.openrewrite.recipe:rewrite-migrate-java:LATEST \
--recipe-artifact org.openrewrite.recipe:rewrite-spring:LATEST
```

`LATEST` resolves to the most recent release. Specific versions (e.g. `2.21.0`) are also accepted.

Downloaded recipe JARs are cached under `~/.rewriterunner/cache/repository` (or `--cache-dir`/repository) and reused on subsequent runs. They are stored separately from the project's own dependencies, which always resolve from `~/.m2/repository`.

## Custom Recipe Compositions

Define composite recipes in a `rewrite.yaml` file in your project directory (or pass `--rewrite-config`):

```yaml
---
type: specs.openrewrite.org/v1beta/recipe
name: com.example.MyMigration
displayName: My Custom Migration
recipeList:
  - org.openrewrite.java.migrate.UpgradeToJava21
  - org.openrewrite.java.spring.boot3.UpgradeSpringBoot_3_3
  - org.openrewrite.java.format.AutoFormat
```

Then run it:

```bash
java -jar rewrite-runner-all.jar \
  --project-dir /path/to/project \
  --active-recipe com.example.MyMigration \
  --recipe-artifact org.openrewrite.recipe:rewrite-migrate-java:LATEST \
  --recipe-artifact org.openrewrite.recipe:rewrite-spring:LATEST
```

## Tool Config File

Create `rewriterunner.yml` to configure repositories and caching for your environment.

**Default locations** (checked in order):
1. `<project-dir>/rewriterunner.yml` — project-level config
2. `~/.rewriterunner/rewriterunner.yml` — global fallback, shared across all projects

File name matching is case-insensitive (e.g. `RewriteRunner.yml` also works). Override either default with `--config <path>`.

```yaml
repositories:
  - url: https://nexus.example.com/repository/maven-public
    username: ${NEXUS_USER}
    password: ${NEXUS_PASSWORD}

cacheDir: ~/.rewriterunner/cache

parse:
  includeExtensions: [".java", ".kt", ".xml"]
  excludeExtensions: [".properties"]
  excludePaths:
    - "**/generated/**"
    - "**/build/**"
```

Environment variable placeholders (`${VAR_NAME}`) are expanded at runtime.

## Resilient Parsing Pipeline

The tool uses a three-stage fallback pipeline to build the LST, ensuring recipes run even on projects with broken builds.

### Stage 1 — Build tool classpath extraction
Invokes the project's own build tool as a subprocess to extract the full compile classpath:
- **Maven**: `mvn dependency:build-classpath -DincludeScope=compile`
- **Gradle**: injects a temporary init script that prints `runtimeClasspath` file paths

If successful, the resulting JAR list is passed to `JavaParser` for full type attribution (resolves imports, method signatures, type hierarchies).

### Stage 2 — Direct dependency resolution
If Stage 1 fails (broken build, no wrapper, timeout), the tool resolves dependencies without running the full build:
- **Maven**: parses `pom.xml` using `maven-model`; resolves JARs via Maven Resolver (Aether)
- **Gradle**: runs `gradle dependencies` for the root project **and all declared subprojects** (discovered from `settings.gradle` / `settings.gradle.kts`), parsing the resolved dependency tree to get accurately resolved versions; falls back to best-effort static regex parsing of `build.gradle` / `build.gradle.kts` if Gradle cannot be invoked

> **Note:** The `gradle dependencies` task only reports dependencies for the project it is applied to. Subprojects are queried explicitly (`:sub:dependencies`) so that multi-module builds are fully covered.

Resolved JARs are cached in `~/.m2/repository` (Maven default), so artifacts already downloaded by the project's own build are reused without re-downloading. Extra repositories from the tool config are also consulted.

### Stage 3 — Local cache scan
If dependency resolution also fails, the tool scans local Maven and Gradle caches:
- `~/.m2/repository` for Maven-cached JARs
- `~/.gradle/caches/modules-*/files-*/` for Gradle-cached JARs

Unresolved types appear as `JavaType.Unknown` in the LST, but all structural, text-based, YAML, XML, and search recipes continue to work correctly.

## Supported File Types

| Extension | Parser |
|-----------|--------|
| `.java` | `JavaParser` (with classpath from 3-stage pipeline) |
| `.kt`, `.kts` | `KotlinParser` (`.kts` augmented with Gradle DSL classpath) |
| `.groovy`, `.gradle` | `GroovyParser` (`.gradle` augmented with Gradle DSL classpath) |
| `.yaml`, `.yml` | `YamlParser` |
| `.json` | `JsonParser` |
| `.xml` | `XmlParser` |
| `.properties` | `PropertiesParser` |

The parsed file set is configurable via `--include-extensions`, `--exclude-extensions`, and the `parse` section of `rewriterunner.yml`.

### Automatically excluded directories

The following directories are always skipped during the file-system walk, regardless of configuration:

`.git`, `build`, `target`, `node_modules`, `.gradle`, `.idea`, `out`, `dist`

Use `parse.excludePaths` (or `--exclude-extensions` / `excludePaths()` in the library API) to skip additional paths.

## Development

```bash
# Run all tests
./gradlew test

# Run a specific test class
./gradlew test --tests "io.github.skhokhlov.rewriterunner.output.ResultFormatterTest"

# Build and run locally
./gradlew shadowJar
java -jar cli/build/libs/cli-1.0-SNAPSHOT-all.jar --help
```
