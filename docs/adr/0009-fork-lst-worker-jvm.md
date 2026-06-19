# ADR 0009: Fork the LST stage into a worker JVM

## Status

Accepted (deferred). The incremental `pluginJvmArgs` surface (Stage 0 subprocess heap) and the
docs-only memory model ship now; the worker JVM described here is the planned future direction and is
not yet implemented.

## Context

OpenRewrite can need a large heap. Today there are two heap consumers (see
[architecture.md](../architecture.md#stage-0-jvm-memory)): the **runner JVM** (in-process LST
fallback) and the **Stage 0 plugin subprocess**. `pluginJvmArgs` now lets operators size the Stage 0
subprocess, and the combined-budget model is documented — but two gaps remain:

1. The runner's big LST heap is set by the operator wrapping the launch (`java -Xmx… -jar`), which is
   awkward in many launchers, and rewrite-runner cannot auto-size it from the cgroup limit.
2. A large runner `-Xms` (or `AlwaysPreTouch`) makes the runner heap coexist with the Stage 0
   subprocess inside one container cgroup, risking an OOM-kill that no documentation can prevent.

The combined-memory tension exists only because the heavy LST work runs *in the same JVM* that also
orchestrates Stage 0.

## Decision

Run the LST fallback (and the specialized pass) in a **fork-per-run worker JVM**, leaving a thin
orchestrator:

- The orchestrator process stays small (e.g. `-Xmx128m`) and runs Stage 0 — which only spawns
  `gradlew`/`mvnw` and blocks. After Stage 0 completes and its subprocess exits, the orchestrator
  forks a separate worker JVM (`java -Xmx<worker> -cp <self-jar> <WorkerMain>`, JVM from
  `java.home`, jar from `ProtectionDomain.getCodeSource()`; reuse `ProcessRunner`) for the LST work.
  The big LST heap therefore **never coexists** with the Stage 0 subprocess. Peak ≈
  `max(stage0_subprocess, lst_worker)` + a negligible orchestrator.
- The worker's `-Xmx` sits on rewrite-runner's *own* `java` command, so it is fully controlled (no
  Gradle/Maven precedence games) and a new `--lst-max-heap` can **auto-size it from the cgroup
  limit** (`OperatingSystemMXBean.getTotalMemorySize()`, cgroup-aware on modern JDKs).
- **Result fidelity:** `org.openrewrite.Result` holds full `SourceFile` LSTs that cannot cross a
  process boundary cheaply — and serializing them back would re-import the big trees into the
  small-heap orchestrator, defeating the purpose. So the worker **applies changes to disk itself and
  returns `rawDiffs`** (empty `results`), exactly like the existing Stage 0 plugin path. Worker mode
  is therefore **CLI-only / opt-in**; **in-process stays the library default** for consumers that
  need `RunResult.results`. Consumers needing programmatic LST access should write a recipe (which
  runs inside the worker where the trees live) rather than post-processing `Result`s.
- **IPC** reuses what exists: serialize the effective config to a temp file the worker reads; the
  worker writes the existing report/diff output, which the orchestrator reads back. Outputs-to-disk,
  no new wire protocol.
- **Crash isolation** becomes a feature: if the worker OOM-kills, the orchestrator survives and
  reports an actionable "raise `--lst-max-heap`" message with a clean exit code.

## Alternatives considered

- **Status quo + docs only** (what ships now): no result-fidelity change, but the combined-`-Xms`
  container case and runner-heap ergonomics/auto-sizing remain unsolved.
- **Serialize `List<Result>` across the boundary** (Kryo/Java): rejected — intractable for
  type-attributed LSTs and self-defeating (re-imports the heap we offloaded).
- **Reusable long-lived worker daemon** (Gradle/Bazel daemon style): more complexity
  (lifecycle/staleness/cleanup) for little gain given rewrite-runner is typically one run per
  project. A possible later step on top of the fork-per-run worker.

Industry precedent for "serializable summaries + filesystem side effects, rich graphs never cross":
Gradle Worker API / compiler daemon (`forkOptions.memoryMaximumSize`), Bazel persistent workers,
forked test executors.

## Consequences

- Memory becomes cleanly bounded and self-sizable even with large runner `-Xms` and inside
  containers; the combined-budget detection/clamp lives naturally in the worker launch.
- A second execution mode exists (in-process vs worker). To avoid pipeline drift, the worker should
  re-enter the *same* `RewriteRunner.run()` in the child JVM and differ only in result serialization.
- Worker mode cannot return `RunResult.results`; library embedders needing them must use in-process
  mode. GraalVM native-image lacks a bundled `java`, so worker mode requires a JVM launcher.
- Cross-process integration tests are needed (the fake-wrapper harness fits).
