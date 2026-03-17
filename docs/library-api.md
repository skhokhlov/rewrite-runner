# Library API

## RewriteRunner

Programmatic entry point. Use `RewriteRunner.builder()` to configure, `.build().run()` to execute.

```kotlin
val result = RewriteRunner.builder()
    .projectDir(Paths.get("/path/to/project"))
    .activeRecipe("org.openrewrite.java.format.AutoFormat")
    .recipeArtifact("org.openrewrite.recipe:rewrite-java:LATEST")
    .dryRun(true)
    .build()
    .run()
```

### Builder Methods

| Method | Type | Default | Description |
|--------|------|---------|-------------|
| `projectDir(Path)` | `Path` | `"."` | Project root — must exist when `run()` is called |
| `activeRecipe(String)` | `String` | **required** | Fully-qualified recipe name (e.g. `org.openrewrite.java.format.AutoFormat`) |
| `recipeArtifact(String)` | `String` | — | Add one Maven coordinate (`groupId:artifactId:version`); may be called multiple times; `LATEST` accepted as version |
| `recipeArtifacts(List<String>)` | `List` | `[]` | Replace all recipe coordinates at once |
| `rewriteConfig(Path)` | `Path?` | `<projectDir>/rewrite.yaml` | Path to a `rewrite.yaml` for custom composite recipes |
| `rewriteConfigContent(String)` | `String?` | — | Raw `rewrite.yaml` content as a string; takes precedence over `rewriteConfig` when both are set |
| `cacheDir(Path)` | `Path?` | from config / `~/.rewriterunner/cache` | Recipe JAR cache directory; stored under `<cacheDir>/repository`. Project deps always use `~/.m2/repository`. |
| `configFile(Path)` | `Path?` | `<projectDir>/rewriterunner.yml`, then `~/.rewriterunner/rewriterunner.yml` | `rewriterunner.yml` tool config (case-insensitive name match). Pass `null` to use auto-discovery. |
| `dryRun(Boolean)` | `Boolean` | `false` | Run without writing files to disk |
| `includeExtensions(List<String>)` | `List` | `[]` | Restrict to these extensions (e.g. `[".java", ".kt"]`); overrides `parse.includeExtensions` from config file |
| `excludeExtensions(List<String>)` | `List` | `[]` | Skip these extensions; overrides `parse.excludeExtensions` from config file |
| `excludePaths(List<String>)` | `List` | `[]` | Glob patterns (relative to project root) to skip during parsing; overrides `parse.excludePaths` from config file |
| `includeMavenCentral(Boolean)` | `Boolean?` | from config / `true` | Include Maven Central as a remote repository. Set `false` for air-gapped or enterprise environments. |
| `repository(RepositoryConfig)` | — | — | Add one extra Maven repository; accumulated, combined with config file repos |
| `repositories(List<RepositoryConfig>)` | `List` | `[]` | Replace all extra Maven repositories; combined with config file repos |

### Throws
- `IllegalArgumentException` — recipe not found in loaded JARs or classpath
- `IllegalStateException` — `activeRecipe` not set when `build()` is called

### Resource management

`RewriteRunner.run()` automatically closes the `URLClassLoader` created over recipe JARs
once the recipe has finished executing and all file writes are complete. No manual cleanup
is required by callers. Each call to `run()` creates and promptly releases its own
classloader, so calling `run()` multiple times on the same instance is safe and does not
accumulate file descriptors.

## RunResult

Return type of `RewriteRunner.run()`.

| Property | Type | Description |
|----------|------|-------------|
| `results` | `List<Result>` | Raw OpenRewrite results (one per changed file). Empty means no changes. |
| `changedFiles` | `List<Path>` | Files written to disk during this run. Empty when `dryRun = true` or no changes. |
| `projectDir` | `Path` | Resolved project directory (same as `Builder.projectDir`) |
| `hasChanges` | `Boolean` | `true` when the recipe produced at least one change, regardless of `dryRun` |
| `changeCount` | `Int` | Number of changed source files |

Each `org.openrewrite.Result` in `results` exposes:
- `before` — the source file before the recipe (`null` for newly created files)
- `after` — the source file after the recipe (`null` for deleted files)
- `diff()` — a precomputed unified diff string
- `before?.sourcePath` / `after?.sourcePath` — relative path within the project

```kotlin
result.results.forEach { r ->
    println("=== ${r.after?.sourcePath ?: r.before?.sourcePath} ===")
    if (r.before == null) println("(new file)")
    if (r.after == null) println("(deleted)")
    println(r.diff())
}
```

## ResultFormatter and OutputMode

`ResultFormatter` formats a `RunResult` in the same three modes available in the CLI. Library consumers that only need to inspect results programmatically can skip this class and work with `RunResult.results` directly.

```kotlin
import io.github.skhokhlov.rewriterunner.output.OutputMode
import io.github.skhokhlov.rewriterunner.output.ResultFormatter

val result = runner.run()

// Print unified diffs to stdout
ResultFormatter(OutputMode.DIFF).format(result.results, result.projectDir)

// Print one changed-file path per line to stdout
ResultFormatter(OutputMode.FILES).format(result.results, result.projectDir)

// Write openrewrite-report.json to the project directory
ResultFormatter(OutputMode.REPORT).format(result.results, result.projectDir)
```

