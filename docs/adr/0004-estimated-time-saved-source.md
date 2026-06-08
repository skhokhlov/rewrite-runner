# ADR 0004: Source of the Estimated Time Saved Metric

## Status

Accepted

## Context

"Estimated time saved" is OpenRewrite's heuristic for the manual developer effort a recipe run
avoids: per changed file, the total `getEstimatedEffortPerOccurrence()` (default 5 minutes) of the
recipes that made the change. OpenRewrite already computes this and exposes it as
`org.openrewrite.Result.getTimeSavings()` (a `java.time.Duration`).

rewrite-runner has two execution paths, and they expose very different material:

- The **LST path** runs the recipe in-process and holds the `Result` objects. `getTimeSavings()` is
  available directly.
- The **Stage 0 plugin path** — the default — shells out to the official Maven/Gradle OpenRewrite
  plugin. Before this decision, rewrite-runner only read the emitted `rewrite.patch`. The patch is a
  plain git diff: its marker printer emits only `SearchResult`/`Markup` markers, **not** recipe
  attribution. So from the patch alone we know the changed file paths and nothing else — not which
  recipes changed each file, not occurrence counts, not per-recipe effort.

Because Stage 0 is the default path and carries no per-`Result` saving to sum, any metric derived
naively from in-process objects reads `0`/`null` on essentially every real run. That is the bug this
decision addresses.

We considered computing the number ourselves to avoid depending on OpenRewrite's reporting. That is
not feasible accurately: reproducing `getTimeSavings()` needs the per-file recipe set, occurrence
counts, and each recipe's effort estimate — none of which survive into the patch. The best a
self-estimate could do on Stage 0 is `changedFileCount × 5 min`, which is wrong whenever a file is
touched by multiple recipes, a recipe overrides the default effort, or the active recipe is
composite — and it would disagree with the LST path for the identical change set. The effort numbers
are OpenRewrite recipe metadata; "avoiding OpenRewrite" is illusory, since even a recipe-aware
reimplementation ends up calling `getEstimatedEffortPerOccurrence()`.

## Decision

Surface the metric as `ExecutionDiagnostics.estimatedTimeSaved: Duration?`, sourced from OpenRewrite
on both paths and **never recomputed** by rewrite-runner:

- **LST path** — sum `Result.getTimeSavings()` over the run's results.
- **Stage 0 path** — enable the plugin's data table export (Maven `-Drewrite.exportDatatables=true`;
  Gradle init script `rewrite { exportDatatables = true }`). Prefer the latest exported
  `datatables/<timestamp>/org.openrewrite.table.SourcesFileResults.csv`, deduplicate rows by
  `sourcePath`, and sum one `estimatedTimeSaving` value (seconds) per changed file. That column is
  populated by `Result.getTimeSavings().getSeconds()` and may be repeated across parent/child recipe
  rows for the same source file, so deduplication preserves parity with the LST path's one `Result`
  per changed file. Current Maven/Gradle plugin versions do not always emit that table, so Stage 0
  falls back to the official plugin output line `Estimate time saved: ...`, which is emitted from the
  same OpenRewrite-computed result duration. rewrite-runner never derives a number from patch shape
  or changed-file count.

Null-versus-zero is meaningful: `null` means "could not be determined" (Stage 0 with export missing
or the CSV absent/unparseable, or empty diagnostics); `Duration.ZERO` means "ran, genuinely saved
nothing." This mirrors the existing `parsedFileCount` discipline.

Scope is total-only: no per-recipe/per-file breakdown, and no report-JSON change.

## Considered Options

- **Parse OpenRewrite plugin-reported output (chosen).** Prefer the exported data table CSV when
  available, because it is structured and identical to the LST-path number. Fall back to the plugin's
  own `Estimate time saved: ...` log line for current plugin versions that compute the estimate but do
  not export `SourcesFileResults`. Cost: a new plugin flag, a CSV reader, and a small duration parser,
  plus a dependency on OpenRewrite's data-table file naming conventions (`datatables/` dir, FQN-based
  filename) and log wording, which are conventions rather than a published contract.
- **Self-estimate from the patch (rejected).** No new plugin machinery, but only a crude
  `files × default` is possible from Stage 0 output, which is inaccurate and inconsistent with the
  LST path.

## Consequences

The two paths report the same metric, so a project's number does not change depending on which path
won.

We take on a dependency on the plugin's data-table export layout and estimate-output wording. If
OpenRewrite renames the `datatables/` directory, the CSV, or the `Estimate time saved:` line, our
parse silently finds nothing and falls back to `null` — the same failure mode as the original bug.
This is mitigated by a real-plugin integration test (`testRealPlugin` lane) that asserts a non-zero
`estimatedTimeSaved` on a real run, so format drift fails CI loudly instead of regressing silently.

The Stage 0 path now requests data-table export on every plugin invocation. Gradle writes under
`build/reports/rewrite/datatables`; current Maven plugins write under `target/rewrite/datatables`.
These are build-output locations, but they may be in the target project tree.

`ExecutionDiagnostics.PLUGIN` can no longer be a fixed singleton, since the plugin path now carries a
per-run `estimatedTimeSaved`.
