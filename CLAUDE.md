# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Test Commands

```bash
# Build fat JAR
./gradlew shadowJar
# Output: build/libs/openrewrite-runner-1.0-SNAPSHOT-all.jar

# Run all tests
./gradlew test

# Run a single test class
./gradlew test --tests "org.example.output.ResultFormatterTest"

# Run a single test method (use backtick-quoted name for Kotlin)
./gradlew test --tests "org.example.cli.RunCommandTest.default output mode is diff"

# Run the tool locally
java -jar build/libs/openrewrite-runner-1.0-SNAPSHOT-all.jar --help
```

## Architecture

The tool is a fat JAR CLI that runs OpenRewrite recipes against arbitrary project directories without requiring a working build system.

**Entry point**: `Main.kt` → `CommandLine(RunCommand()).execute(*args)`. `RunCommand` is the **root command** (not a subcommand) — all options are passed directly without a "run" prefix.

**Execution pipeline in `RunCommand.call()`**:
1. Load tool config (`ToolConfig`)
2. Resolve recipe JARs from Maven coordinates (`RecipeArtifactResolver`)
3. Load recipe from JARs + optional `rewrite.yaml` (`RecipeLoader`)
4. Build LST — parse source files into OpenRewrite's tree representation (`LstBuilder`)
5. Run the recipe (`RecipeRunner`)
6. Optionally write changes to disk (unless `--dry-run`)
7. Format output (`ResultFormatter`)

**3-stage LST building** (`lst/` package):
- **Stage 1** (`BuildToolStage`): Subprocess Maven/Gradle to extract compile classpath. Falls through on failure.
- **Stage 2** (`DependencyResolutionStage`): Parse `pom.xml`/`build.gradle` + Maven Resolver to download JARs. Falls through on failure.
- **Stage 3** (`DirectParseStage`): Scan `~/.m2` and `~/.gradle/caches` for already-cached JARs matching declared deps. Always succeeds (possibly with empty list).

`LstBuilder` routes files by extension to the correct OpenRewrite parser. Excludes `build/`, `target/`, `node_modules/`, `.git/`, `.gradle/` by default.

**Key dependency versions** (chosen for Gradle 9.0.0 + JDK 25 compatibility):
- Kotlin: `2.3.0` (2.1.x crashes on JDK 25)
- Shadow plugin: `com.gradleup.shadow:9.0.0` (`com.github.johnrengelman.shadow` is incompatible with Gradle 9)
- Maven Resolver: `1.9.22` (version 2.x doesn't exist yet)
- JVM toolchain: 21 (set via `kotlin { jvmToolchain(21) }`)
- OpenRewrite: `8.49.0` via `rewrite-recipe-bom:3.5.0`

## Important Implementation Notes

- `InMemoryLargeSourceSet` is in `org.openrewrite.internal` (not the top-level package)
- `BuildToolStage` and `DependencyResolutionStage` are `open` classes with `open` methods to allow test subclassing
- `DependencyResolutionStage.parseMavenDependencies` and `parseGradleDependencies` are `internal` for direct test access
- `ResultFormatter` has a secondary constructor accepting `PrintWriter` for picocli integration; `RunCommand` uses `@Spec` to get picocli's output `PrintWriter` and passes it to `ResultFormatter`
- Creating OpenRewrite `Result` objects in tests **requires** running through a real recipe pipeline (`Recipe.run()` with `PlainTextVisitor`) — calling `PlainText.withText()` outside a visitor context throws `UnknownSourceFileChangeException`
