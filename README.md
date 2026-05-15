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
- Supports Java, Kotlin, Groovy, YAML, JSON, XML, Properties, TOML, HCL/Terraform, Protobuf, and Dockerfile files (`pom.xml` uses `MavenParser` for full Maven recipe support)
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
import java.time.Duration

fun main() {
    val result = RewriteRunner.builder()
        .projectDir(Paths.get("/path/to/project"))
        .activeRecipe("org.openrewrite.java.format.AutoFormat")
        .recipeArtifact("org.openrewrite.recipe:rewrite-static-analysis:LATEST")
        .processTimeout(Duration.ofSeconds(120))
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
import java.time.Duration;

public class Example {
    public static void main(String[] args) {
        RunResult result = RewriteRunner.builder()
            .projectDir(Paths.get("/path/to/project"))
            .activeRecipe("org.openrewrite.java.format.AutoFormat")
            .recipeArtifact("org.openrewrite.recipe:rewrite-static-analysis:LATEST")
            .processTimeout(Duration.ofSeconds(120))
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

// Per-file parse failures (no need to scrape logs)
result.executionDiagnostics.parseFailures.forEach { failure ->
    println("${failure.parser} could not handle ${failure.path}: ${failure.reason}")
}
```

Per-file parse failures are collected into `executionDiagnostics.parseFailures`
rather than aborting the build; callers can surface or ignore them. The one
intentional exception: a non-URI `MavenParser` throw aborts the LST build by
design (silently downgrading it to `XmlParser` would hide regressions and produce
misleading recipe results). URI-class `MavenParser` failures still fall back to
`XmlParser` and are recorded normally. Fatal `Error`s (e.g. `OutOfMemoryError`)
always propagate. See [`docs/library-api.md`](docs/library-api.md#parse-failures)
for the full shape.

### Formatted output (ResultFormatter)

For the same three output modes as the CLI (`diff`, `files`, `report`), use `ResultFormatter`:

```kotlin
import io.github.skhokhlov.rewriterunner.output.OutputMode
import io.github.skhokhlov.rewriterunner.output.ResultFormatter

val result = runner.run()

// Print unified diffs to stdout
ResultFormatter(OutputMode.DIFF).format(result)

// Print only the paths of changed files (one per line)
ResultFormatter(OutputMode.FILES).format(result)

// Write openrewrite-report.json to the project directory
ResultFormatter(OutputMode.REPORT).format(result)
```

`OutputMode` values:

| Value | Behaviour |
|-------|-----------|
| `DIFF` | Prints a unified diff for each changed file to stdout (default CLI mode) |
| `FILES` | Prints one changed-file path per line to stdout |
| `REPORT` | Writes `openrewrite-report.json` to the `reportDir` argument (defaults to `.`) |

Library consumers that only need to inspect changes programmatically can skip `ResultFormatter` entirely and work with `RunResult.results` directly.

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
    .artifactRepository(RepositoryConfig(
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
Usage: rewrite-runner [-h] [--dry-run] [--skip-plugin-run] [--info] [--debug]
                          [--no-maven-central]
                          [--active-recipe=<recipe>]
                          [--cache-dir=<path>] [--config=<path>]
                          [--artifact-download-threads=<n>]
                          [--subprocess-run-timeout=<duration>]
                          [--plugin-run-timeout<duration>]
                          [--artifact-resolver-connect-timeout=<duration>]
                          [--artifact-resolver-request-timeout=<duration>]
                          [--output=<mode>] [--project-dir=<path>]
                          [--rewrite-config=<path>]
                          [--exclude-extensions=<ext>[,<ext>...]]
                          [--include-extensions=<ext>[,<ext>...]]
                          [--recipe-artifact=<coord>]...
```

| Option                                | Description | Default |
|---------------------------------------|-------------|---------|
| `--project-dir`, `-p`                 | Project directory to refactor | `.` (current directory) |
| `--active-recipe`, `-r`               | Fully-qualified recipe name to run | *(required)* |
| `--recipe-artifact`                   | Maven coordinate of a recipe JAR to load (repeatable) | — |
| `--rewrite-config`                    | Path to `rewrite.yaml` for custom recipe compositions | `<project-dir>/rewrite.yaml` |
| `--output`, `-o`                      | Output mode: `diff`, `files`, or `report` | `diff` |
| `--cache-dir`                         | Cache root for downloaded recipe JARs (stored under `<path>/repository`). Project dependencies always resolve from `~/.m2/repository`. | `~/.rewriterunner/cache` |
| `--config`                            | Path to tool config file (`rewriterunner.yml`) | `<project-dir>/rewriterunner.yml`, then `~/.rewriterunner/rewriterunner.yml` |
| `--dry-run`                           | Run recipe but do not write changes to disk | `false` |
| `--skip-plugin-run`                   | Skip plugin-first execution; use full LST pipeline directly | `false` |
| `--artifact-download-threads`         | Number of parallel artifact download threads | `5` |
| `--subprocess-run-timeout`            | Timeout for build-tool subprocesses in the fallback LST pipeline. Accepts `ms`, `s`, `m`, `h`, `d`, or ISO-8601 values. | `120s` |
| `--plugin-run-timeout`                | Timeout for plugin-first Gradle/Maven invocations. Accepts `ms`, `s`, `m`, `h`, `d`, or ISO-8601 values. | `10m` |
| `--artifact-resolver-connect-timeout` | TCP connection timeout for Maven Resolver downloads. Accepts `ms`, `s`, `m`, `h`, `d`, or ISO-8601 values. | `30s` |
| `--artifact-resolver-request-timeout` | Socket read/request timeout for Maven Resolver downloads. Accepts `ms`, `s`, `m`, `h`, `d`, or ISO-8601 values. | `60s` |
| `--include-extensions`                | Comma-separated file extensions to parse (e.g. `.java,.kt`) | all supported |
| `--exclude-extensions`                | Comma-separated file extensions to skip | — |
| `--no-maven-central`                  | Disable Maven Central; use only repositories from the config file | `false` |
| `--info`                              | Enable INFO-level logging to stderr | `false` |
| `--debug`                             | Enable DEBUG-level logging to stderr (overrides `--info`) | `false` |

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
  ],
  "parseFailures": [
    {
      "path": "src/main/java/Broken.java",
      "reason": "unterminated comment",
      "parser": "JavaParser"
    }
  ]
}
```

`parseFailures` is empty when every file parsed cleanly. Each entry names the canonical
parser (`JavaParser`, `MavenParser`, `XmlParser`, …) that gave up on the file along with
a short reason. A file can appear more than once if multiple parsers tried and failed on
it (the Maven POM → XML fallback path is the typical case).

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

Downloaded recipe JARs are cached under `~/.rewriterunner/cache/repository` (or `--cache-dir/repository`) and reused on subsequent runs. They are stored separately from the project's own dependencies, which always resolve from `~/.m2/repository`.

Only compile/runtime JARs are downloaded for recipe artifacts — test-scoped and provided-scoped transitive dependencies of recipes are skipped.

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
Duration values require units such as `30000ms`, `120s`, `10m`, `2h`, `1d`, or ISO-8601 values such as `PT2M`.

```yaml
repositories:
  - url: https://nexus.example.com/repository/maven-public
    username: ${NEXUS_USER}
    password: ${NEXUS_PASSWORD}

cacheDir: ~/.rewriterunner/cache

downloadThreads: 5   # parallel artifact download threads (default: 5)
processTimeout: 120s              # fallback LST build-tool subprocess timeout
pluginTimeout: 10m                # plugin-first rewriteDryRun/rewriteRun timeout
rewriteGradlePluginVersion: 7.32.1
rewriteMavenPluginVersion: 6.38.0
resolverConnectTimeout: 30s       # Maven Resolver TCP connection timeout
resolverRequestTimeout: 60s       # Maven Resolver socket/request timeout

parse:
  includeExtensions: [".java", ".kt", ".xml"]
  excludeExtensions: [".properties"]
  excludePaths:
    - "**/generated/**"
    - "**/build/**"
```

Environment variable placeholders (`${VAR_NAME}`) are expanded at runtime.

## Plugin-First Execution

Before building its own LST, the tool first attempts to apply the recipe through the official OpenRewrite plugin for the project's build tool:

- **Gradle**: injects a temporary init script that applies the `org.openrewrite.rewrite` plugin, then runs `rewriteDryRun` and, when not in `--dry-run` mode, `rewriteRun`
- **Maven**: invokes `org.openrewrite.maven:rewrite-maven-plugin` directly via `./mvnw`, `mvnw.cmd`, or system `mvn`

The dry-run goal always runs first so the tool can capture generated `rewrite.patch` files and format output in `diff`, `files`, or `report` mode. Maven multi-module builds may emit patches under each changed module's `target/site/rewrite/` directory, and those paths are reported relative to the project root. If no patches contain changes, the run short-circuits with no changes. If plugin execution succeeds with changes, the in-process LST pipeline is skipped entirely.

If the plugin path fails for any reason (no build tool, plugin resolution failure, build error, recipe unavailable, timeout), the tool falls through silently to the fallback pipeline below.

This plugin-first stage is enabled by default. Existing callers that need the previous direct LST pipeline behavior should pass `--skip-plugin-run` or set `skipPluginRun(true)`.

Use `--skip-plugin-run` to bypass this stage.

## Resilient Parsing Pipeline

The tool uses a four-stage fallback pipeline to build the LST, ensuring recipes run even on projects with broken builds.

### Stage 1 — Build tool classpath extraction
Invokes the project's own build tool as a subprocess to extract the full compile classpath:
- **Maven**: `mvn dependency:build-classpath -DincludeScope=compile`
- **Gradle**: injects a temporary init script that prints `runtimeClasspath` file paths

If successful, the resulting JAR list is passed to `JavaParser` for full type attribution (resolves imports, method signatures, type hierarchies).

### Stage 2 — Direct dependency resolution
If Stage 1 fails (broken build, no wrapper, timeout), the tool resolves dependencies without running the full build:
- **Maven**: parses `pom.xml` using `maven-model`. Includes `compile`, `provided`, and `test` scopes; excludes `runtime` and `system` scopes — provided and test dependencies are included to support compile-time and test-source type resolution while runtime-only artifacts are skipped.
- **Gradle**: runs `gradle dependencies` for the root project **and all declared subprojects** (discovered from `settings.gradle` / `settings.gradle.kts`), parsing the resolved dependency tree to get accurately resolved versions; falls back to best-effort static regex parsing of `build.gradle` / `build.gradle.kts` if Gradle cannot be invoked.

> **Note:** The `gradle dependencies` task only reports dependencies for the project it is applied to. Subprojects are queried explicitly (`:sub:dependencies`) so that multi-module builds are fully covered.

**Direct deps only, no POM traversal.** Stage 2 downloads JARs only for the dependencies explicitly declared in the build file — it does not traverse transitive dependency graphs or fetch transitive POM files. This avoids hundreds of extra HTTP requests on a cold run. Missing transitive types appear as `JavaType.Unknown`, which OpenRewrite handles gracefully.

Resolved JARs are cached in `~/.m2/repository` (Maven default), so artifacts already downloaded by the project's own build are reused without re-downloading. Extra repositories from the tool config are also consulted.

### Stage 3 — Static build file parse + POM traversal
If Stage 2 fails, the tool statically parses build files without invoking any subprocess, then resolves transitive dependencies via Maven Resolver POM traversal:
- **Maven**: discovers all modules via `pom.xml` module declarations and directory walk, then resolves the full transitive dependency graph.
- **Gradle**: statically parses `build.gradle(.kts)` files and version catalogs (`gradle/*.versions.toml`) using regex extraction, then resolves transitives via Maven Resolver POM traversal.

This stage provides full transitive dependency resolution without requiring a working build tool installation.

### Stage 4 — Local cache scan
If all previous stages fail, the tool scans local Maven and Gradle caches:
- `~/.m2/repository` for Maven-cached JARs
- `~/.gradle/caches/modules-*/files-*/` for Gradle-cached JARs

Unresolved types appear as `JavaType.Unknown` in the LST, but all structural, text-based, YAML, XML, and search recipes continue to work correctly.

## Supported File Types

| Extension | Parser |
|-----------|--------|
| `.java` | `JavaParser` (with classpath from fallback pipeline) |
| `.kt`, `.kts` | `KotlinParser` (`.kts` augmented with Gradle DSL classpath) |
| `.groovy`, `.gradle` | `GroovyParser` (`.gradle` augmented with Gradle DSL classpath) |
| `.yaml`, `.yml` | `YamlParser` |
| `.json` | `JsonParser` |
| `pom.xml` | `MavenParser` (fully resolved — parent POMs, property interpolation, BOM imports; enables full `rewrite-maven` recipe catalog) |
| `*.xml` (other) | `XmlParser` |
| `.properties` | `PropertiesParser` |
| `.toml` | `TomlParser` |
| `.hcl`, `.tf`, `.tfvars` | `HclParser` |
| `.proto` | `ProtoParser` |
| `.dockerfile`, `.containerfile`, `Dockerfile*`, `Containerfile*` | `DockerParser` (matched both by extension and by filename prefix) |

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
