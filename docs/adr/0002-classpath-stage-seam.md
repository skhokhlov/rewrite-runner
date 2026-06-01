# ADR 0002: Unified Classpath Stage Seam

## Status

Accepted

## Context

The LST fallback classpath pipeline has four stages: build-tool extraction, dependency subprocess
resolution, static build-file parsing, and local cache scanning. Before this ADR, `LstBuilder`
stitched those stages together with custom code for each return shape:

- Stage 1 returned a nullable `List<Path>`.
- Stage 2 returned a `ClasspathResolutionResult`.
- Stage 3 returned a `List<Path>`.
- Stage 4 was invoked directly through `LocalRepositoryStage.findAvailableJars`.

That made fall-through behavior hard to change safely. The orchestrator also appended project class
directories in multiple branches and carried Gradle project data from a Stage 2 fall-through into
Stage 3 or Stage 4 wins.

## Decision

Introduce `ClasspathStage`:

```kotlin
interface ClasspathStage {
    fun resolve(
        projectDir: Path,
        parseFailures: MutableList<ParseFailure>,
    ): ClasspathResolutionResult?
}
```

Returning `null` means "fall through"; returning a `ClasspathResolutionResult` wins and terminates
resolution.

`ProjectBuildStage`, `DependencyResolutionStage`, and `BuildFileParseStage` implement the interface.
Stage 4 keeps `LocalRepositoryStage` as the cache-scanning primitive and is wrapped by a small
adapter inside `LstBuilder`. `LstBuilder` runs the ordered list and appends project class directories
once to the winning result.

Stage 1 owns its compile-on-demand side effect. If Stage 1 extracts a classpath and no project class
dirs exist yet, it invokes `tryCompile`; the single append in `LstBuilder` then observes any compiled
outputs.

Gradle project data is owned by the winning stage. Stage 1 gathers it through Stage 2's
`collectGradleProjectData` helper. Stage 2 includes it only when Stage 2 resolves a non-empty
classpath and wins. If Stage 2 gathers metadata but resolves no JARs, it returns `null`; a later
Stage 3 or Stage 4 win does not inherit that metadata.

## Consequences

Adding, removing, reordering, or testing classpath stages is now a list-level change instead of a
bespoke `LstBuilder` branch.

Malformed-coordinate parse failures are passed as an ordinary parameter to Stages 2 and 3, removing
the previous mutable side-channel on the stage instances.

Project class dirs are appended consistently, including when all stages fall through and only class
dirs are available.

GradleProject markers are no longer attached on the Stage-2-empty -> Stage-3/4-win path. That loses
partial marker coverage for projects where the Gradle dependency task could produce metadata but no
resolvable classpath, but it prevents a losing stage from leaking data into a different winning
result and keeps the stage contract explicit.
