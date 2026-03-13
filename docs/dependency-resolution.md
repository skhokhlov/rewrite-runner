# Dependency Resolution

Two independent dependency resolution flows run per `RewriteRunner.run()` invocation: one for **recipe artifacts** and one for **project classpath**. They share the same underlying engine (Maven Resolver / Eclipse Aether) but use separate `AetherContext` instances, different local repositories, and different resolution strategies tailored to each use case.

---

## Recipe artifact resolution

**Code:** `RecipeArtifactResolver` · `RewriteRunner` lines ~87–108

### Why

OpenRewrite recipes are distributed as Maven artifacts. They must be downloaded and placed on the classpath before the recipe can be loaded and executed. Recipe artifacts are not part of the project being analysed — they are tool-level dependencies, similar to Maven plugins or Gradle build plugins.

### How

1. **Coordinates** come from `--recipe-artifact` flags (or the `ToolConfig.repositories` block) in the form `groupId:artifactId:version`. `LATEST` is supported and resolves to the highest available release via a `VersionRangeRequest`.

2. **All coordinates are resolved together** in a single `CollectRequest + DependencyRequest`. This matters because multiple recipe artifacts may share transitive dependencies at different versions (e.g. both depend on `rewrite-core` but declare different versions). Submitting them as one graph lets Maven Resolver's `ClassicConflictResolver` (nearest-wins) apply across the combined graph and produce a deduplicated, conflict-free JAR list.

3. **Only compile/runtime JARs are needed.** Recipes are loaded into the JVM via a `URLClassLoader` — you only need the JARs that must be on the classpath at runtime. Test-scoped deps of the recipe's own build are irrelevant.

4. **Session-level scope pruning** (`excludeScopesFromGraph = listOf("test", "provided", "system")`) adds a `ScopeDependencySelector` to the Aether session. Nodes with excluded scopes are pruned from the dependency graph *during collection*, so their POM files are never downloaded. This is more efficient than a post-collection `ScopeDependencyFilter`, which would still traverse and fetch those POMs before discarding the results.

### Local repository

`<cacheDir>/repository` (defaults to `~/.rewriterunner/cache/repository`)

Recipe JARs are stored in the tool's own cache directory so they never appear in the user's Maven local repository (`~/.m2`). This prevents them from interfering with the user's own builds.

---

## Project classpath resolution

**Code:** `DependencyResolutionStage` (Stage 2 of the LST pipeline) · `RewriteRunner` lines ~97–103

### Why

OpenRewrite parsers (`JavaParser`, `KotlinParser`, `GroovyParser`) need the project's compile classpath to resolve type references. Without it, every external type appears as `JavaType.Unknown` and recipes that inspect or rewrite typed code produce incorrect results or miss matches entirely. Test-scoped sources (files under `src/test/`) also need their test-scoped dependencies (e.g. JUnit, Mockito) to be type-resolved correctly.

Stage 2 is a fallback: it runs only when Stage 1 (`BuildToolStage`, which invokes `mvn`/`gradle` as a subprocess) fails or is unavailable.

### How

#### Coordinate extraction

The stage first extracts declared dependency coordinates from the build file without running the build tool:

| Build file | Strategy | Notes |
|---|---|---|
| `pom.xml` | `MavenXpp3Reader` | Skips `provided`, `system`, and unresolved property versions (`${...}`). Includes `test` scope (needed for test source type resolution). |
| `build.gradle.kts` / `build.gradle` — Gradle wrapper present | `gradle -q dependencies` task | Runs for root project and all subprojects found in `settings.gradle(.kts)`. Parses the tree output, resolves `1.0 -> 2.0` conflict notation to the final version. Returns `null` on non-zero exit or empty output. |
| `build.gradle.kts` / `build.gradle` — no wrapper or task failed | Static regex | Extracts quoted `"group:artifact:version"` strings and `implementation("g","a","v")` three-arg forms. Best-effort: version catalog refs and BOM-managed versions are missed. |

#### Artifact resolution: direct only, no POM traversal

All coordinate sources — including Maven POM parsing and static Gradle fallback — resolve artifacts *directly* using `ArtifactRequest` (equivalent to `resolveArtifacts`), not via `DependencyRequest` (which would traverse transitive POMs).

**Why skip POM traversal for project deps?**

- **Speed.** POM traversal requires one HTTP request per reachable node in the transitive graph. A project with 50 direct deps may have 300+ transitive nodes, each needing a POM download on a cold run. Direct resolution only fetches the JARs for the coordinates explicitly listed.
- **Sufficiency.** In practice, most type references in source code come from direct or first-level-indirect dependencies that are already declared in the build file. Missing transitives show up as `JavaType.Unknown`, which OpenRewrite tolerates — recipes still run and produce correct results for the types they can resolve.
- **Stage 3 as supplement.** `DirectParseStage` (Stage 3) scans `~/.m2/repository` and `~/.gradle/caches` for any JARs already present locally, including transitives previously downloaded by the project's own Gradle/Maven build. Together, Stages 2 and 3 cover the common case without any extra POM downloads.

When the Gradle task (`gradle dependencies`) is used, it already returns the **full resolved transitive graph** (Gradle has already done conflict resolution). Those coordinates are resolved directly with `ArtifactRequest` — no re-traversal needed. This is why both paths (Gradle task output and static/Maven parsing) use the same `resolveArtifactsDirectly` method.

#### No scope pruning in the session

The project context does **not** set `excludeScopesFromGraph`, so no `ScopeDependencySelector` is installed. `test`-scoped dependencies declared in the project's build file are included in the coordinate list and resolved normally.

### Local repository

`~/.m2/repository` (Maven default)

The project context uses the user's standard Maven local repository. This means any JARs the project has already downloaded through its own `mvn` or `gradle` build are immediately available without re-downloading.

---

## Shared Aether session settings (both contexts)

Both recipe and project `AetherContext` instances apply the following settings:

| Setting | Value | Reason |
|---|---|---|
| `OptionalDependencySelector` | always on | Skip optional transitive deps — they are not required by dependents |
| `ExclusionDependencySelector` | always on | Honour `<exclusions>` declared in POMs |
| `ClassicConflictResolver` (nearest-wins) | always on | Pick one version per artifact when the graph contains duplicates |
| `setIgnoreArtifactDescriptorRepositories` | `true` | Ignore `<repositories>` blocks in dependency POMs — prevents contacting arbitrary third-party repos |
| `aether.remoteRepositoryFilter.prefixes.resolvePrefixFiles` | `false` | Don't download large prefix-filter index files before resolving |
| `aether.updateCheckManager.sessionState` | `"bypass"` | Skip redundant update rechecks for artifacts already checked in this JVM session |
| Repository `checksumPolicy` | `CHECKSUM_POLICY_IGNORE` | Don't fail or retry when a repository omits `.sha1` / `.sha256` files (common on corporate proxies and private registries) |
| Repository update policy | `UPDATE_POLICY_DAILY` | Re-check remote metadata at most once per day; cached artifacts are reused within a day |
| Parallel download threads | configurable (`--download-threads`, default 5) | Tune for network bandwidth vs resource constraints |
| `CONNECT_TIMEOUT` | 30 s | Avoid hanging on slow connections |
| `REQUEST_TIMEOUT` | 60 s | Abort if a server accepts the connection but never responds |
