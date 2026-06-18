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

## Multi-Module Path Relativity

Issue #182 questioned whether Gradle exclusions declared only in the generated init script's
`rootProject { rewrite { ... } }` block reach subproject files. They do. In
`rewrite-gradle-plugin` 7.32.1, the root project parser is the parser that walks subprojects, and
it reads exclusions from the root project's single `RewriteExtension`. Keeping the exclusions in
the root `rewrite { }` block is therefore the correct declaration site; moving them to
`allprojects {}` would configure subproject extensions that the root parser does not use for this
walk. The `allprojects { repositories { ... } }` block in the init script has a different purpose:
each project still needs repositories to resolve its own dependencies.

The remaining subtlety is the base path used by the upstream plugins. The real-plugin regression
tests pin the behavior observed through rewrite-runner's default invocation shape:

- Gradle 7.32.1 matches Java source exclusion globs against paths relative to the repository root.
  A `**/Skip.java` pattern reaches `lib/src/main/java/.../Skip.java`; a module-relative
  `src/main/java/.../Skip.java` pattern only matches a root-project file with that path.
- Maven 6.41.0, invoked by rewrite-runner with `rewrite.runPerSubmodule=false`, shows the same
  behavior for this multi-module Java-source case. The module-relative
  `src/main/java/.../Skip.java` pattern does not exclude `lib/src/main/java/.../Skip.java`.

Use `**/`-anchored globs for exclusions that need to reach subprojects in both Gradle and Maven
multi-module builds. The real-plugin tests encode this observed behavior; broader cleanup or
encoding consolidation remains #192's responsibility.

## Consequences

The two execution paths now filter identically, and the surface matches upstream OpenRewrite, so
there is one fewer rewrite-runner-specific concept to explain. The cost is a hard breaking change
for anyone who used the extension flags, and the loss of an include/allowlist convenience.

The most important thing this ADR preserves is the **"why not include-paths"**: it is not an
oversight or a backlog item. Re-adding an allowlist would recreate the Stage-0-vs-LST divergence
the whole change was meant to remove. A future contributor proposing `--include-paths` should
treat that as the bar to clear, not a quick win.
