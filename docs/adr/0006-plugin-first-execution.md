# ADR 0006: Plugin-First (Stage 0) Execution

## Status

Accepted

## Context

rewrite-runner contains a full in-process recipe engine: a four-stage LST classpath
pipeline (`LstBuilder` + the `ClasspathStage` chain) that resolves a classpath, parses every
source file into an OpenRewrite LST, runs the recipe, and emits results. That engine exists
because not every target project is buildable in our environment, and we want recipes to run
even when `mvn`/`gradle` cannot.

But for the common case — a project whose own Gradle or Maven build already works — the
official OpenRewrite plugins (`org.openrewrite:plugin` for Gradle, `rewrite-maven-plugin` for
Maven) can apply the same recipe directly, using the project's own build system for
high-fidelity classpath resolution. Reproducing that fidelity in-process is exactly what the
four LST stages struggle with (BOM-managed versions, `dependencyManagement`, resolved
transitive scopes, Gradle version catalogs). Running the official plugin sidesteps all of it
and stays closest to upstream OpenRewrite behavior.

## Decision

`RewriteRunner.run()` tries the official plugin first, as "Stage 0", before building any
in-process LST. The in-process four-stage pipeline becomes the **fallback**, not the primary
path.

- Stage 0 is default-on and gated by a single opt-out: `--skip-plugin-run` /
  `Builder.skipPluginRun(true)`.
- `PluginRecipeRunner` dispatches to a `PluginBuildStrategy` per build tool. When both root
  descriptors are present it is **try-with-fallback, Gradle then Maven** — if the Gradle
  plugin invocation fails, it tries the Maven plugin before giving up. (This is deliberately
  *not* routed through the exclusive Gradle-first marker verdict of [ADR 0001](0001-build-unit-classpath-resolution.md);
  forcing it through that verdict would drop the Maven fallback.)
- Plugin outcomes are a sealed result: `Success` (short-circuit, return the plugin's patch
  diffs), `NoChanges` (short-circuit, empty result), `Failed`, or `Skipped` (no build tool).
  `Failed` and `Skipped` **fall through silently** to the LST pipeline — a plugin that cannot
  resolve, times out, or finds no build tool must never abort the run.
- Diagnostics record `stageUsed = UsedExecutionStage.PLUGIN` so a consumer can tell the
  plugin path produced the run (see [ADR 0004](0004-estimated-time-saved-source.md) and the
  `ExecutionDiagnostics` work).

The plugin path returns a git-format patch rather than in-process `Result` objects, which is
why `RunResult` carries both `rawDiffs` (Stage 0 patches) and `results` (LST/specialized-pass
results), and why metrics like estimated-time-saved had to be sourced differently per path.

## Consequences

Stage 0 is the fastest and most faithful path for buildable JVM projects, and it is what most
real runs use. The substantial LST engine still earns its keep: it is the fallback for
non-buildable projects, offline scenarios, and `--skip-plugin-run` debugging.

The cost is that rewrite-runner now has **two execution paths that must stay observably
consistent** — every cross-cutting feature (path exclusions, plain-text masks, estimated time
saved, specialized Docker/HCL/protobuf parsing) has to be implemented twice or forwarded to
both paths, and several later ADRs (0002, 0004, 0005) exist precisely to reconcile a Stage 0
gap against LST-path behavior. The silent fall-through also means a misconfigured plugin run
can be masked by a successful LST fallback; this is mitigated by the real-plugin test lane,
which asserts `stageUsed == PLUGIN` so an accidental fallback fails CI.
