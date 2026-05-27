# Context

## Glossary

- **Build unit**: A directory where rewrite-runner invokes Maven or Gradle for classpath resolution,
  paired with the build tool used there.
- **Root descriptor**: A build descriptor at the project root: `pom.xml` for Maven, or
  `settings.gradle(.kts)` / `build.gradle(.kts)` for Gradle.
- **Root-less monorepo**: A repository whose project root has no build descriptor for a tool, but one
  or more subdirectories do.
- **Top-most discovery**: Build-unit discovery rule that selects the first descriptor directory found
  on a path and does not descend into that directory's children.
