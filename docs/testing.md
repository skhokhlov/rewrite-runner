# Testing

## TDD Requirement

**Test-Driven Development is required.** For every feature or bug fix:
1. Write a failing test that covers the desired behavior
2. Implement the minimum code to make it pass
3. Refactor if needed, keeping tests green

This applies at all layers: unit tests in `core/`, integration tests in `cli/`.

## Test Conventions

- **JUnit 5** with `@TempDir` for temporary directories
- **`kotlin.test` assertions**: `assertEquals`, `assertTrue`, `assertFalse`, `assertNull`, `assertNotEquals`
- **No mocks** — subclass `ClasspathStage` implementations and override `resolve(projectDir, parseFailures)` to drive classpath behavior in tests
- Some integration tests accept "success path OR expected fallback" to handle environment variability (e.g., no Maven in CI)

## Integration Tests

`BaseIntegrationTest.runCli()` captures stdout, stderr, and exit code by invoking `RunCommand` in-process.

```kotlin
class MyIntegrationTest : BaseIntegrationTest() {
    @Test
    fun `some behavior`(@TempDir projectDir: Path) {
        // set up project files in projectDir
        val result = runCli(
            "--project-dir", projectDir.toString(),
            "--active-recipe", "org.openrewrite.java.format.AutoFormat",
            "--dry-run"
        )
        assertEquals(0, result.exitCode)
        assertTrue(result.stdout.contains("..."))
    }
}
```

## Unit Test Patterns

### Creating OpenRewrite Result objects

Calling `PlainText.withText()` outside a visitor context throws `UnknownSourceFileChangeException`.
**Always** create `Result` objects by running through a real recipe pipeline:

```kotlin
// Use PlainTextVisitor inside Recipe.run() — do NOT call withText() directly
val results = Recipe.run(InMemoryLargeSourceSet(listOf(sourceFile)), executionContext)
// InMemoryLargeSourceSet is in org.openrewrite.internal (not top-level)
```

### Overriding LST stages in tests

```kotlin
class MyProjectBuildStage : ProjectBuildStage(NoOpRunnerLogger) {
    override fun resolve(
        projectDir: Path,
        parseFailures: MutableList<ParseFailure>
    ): ClasspathResolutionResult {
        return ClasspathResolutionResult(listOf(Path.of("/tmp/fake.jar")))
    }
}
```

### Capturing log output in tests

```kotlin
val logger = LoggerFactory.getLogger(MyClass::class.java) as ch.qos.logback.classic.Logger
val appender = ch.qos.logback.core.read.ListAppender<ILoggingEvent>()
appender.start()
logger.addAppender(appender)
try {
    // ... exercise code
    assertTrue(appender.list.any { it.message.contains("expected log") })
} finally {
    logger.detachAppender(appender)
}
```

## Test File Locations

```
core/src/test/kotlin/.../
├── config/ToolConfigTest.kt
├── lst/
│   ├── LstBuilderTest.kt           ← parser routing, 4-stage pipeline, compile-on-demand, Gradle DSL classpath
│   ├── ProjectBuildStageTest.kt
│   ├── ProjectBuildStageBranchTest.kt
│   ├── DependencyResolutionStageTest.kt
│   ├── DependencyResolutionStageResolveClasspathTest.kt
│   ├── BuildFileParseStageTest.kt
│   ├── LocalRepositoryStageTest.kt
│   ├── GatherDeclaredCoordinatesTest.kt
│   ├── GradleVersionParsingTest.kt
│   ├── JavaVersionDetectionTest.kt
│   ├── JavaVersionParsingTest.kt
│   ├── KotlinVersionDetectionTest.kt
│   ├── MultiModuleJavaVersionTest.kt
│   └── utils/
│       └── FileCollectorTest.kt    ← extension filtering, directory exclusion, glob patterns
└── output/ResultFormatterTest.kt

cli/src/test/kotlin/.../
├── cli/RunCommandTest.kt
└── integration/
    ├── BaseIntegrationTest.kt
    ├── JavaProjectIntegrationTest.kt
    ├── KotlinProjectIntegrationTest.kt
    ├── YamlProjectIntegrationTest.kt
    ├── JsonProjectIntegrationTest.kt
    ├── XmlProjectIntegrationTest.kt
    ├── PropertiesProjectIntegrationTest.kt
    └── MultiLanguageProjectIntegrationTest.kt
```

## Test Lanes

Tests are split into three lanes by Gradle `Test.filter` class-name pattern. Each lane has a dedicated CI job; in CI they run sequentially so a failure shows up at the right stage.

| Lane | Gradle task | Scope | When |
|------|-------------|-------|------|
| Unit | `:core:test`, `:cli:test` | `core` module + `RunCommandTest`; excludes `*IntegrationTest` | `check` lifecycle, CI job 1 |
| Integration (fake wrappers) | `:cli:testIntegration` | All `*IntegrationTest` classes **except** `PluginRealExecutionIntegrationTest`; offline-safe | CI job 2 |
| Integration (real plugins) | `:cli:testRealPlugin` | `PluginRealExecutionIntegrationTest` only; downloads Maven/Gradle distributions and pulls live plugin artifacts from Maven Central | CI job 3 |

