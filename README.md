# rewrite-runner

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
        .dryRun(true)
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

### Formatting results

If you want formatted output (diff, file list, or JSON report), use `ResultFormatter` directly:

```kotlin
import io.github.skhokhlov.rewriterunner.output.OutputMode
import io.github.skhokhlov.rewriterunner.output.ResultFormatter

val result = runner.run()
ResultFormatter(OutputMode.DIFF).format(result.results, result.projectDir)
```

### Builder reference

| Method | Type | Default | Description |
|--------|------|---------|-------------|
| `projectDir(Path)` | required | `.` (cwd) | Project root to analyse |
| `activeRecipe(String)` | required | — | Fully-qualified recipe name |
| `recipeArtifact(String)` | optional (repeatable) | — | Maven coordinate of a recipe JAR |
| `recipeArtifacts(List<String>)` | optional | — | Set all recipe artifact coordinates at once |
| `rewriteConfig(Path)` | optional | `<projectDir>/rewrite.yaml` | Custom `rewrite.yaml` path |
| `cacheDir(Path)` | optional | `~/.rewriterunner/cache` | JAR download cache directory |
| `configFile(Path)` | optional | — | Path to `rewrite-runner.yml` |
| `dryRun(Boolean)` | optional | `false` | Analyse without writing to disk |
| `includeExtensions(List<String>)` | optional | all supported | File extensions to parse |
| `excludeExtensions(List<String>)` | optional | — | File extensions to skip |

## CLI Reference

```
Usage: rewrite-runner [-h] [--dry-run] [--active-recipe=<recipe>]
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
| `--cache-dir` | Directory for caching downloaded JARs | `~/.rewriterunner/cache` |
| `--config` | Path to tool config file (`rewrite-runner.yml`) | — |
| `--dry-run` | Run recipe but do not write changes to disk | `false` |
| `--include-extensions` | Comma-separated file extensions to parse (e.g. `.java,.kt`) | all supported |
| `--exclude-extensions` | Comma-separated file extensions to skip | — |

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

Downloaded JARs are cached in `~/.rewriterunner/cache` (or `--cache-dir`) and reused on subsequent runs.

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

Create `rewrite-runner.yml` to configure repositories and caching for your environment:

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

Environment variable placeholders (`${VAR_NAME}`) are expanded at runtime. Pass the config file with `--config rewrite-runner.yml`.

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

Downloads are cached locally and respect configured extra repositories.

### Stage 3 — Local cache scan
If dependency resolution also fails, the tool scans local Maven and Gradle caches:
- `~/.m2/repository` for Maven-cached JARs
- `~/.gradle/caches/modules-*/files-*/` for Gradle-cached JARs

Unresolved types appear as `JavaType.Unknown` in the LST, but all structural, text-based, YAML, XML, and search recipes continue to work correctly.

## Supported File Types

| Extension | Parser |
|-----------|--------|
| `.java` | `JavaParser` (with classpath from 3-stage pipeline) |
| `.kt` | `KotlinParser` (with classpath from 3-stage pipeline) |
| `.groovy` | `GroovyParser` (with classpath from 3-stage pipeline) |
| `.yaml`, `.yml` | `YamlParser` |
| `.json` | `JsonParser` |
| `.xml` | `XmlParser` |
| `.properties` | `PropertiesParser` |

The parsed file set is configurable via `--include-extensions`, `--exclude-extensions`, and the `parse` section of `rewrite-runner.yml`.

## Development

```bash
# Run all tests
./gradlew test

# Run a specific test class
./gradlew test --tests "io.github.skhokhlov.rewriterunner.output.ResultFormatterTest"

# Build and run locally
./gradlew shadowJar
java -jar build/libs/rewrite-runner-1.0-SNAPSHOT-all.jar --help
```
