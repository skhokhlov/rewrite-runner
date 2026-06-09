# ADR 0007: Exclusion-Only Path Filtering

## Status

Accepted

## Context

rewrite-runner originally filtered which files a run touched with two extension-based flags:
`--include-extensions` and `--exclude-extensions` (mirrored on the Builder and in YAML). They
had a silent correctness bug: they were **only** applied in the LST fallback and were never
forwarded to Stage 0. A user who ran `--exclude-extensions .md` on a buildable project saw the
official plugin parse every `.md` anyway. That is worse than a missing feature — it is a filter
that appears to work and quietly does not.

Extensions were also our own invention. The upstream OpenRewrite Gradle and Maven plugins
expose exactly one native filtering primitive: glob `PathMatcher` **exclusions** (Maven
`rewrite.exclusions`, Gradle `exclusion("…")`). They have no include/allowlist concept at all.
With two execution paths that must stay observably consistent (see
[ADR 0006](0006-plugin-first-execution.md)), any filter we offer has to be expressible on both —
and the LST fallback can express anything, but Stage 0 can only express what its plugin accepts.

## Decision

Filtering is **exclusion-only, by glob, with no allowlist.**

- Remove the extension flags, Builder methods, and YAML keys outright — one breaking cut, no
  deprecation cycle.
- Introduce `--exclude-paths` (comma-separated globs) / `Builder.excludePaths(...)` /
  `parse.excludePaths`, resolved once into a single `effectiveExcludePaths` (CLI over YAML) and
  forwarded to **both** Stage 0 (Maven `-Drewrite.exclusions=…`; Gradle `exclusion(...)` lines in
  the init script) and the LST fallback. One value, both paths, identical filtering.
- Deliberately provide **no** `--include-paths` and no allowlist API. Upstream has no include
  primitive; adding one would mean a filter that Stage 0 cannot honor, reintroducing exactly the
  divergence that killed the old flags. Users scope tightly by composing exclusion globs.

A related optimization rides along: when the resolved exclusions eliminate every JVM source file,
classpath-resolution stages 1–4 are skipped entirely.

## Consequences

The two execution paths now filter identically, and the surface matches upstream OpenRewrite, so
there is one fewer rewrite-runner-specific concept to explain. The cost is a hard breaking change
for anyone who used the extension flags, and the loss of an include/allowlist convenience.

The most important thing this ADR preserves is the **"why not include-paths"**: it is not an
oversight or a backlog item. Re-adding an allowlist would recreate the Stage-0-vs-LST divergence
the whole change was meant to remove. A future contributor proposing `--include-paths` should
treat that as the bar to clear, not a quick win.
