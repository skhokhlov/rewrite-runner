# ADR 0001: Build-Unit Classpath Resolution

## Status

Accepted

## Context

Stages 1 and 2 of the LST classpath pipeline historically invoked Maven or Gradle only from the
project root. That works for ordinary single-build roots and aggregator builds, but it is weak for
root-less monorepos where build descriptors live only in subdirectories. In those repositories the
root subprocess cannot see module-local `pom.xml` or `build.gradle(.kts)` files, so the pipeline
falls through to static parsing in Stage 3 and loses build-tool fidelity such as BOM-managed versions,
dependency management, resolved Gradle versions, and transitive scope decisions.

## Decision

Stages 1 and 2 resolve classpaths by iterating discovered build units.

A build unit is a directory where rewrite-runner invokes a build tool, paired with that tool:

- Maven root `pom.xml` exists: use the project root as the Maven unit.
- No root Maven descriptor: discover top-most subdirectory `pom.xml` files as Maven units.
- Gradle root `settings.gradle(.kts)` or `build.gradle(.kts)` exists: use the project root as the
  Gradle unit.
- No root Gradle descriptor: discover top-most subdirectory Gradle descriptors as Gradle units.

Discovery prunes below the first descriptor directory, skips the same default excluded directory names
as file collection, scans to a maximum subdirectory depth of 3, sorts candidates by root-relative path,
and caps processing at 25 units. When more than 25 units are discovered, rewrite-runner warns and does
not treat Stage 1 or Stage 2 as complete, so later fallback stages provide full-project coverage.

Stage 1 unions distinct JAR paths from unit classpath extractions only when every discovered unit
completes. Stage 2 accumulates coordinates from Maven and Gradle dependency subprocesses only when
every discovered unit completes, then resolves the distinct coordinate set. Partial per-unit coverage
falls through to the next stage instead of reporting a build-tool or dependency-resolution success for
the whole project. Gradle project data from subdirectory units is re-keyed to root-relative Gradle
paths such as `:services:api` so build-file markers can still be attached.

## Consequences

Root-less Maven and Gradle monorepos can now use high-fidelity build-tool classpaths in Stages 1 and
2 instead of falling directly to static parsing. The common root-descriptor case remains one
subprocess per tool, preserving the previous cost profile for ordinary projects.

Large root-less repositories may invoke more subprocesses than before. The 25-unit cap and Stage 3
fallback limit the worst case while still improving the most common root-less layouts.
