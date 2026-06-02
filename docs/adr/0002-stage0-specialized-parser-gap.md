# ADR 0002: Stage 0 Specialized Parser Gap

## Status

Accepted

## Context

`RewriteRunner.run()` tries Stage 0 first: the official OpenRewrite Gradle or Maven plugin. When
that plugin path succeeds, the runner returns immediately with `RunResult.rawDiffs`; it does not build
an in-process LST.

The plugin path uses the upstream plugin parser set and plain-text mask handling. The classpath-free
specialized parsers wired into rewrite-runner's LST fallback, such as `DockerParser`, `HclParser`, and
`ProtoParser`, only run when Stage 0 is skipped or fails. That means Docker, HCL, and protobuf recipes
can silently no-op on projects where Stage 0 succeeds because those files are not owned by the
specialized fallback parsers in that path.

Plain-text masks do not solve this. Forwarding masks keeps Stage 0 and the LST fallback aligned for
plain-text file selection, but it does not make Stage 0 run rewrite-runner's specialized parsers.

## Decision

Keep the plain-text mask work scoped to selection parity:

- Resolve one `plainTextMasks` list using CLI/builder over YAML over upstream defaults.
- Forward that list to Stage 0 and to the LST fallback.
- Keep specialized parser precedence on the LST path, so files like `Dockerfile*` are still parsed by
  `DockerParser` when the fallback runs.

Do not change Stage 0 short-circuit behavior in this work.

Follow-up issue: [#202](https://github.com/skhokhlov/rewrite-runner/issues/202).

## Consequences

Stage 0 remains fastest and closest to the official OpenRewrite plugin behavior, but it still does not
exercise rewrite-runner's classpath-free specialized parsers. Users who need those parsers can use
`--skip-plugin-run` as a workaround.

Future work should split file-type ownership: exclude specialized non-JVM formats from Stage 0 using
the existing exclusion forwarding, always run a lightweight classpath-free LST pass over just those
files, and merge the plugin raw diffs with fallback `Result` objects. `RunResult` already carries both
`rawDiffs` and `results`, so the public result shape can represent that merged execution model.