### OutputMode

| Value | Behaviour |
|-------|-----------|
| `DIFF` | Prints a unified diff for each changed file to stdout |
| `FILES` | Prints one changed-file path per line to stdout |
| `REPORT` | Writes `openrewrite-report.json` to the directory passed as `reportDir` (defaults to `.`) |

### Constructors

```kotlin
// Primary constructor — writes to System.out
ResultFormatter(outputMode: OutputMode, out: PrintStream = System.out)

// Secondary constructor — accepts a PrintWriter (used by the CLI's picocli @Spec writer)
ResultFormatter(outputMode: OutputMode, writer: PrintWriter)
```

### format()

```kotlin
fun format(results: List<Result>, reportDir: Path? = null)
```

- `results` — the list from `RunResult.results`. May be empty; prints `"No changes produced."` / `"No files changed."` for `DIFF` / `FILES` modes.
- `reportDir` — directory where `openrewrite-report.json` is written. Ignored for `DIFF` and `FILES` modes. Defaults to the current directory (`.`).

## ToolConfig YAML

Config file (`rewriterunner.yml`) loaded via `--config` CLI flag or `configFile()` builder method. File name matching is case-insensitive. Auto-discovered from `<projectDir>/rewriterunner.yml` (project-level) then `~/.rewriterunner/rewriterunner.yml` (global fallback) when not explicitly provided. Supports `${ENV_VAR}` interpolation and `~` expansion in all string fields.

```yaml
cacheDir: ~/.rewriterunner/cache   # default

repositories:
  - url: https://nexus.example.com/repository/maven-public
    username: ${NEXUS_USER}         # env var interpolated at load time
    password: ${NEXUS_PASS}

parse:
  includeExtensions: [".java", ".kt"]   # restrict to these; overridden by CLI flag
  excludeExtensions: [".xml"]           # skip these; overridden by CLI flag
  excludePaths: ["generated/", "vendor/"] # glob patterns relative to project root
```

### ToolConfig fields

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `cacheDir` | `String` | `~/.rewriterunner/cache` | Recipe JAR cache root; `~` and env vars expanded. Recipes are stored under `<cacheDir>/repository`. Project dependencies always resolve from `~/.m2/repository`. |
| `repositories` | `List<RepositoryConfig>` | `[]` | Extra Maven repos for resolution |
| `parse` | `ParseConfig` | defaults | File inclusion/exclusion config |
| `includeMavenCentral` | `Boolean` | `true` | Include Maven Central as a remote repository. Set `false` to restrict to only the repositories listed in `repositories`. |

### RepositoryConfig fields

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `url` | `String` | `""` | Full repository URL |
| `username` | `String?` | `null` | HTTP basic-auth username |
| `password` | `String?` | `null` | HTTP basic-auth password |

To provide extra repositories programmatically (without a `rewriterunner.yml` file):

```kotlin
import io.github.skhokhlov.rewriterunner.config.RepositoryConfig

val runner = RewriteRunner.builder()
    .projectDir(Paths.get("/path/to/project"))
    .activeRecipe("org.openrewrite.java.format.AutoFormat")
    .recipeArtifact("org.openrewrite.recipe:rewrite-static-analysis:LATEST")
    .repository(RepositoryConfig(
        url = "https://nexus.example.com/repository/maven-public",
        username = System.getenv("NEXUS_USER"),
        password = System.getenv("NEXUS_PASS")
    ))
    .includeMavenCentral(false)   // use only the Nexus repository above
    .build()
```

### ParseConfig fields

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `includeExtensions` | `List<String>` | `[]` | When non-empty, only parse these extensions |
| `excludeExtensions` | `List<String>` | `[]` | Remove these from the default set |
| `excludePaths` | `List<String>` | `[]` | Glob patterns (relative to project root) to skip |

**Precedence**: CLI flags `--include-extensions` / `--exclude-extensions` override config file `parse` settings.

### Automatically excluded directories

The following directories are **always** skipped during the file-system walk, regardless of `includeExtensions`, `excludeExtensions`, or `excludePaths` configuration:

`.git`, `build`, `target`, `node_modules`, `.gradle`, `.idea`, `out`, `dist`

Use `parse.excludePaths` (or `excludePaths()` in the builder) to skip additional directories or path patterns.

## Exit Codes (CLI)

| Code | Meaning |
|------|---------|
| `0` | Success (recipe ran; changes may or may not exist) |
| `1` | Error (invalid args, unknown recipe, unknown output mode, unhandled exception) |

## KotlinDoc Coverage

KDoc is present on all public API classes and methods:
`RewriteRunner`, `RunResult`, `RecipeArtifactResolver`, `RecipeLoader`, `RecipeRunner`, `LstBuilder`, `ToolConfig`, `ParseConfig`, `RepositoryConfig`, `ResultFormatter`, `OutputMode`.