`:cli:check` does **not** depend on `:cli:testIntegration` or `:cli:testRealPlugin` — those are explicit. Run `./gradlew check :cli:testIntegration` for a full offline verification before pushing; add `:cli:testRealPlugin` for the live-plugin pass.

Class-name conventions:
- Every integration test class name ends with `IntegrationTest` (enforced by `failOnNoMatchingTests = true` on `testIntegration` / `testRealPlugin`).
- Add a new offline integration test by simply creating an `*IntegrationTest` class under `cli/src/test/kotlin/.../integration/`.

## Stage 0 Plugin Tests

Stage 0 (plugin-first execution) is covered by two complementary test tiers:

| Tier | Class | How | Lane |
|------|-------|-----|------|
| Fake-wrapper (fast) | `PluginFirstIntegrationTest` | Shell scripts simulate plugin output | `:cli:testIntegration` |
| Real-wrapper (slow) | `PluginRealExecutionIntegrationTest` | Real Maven/Gradle distributions downloaded by `ToolchainCache` | `:cli:testRealPlugin` |

The fake-wrapper tier gives fast feedback on orchestration logic and CLI flag wiring. The real-wrapper tier verifies that the pinned plugin versions actually exist on Maven Central, that the generated Gradle init-script DSL is accepted by the live plugin (including Gradle 9 compatibility, exercised via the version pulled from the project's `gradle-wrapper.properties`), and that `PatchParser` can parse the real plugin's output for both single-project and multi-module builds.

**License envelope** — the real-wrapper suite only loads recipes from `rewrite-core` (Apache 2.0). The `org.openrewrite.recipe.*` artifacts (`rewrite-static-analysis`, `rewrite-spring`, `rewrite-migrate-java`, …) ship under the Moderne Source Available license, which is incompatible with this project; do not add a scenario that pulls one in via `recipeArtifacts`. The `--recipe-artifact` coordinate-resolution code path is covered by `RecipeArtifactResolver` unit tests against permissive coordinates.

**Stage 0 proof** — the real-wrapper suite calls `RewriteRunner` directly (not the CLI) and asserts that `RunResult.executionDiagnostics.stageUsed == UsedExecutionStage.PLUGIN`. Without this assertion a Stage 0 regression that silently fell through to the LST pipeline would still satisfy file-content checks and stay green.

**Scenario shape** — both tiers consume `PluginScenario` objects from `PluginScenarios.kt`. Each scenario defines the project layout, recipe, and expected outcomes so a layout change is a one-place edit.

**Task partitioning** — all three test tasks share one source set; Gradle `Test.filter` selects which class runs in each lane (see the Test Lanes table above). There is no Kotest tag involved.

**Running locally:**

```bash
# Unit only (fast):
./gradlew :cli:test

# Full offline verification (unit + fake-wrapper integration):
./gradlew check :cli:testIntegration

# Real-plugin lane (downloads toolchains + plugins on first run; ~5 min warm):
./gradlew :cli:testRealPlugin

# Single scenario smoke:
./gradlew :cli:testRealPlugin --tests "*maven multi-module*"
```

**Skip conditions for the real-wrapper suite:**
- Windows (POSIX shell wrappers not supported)
- Maven Central unreachable at test start (assumption skips with a message, not a failure)

**Toolchain cache** — Maven and Gradle distributions are cached under `cli/build/test-cache/toolchains/` (`./gradlew clean` clears them). The Gradle distribution version tracks the project's own `gradle/wrapper/gradle-wrapper.properties` automatically — `cli/build.gradle.kts` reads it at configuration time and forwards it to the test JVM as `-Drewriterunner.test.gradleVersion=<v>`. Maven is pinned in `ToolchainCache.kt` (`MAVEN_VERSION`) and is the only manual bump knob.

## Internal API Access for Tests

- `DependencyResolutionStage.parseMavenDependencies` and `parseGradleDependencies` are `internal` — accessible from test code in the same module
- `ProjectBuildStage`, `DependencyResolutionStage`, and `BuildFileParseStage` are `open` `ClasspathStage` implementations — subclass instead of mocking
- `VersionDetector.parseGradleVersionFromWrapper` and `LstBuilder.parseGradleVersionFromWrapper` are `internal` — the `LstBuilder` method is a thin delegation to `VersionDetector`; `GradleVersionParsingTest` calls it via `LstBuilder` for backward compatibility
- `LstBuilder.resolveGradleDslClasspath` is `internal` — thin delegation to `GradleDslClasspathResolver`; tested via `LstBuilderTest`
- `FileCollector` is `internal` — tested directly in `FileCollectorTest`
