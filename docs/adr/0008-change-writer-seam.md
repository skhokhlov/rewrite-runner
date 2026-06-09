# ADR 0008: Change Writer Seam

## Status

Accepted

## Context

The LST path used to apply OpenRewrite results inside `RewriteRunner`. Deletes were caught and
logged, then the run still looked successful. Writes were not caught, so one failed write aborted the
whole run. Downstream callers and the CLI could not distinguish a fully applied run from a partial
disk failure, and tests could only exercise application behavior against a real directory.

## Decision

Move application into a `ChangeWriter` seam. Production uses `DiskChangeWriter`; same-module tests
can inject an in-memory writer through an internal builder hook.

The writer attempts every result and records a `WriteOutcome` containing successful changes and
failures. Each entry carries `ChangeKind` (`CREATED`, `MODIFIED`, or `DELETED`) plus the
project-relative path; failures also carry a cause. `ExecutionDiagnostics.writeOutcome` is the home
for this signal. Dry-run, plugin-only, and no-change paths use `WriteOutcome.EMPTY`.

The CLI treats a failed write outcome as exit code `1` and prints a short stderr summary. The broader
exit-code scheme remains out of scope for this decision.

## Consequences

Failed deletes can no longer be reported as successful runs. Failed writes no longer prevent later
results from being attempted, so one bad target can produce a partial outcome with all other changes
still applied.

`RunResult.changedFiles` remains the list of successfully applied non-delete paths for compatibility.
Callers that need the full apply story should read `RunResult.executionDiagnostics.writeOutcome`.
