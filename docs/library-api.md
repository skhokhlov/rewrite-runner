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
| `activeRecipe(String)` | `String` | **required** | Fully-qualified recipe name |
| `recipeArtifact(String)` | `String` | — | Add one Maven coordinate; may be called multiple times |
| `recipeArtifacts(List<String>)` | `List` | `[]` | Replace all recipe coordinates at once |
| `rewriteConfig(Path)` | `Path?` | `<projectDir>/rewrite.yaml` | Custom `rewrite.yaml` |
| `cacheDir(Path)` | `Path?` | from config / `~/.rewriterunner/cache` | Recipe JAR cache directory; resolved under `<cacheDir>/repository` |
| `configFile(Path)` | `Path?` | none | `rewrite-runner.yml` tool config |
| `dryRun(Boolean)` | `Boolean` | `false` | Run without writing files to disk |
| `includeExtensions(List<String>)` | `List` | `[]` | Restrict to these extensions; overrides config file |
| `excludeExtensions(List<String>)` | `List` | `[]` | Skip these extensions; overrides config file |

### Throws
- `IllegalArgumentException` — recipe not found in loaded JARs or classpath
- `IllegalStateException` — `activeRecipe` not set when `build()` is called

## RunResult

Return type of `RewriteRunner.run()`.

| Property | Type | Description |
|----------|------|-------------|
| `results` | `List<Result>` | Raw OpenRewrite results (one per changed file) |
| `changedFiles` | `List<Path>` | Files written to disk (empty when `dryRun = true`) |
| `projectDir` | `Path` | Resolved project directory |
| `hasChanges` | `Boolean` | `results.isNotEmpty()` |
| `changeCount` | `Int` | `results.size` |

## ToolConfig YAML

Config file (`rewrite-runner.yml`) loaded via `--config` CLI flag or `configFile()` builder method. Supports `${ENV_VAR}` interpolation and `~` expansion in all string fields.

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

### RepositoryConfig fields

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `url` | `String` | `""` | Full repository URL |
| `username` | `String?` | `null` | HTTP basic-auth username |
| `password` | `String?` | `null` | HTTP basic-auth password |

### ParseConfig fields

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `includeExtensions` | `List<String>` | `[]` | When non-empty, only parse these extensions |
| `excludeExtensions` | `List<String>` | `[]` | Remove these from the default set |
| `excludePaths` | `List<String>` | `[]` | Glob patterns (relative to project root) to skip |

**Precedence**: CLI flags `--include-extensions` / `--exclude-extensions` override config file `parse` settings.

## Exit Codes (CLI)

| Code | Meaning |
|------|---------|
| `0` | Success (recipe ran; changes may or may not exist) |
| `1` | Error (invalid args, unknown recipe, unknown output mode, unhandled exception) |

## KotlinDoc Coverage

KDoc is present on all public API classes and methods:
`RewriteRunner`, `RunResult`, `RecipeArtifactResolver`, `RecipeLoader`, `RecipeRunner`, `LstBuilder`, `ToolConfig`, `ParseConfig`, `RepositoryConfig`, `ResultFormatter`, `OutputMode`.
