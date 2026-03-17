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
- **No mocks** — subclass `BuildToolStage` / `DependencyResolutionStage` (both `open` with `open` methods) to override behavior in tests
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
class MyBuildToolStage : BuildToolStage() {
    override fun extractClasspath(projectDir: Path): List<Path> {
        return listOf(/* fake classpath */)
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
│   ├── LstBuilderTest.kt           ← parser routing, 3-stage pipeline, compile-on-demand, Gradle DSL classpath
│   ├── FileCollectorTest.kt        ← extension filtering, directory exclusion, glob patterns
│   ├── BuildToolStageTest.kt
│   ├── BuildToolStageBranchTest.kt
│   ├── DependencyResolutionStageTest.kt
│   ├── DependencyResolutionStageResolveClasspathTest.kt
│   ├── DirectParseStageTest.kt
│   ├── GatherDeclaredCoordinatesTest.kt
│   ├── GradleVersionParsingTest.kt
│   ├── JavaVersionDetectionTest.kt
│   ├── JavaVersionParsingTest.kt
│   ├── KotlinVersionDetectionTest.kt
│   └── MultiModuleJavaVersionTest.kt
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

## Internal API Access for Tests

- `DependencyResolutionStage.parseMavenDependencies` and `parseGradleDependencies` are `internal` — accessible from test code in the same module
- `BuildToolStage` and `DependencyResolutionStage` are `open` classes with `open` methods — subclass instead of mocking
- `VersionDetector.parseGradleVersionFromWrapper` and `LstBuilder.parseGradleVersionFromWrapper` are `internal` — the `LstBuilder` method is a thin delegation to `VersionDetector`; `GradleVersionParsingTest` calls it via `LstBuilder` for backward compatibility
- `LstBuilder.resolveGradleDslClasspath` is `internal` — thin delegation to `GradleDslClasspathResolver`; tested via `LstBuilderTest`
- `FileCollector` is `internal` — tested directly in `FileCollectorTest`
