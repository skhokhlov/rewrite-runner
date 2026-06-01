# Context

## Glossary

- **Build unit**: A directory where rewrite-runner invokes Maven or Gradle for classpath resolution,
  paired with the build tool used there. Build units are non-exclusive: a root with both Maven and
  Gradle descriptors can produce one unit for each tool.
- **Build-tool identity**: The exclusive, root-level build-tool verdict used only for provenance
  markers. `detectBuildTool` returns Gradle, Maven, or None; Gradle wins with a warning when both
  Maven and Gradle root descriptors exist.
- **Classpath stage**: A `ClasspathStage` implementation in the ordered LST classpath-resolution
  chain. `resolve(projectDir, parseFailures)` returns a `ClasspathResolutionResult` to win or
  `null` to fall through to the next stage.
- **Root descriptor**: A build descriptor at the project root: `pom.xml` for Maven, or
  `settings.gradle(.kts)` / `build.gradle(.kts)` for Gradle.
- **Root-less monorepo**: A repository whose project root has no build descriptor for a tool, but one
  or more subdirectories do.
- **Top-most discovery**: Build-unit discovery rule that selects the first descriptor directory found
  on a path and does not descend into that directory's children.
