# ADR 0009: CLI Fat JAR Distributed via GitHub Releases, Not Maven Central

## Status

Accepted

## Context

rewrite-runner publishes two modules to Maven Central through the `publishing-convention`
plugin (`com.vanniktech.maven.publish`):

- **`core`** — the library: thin JAR + sources + javadoc, dependencies declared in the POM.
- **`cli`** — thin JAR + sources + javadoc, **and** historically the `-all` shadow fat JAR.

The fat JAR bundles all of `core`'s transitive tree (every `rewrite-*` module, Maven Resolver,
Jackson, Logback, picocli) into a single ~110 MB artifact. It was the recommended way to run
the CLI: the README told users to `curl` it straight from `repo1.maven.org`.

That artifact exceeds Maven Central's per-file upload limit, so it cannot continue to ship
there. Two in-place workarounds were rejected:

- **`shadow { minimize() }`** to shrink the JAR is unsafe. OpenRewrite discovers recipes and
  parsers reflectively via `ServiceLoader`, and minimization strips classes reached only by
  reflection — breaking recipe loading at runtime in ways unit builds do not catch.
- **Dropping the sources/javadoc JARs** to claw back size is not allowed: Sonatype requires a
  sources JAR and a javadoc JAR for every released artifact.

The fat JAR is also conceptually not a library dependency — nobody adds it to a `dependencies {}`
block — so Maven Central is the wrong host for it regardless of size.

## Decision

**The runnable `-all` fat JAR ships as a GitHub Release asset; Maven Central hosts only the
libraries (`core` and the `cli` thin JAR).**

- In `cli/build.gradle.kts`, the gradleup shadow plugin auto-registers `shadowRuntimeElements`
  on the `java` component (which vanniktech then publishes). We skip that variant from the
  published component (`withVariantsFromConfiguration(...) { skip() }`, deferred in
  `afterEvaluate` because shadow registers the variant in its own `afterEvaluate`). The
  `shadowJar` task stays intact so the asset can still be built.
- The `cli` thin JAR + sources + javadoc continue to publish to Central, preserving the
  coordinates and the Maven Central badge. The thin JAR is KB-scale and no threat to the limit.
- The tag-triggered `publish.yml` workflow builds the fat JAR after the Central publish and
  uploads it as `rewrite-runner-<version>-all.jar` to the GitHub Release for that tag
  (`softprops/action-gh-release`, `contents: write`). GitHub Releases caps individual assets at
  2 GiB and does not meter asset storage/bandwidth for public repos — ample headroom.
- Because the thin `cli` JAR + POM remain on Central, JBang can run the CLI directly
  (`jbang io.github.skhokhlov.rewriterunner:cli:<version>`) without any fat JAR — documented as
  a bonus path, not the primary one.

## Consequences

The Maven Central footprint drops by the entire fat JAR and stays under the upload limit, while
library consumers are unaffected. The cost is that the CLI download moves off the immutable,
mirror-backed Central CDN onto GitHub Releases, and the README `curl` URL changes shape
(`releases/download/v<version>/...`). Anyone who bookmarked the old `repo1.maven.org` URL must
update it — a one-time, hard-to-silently-reverse break, which is why this is recorded here.

A future contributor tempted to "just put the fat JAR back on Central" should treat the Central
upload limit (not preference) as the blocker, and `minimize()`'s incompatibility with
OpenRewrite's reflective recipe loading as the reason shrinking-in-place is not a shortcut.
