# ADR 0010: Fork post-plugin LST execution into a worker JVM

## Status

Accepted and active.

## Context

OpenRewrite's LST graph can be much larger than a useful coordinator live set. The official
Gradle/Maven plugins also run in separate build-tool JVMs. Keeping fallback LST work in the
coordinator makes container memory policy ambiguous and forces library hosts to absorb LST memory.

## Decision

`RewriteRunner` is a coordinator by default. It attempts the official plugin first, waits for that
process tree to finish, then starts exactly one short-lived LST worker when post-plugin work is
needed:

- `FULL_FALLBACK` after a plugin failure, skip, or partial result;
- `SPECIALIZED_ONLY` after a successful plugin result, for Docker/HCL/protobuf ownership; and
- `IN_PROCESS` only when selected explicitly by a library caller needing rich `Result` objects or a
  custom `ChangeWriter`.

The coordinator and worker use a private owner-only request directory, a versioned JSON request and
response, and framed stdout events. The worker applies LST-generated edits itself and returns raw
diffs, changed paths, write outcomes, parse diagnostics, and its observed maximum heap. It never
re-enters `RewriteRunner.run()`.

Runner JVM policy uses shared executor arguments plus stage-specific arguments. With no explicit
runner, environment, or target-build policy, the coordinator adds a conservative container-aware
`-Xmx`; it never tries to resize the already-running coordinator or impose an RSS limit.

## Consequences

- Forked runs return `results = emptyList()` and transportable `rawDiffs`; in-process runs preserve
  rich OpenRewrite results.
- Worker start, protocol, timeout, OOM, and full-fallback failures are terminal. A specialized
  worker failure preserves an already successful plugin result with a warning.
- Worker and plugin execution are globally serialized within a coordinator JVM. Project-owned
  classpath subprocesses keep their existing build-tool JVM configuration.
- The public replacement for the old flat plugin-argument surface is `execution.*` YAML,
  `--executor-jvm-arg` / `--plugin-jvm-arg` / `--lst-worker-jvm-arg`, and corresponding builder
  methods. Old `pluginJvmArgs` configuration fails with a migration message.

## Alternatives considered

- Keep the LST pipeline in the coordinator: rejected because it couples host memory to recipe size.
- Serialize `Result`/LST graphs across the boundary: rejected because it reintroduces the memory
  pressure the worker isolates.
- Run a persistent worker daemon: rejected for now; a fork-per-run worker has simpler lifecycle,
  cleanup, and configuration semantics.
