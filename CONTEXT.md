# Context

## Glossary

- **Build unit**: A directory where rewrite-runner invokes Maven or Gradle for classpath resolution,
  paired with the build tool used there. Build units are non-exclusive: a root with both Maven and
  Gradle descriptors can produce one unit for each tool.
- **Build-tool identity**: The exclusive, root-level build-tool verdict used only for provenance
  markers. `detectBuildTool` returns Gradle, Maven, or None; Gradle wins with a warning when both
  Maven and Gradle root descriptors exist.
- **Apply**: The step that takes OpenRewrite `Result` objects and attempts to materialize them on
  the configured target, recording per-file successes and failures rather than treating recipe
  execution alone as proof that disk changes landed.
- **Change writer**: The seam responsible for applying recipe results to a target. Production uses
  the disk-backed writer; tests can inject an in-memory writer through the internal builder hook.
- **Classpath stage**: A `ClasspathStage` implementation in the ordered LST classpath-resolution
  chain. `resolve(projectDir, parseFailures)` returns a `ClasspathResolutionResult` to win or
  `null` to fall through to the next stage.
- **Stage 0 / Plugin-first execution**: The default execution path, in which rewrite-runner shells
  out to the official OpenRewrite Gradle/Maven plugin to apply the recipe, short-circuiting the
  in-process engine on success. _Avoid_: "plugin mode", "external run". See [[0006-plugin-first-execution]].
- **LST fallback**: The in-process four-stage engine (`LstBuilder` + classpath stages) that runs
  when Stage 0 is skipped, fails, or finds no build tool. It is the fallback, not the primary path.
  _Avoid_: "LST pipeline" as a synonym for the whole tool — it is one of two execution paths.
- **Orphan unit**: A build unit in a [[#root-less-monorepo]] subdirectory that Stage 0 runs the
  plugin in when the root has no descriptor. Each orphan unit's diffs are rebased to the
  repository root.
- **Hybrid run**: A single run in which Stage 0 produced diffs for some orphan units while the
  LST fallback handled the remainder of the project. Stage 0 wins on any per-path collision, and
  the run still reports itself as Stage 0. _Avoid_: "partial run". See [[0006-plugin-first-execution]].
- **Estimated time saved**: A heuristic estimate of manual developer effort avoided by a recipe run,
  not measured wall-clock. It is the sum, over every changed file, of the effort OpenRewrite attributes
  to the change — for each file the total `getEstimatedEffortPerOccurrence()` (default 5 minutes) of the
  recipes that made it. This is OpenRewrite's own figure (`org.openrewrite.Result.getTimeSavings()`); it is
  never recomputed by rewrite-runner. Both execution paths surface the same number: the LST path sums it
  from in-process `Result` objects, and the Stage 0 plugin path reads the plugin-reported value from an
  exported `SourcesFileResults` data table when present, otherwise from the plugin's `Estimate time saved`
  output line.
- **Exclusion path**: A glob pattern from `--exclude-paths` / `Builder.excludePaths(...)` /
  `parse.excludePaths` selecting files to skip. In multi-module Stage 0 runs, upstream build
  plugins do the final matching; the real-plugin regression suite pins Gradle and Maven Java
  source exclusions as repository-root-relative for rewrite-runner's invocation shape. Prefer
  `**/`-anchored globs when a pattern must reach subprojects in both tools. See
  [[0007-exclusion-only-path-filtering]].
- **Plain-text mask**: A glob pattern (relative to project root) selecting files to parse with
  `PlainTextParser`. The configured list replaces the built-in default. Forwarded to both Stage 0
  and the LST fallback so both paths select the same files. Specialized parsers take precedence: a
  file a real parser claims, such as `Dockerfile*` for `DockerParser`, is never treated as plain
  text on the LST path. See also **Specialized ownership**.
- **Root descriptor**: A build descriptor at the project root: `pom.xml` for Maven, or
  `settings.gradle(.kts)` / `build.gradle(.kts)` for Gradle.
- **Root-less monorepo**: A repository whose project root has no build descriptor for a tool, but one
  or more subdirectories do.
- **Specialized ownership**: The Docker/HCL/protobuf file types rewrite-runner parses with its own
  classpath-free specialized parsers, partitioned out of Stage 0 by exclusion. A file type belongs
  here only when Stage 0 structurally cannot handle it and it is safe to parse in isolation.
- **Top-most discovery**: Build-unit discovery rule that selects the first descriptor directory found
  on a path and does not descend into that directory's children.
- **Write outcome**: The per-file result of the apply step: successful created/modified/deleted
  changes plus failures with their path, kind, and cause.
- **Runner JVM**: The JVM running rewrite-runner itself (the fat JAR). It hosts the
  [[#lst-fallback]] in-process engine, so it is the heap sized by `java -Xmx… -jar`. Distinct from
  the **Stage 0 subprocess JVM**. _Avoid_: conflating its `-Xmx` with the plugin's heap.
- **Stage 0 subprocess JVM**: The `gradlew`/`mvnw` JVM where OpenRewrite runs during
  [[#stage-0-plugin-first-execution]]. Its heap is governed by **plugin JVM args**, never by the
  runner JVM's `-Xmx`.
- **Plugin JVM args**: JVM arguments forwarded to the Stage 0 subprocess JVM via
  `--plugin-jvm-args` / `pluginJvmArgs` / `Builder.pluginJvmArgs(...)`. Gradle receives them as a
  command-line `-Dorg.gradle.jvmargs` (highest precedence, **replaces** the project's value); Maven
  receives them appended to `MAVEN_OPTS` (default-only — a project `.mvn/jvm.config` wins). Empty by
  default. _Avoid_: assuming they are merged with the project's Gradle args, or that they can
  override a Maven `.mvn/jvm.config`.
- **Combined memory budget**: The peak memory of a run. Because Stage 0 and the LST fallback heaps
  are sequential in time, the bare-host constraint is `max(runner, plugin)` plus overhead — except a
  large runner `-Xms`/`AlwaysPreTouch` makes them coexist. In a container both JVMs share one cgroup,
  so their combined RSS is what the kernel limits. Currently a documented model only, not enforced
  in code. See [[0009-fork-lst-worker-jvm]].
