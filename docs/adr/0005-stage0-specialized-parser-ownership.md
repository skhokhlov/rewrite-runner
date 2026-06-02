# ADR 0005: Stage 0 Specialized Parser Ownership

## Status

Accepted

## Context

Stage 0 runs the official OpenRewrite Gradle or Maven plugin before rewrite-runner builds its own
in-process LST. That plugin path is preferred for buildable JVM projects, but it cannot run
rewrite-runner's classpath-free specialized parsers.

The affected parser set is small: Dockerfile/Containerfile, HCL/Terraform, and protobuf. These
formats are both unsupported by Stage 0's practical parser surface in rewrite-runner and safe to
parse in isolation because they do not need the project classpath or build model. Other non-JVM
formats stay with Stage 0: YAML, JSON, XML, TOML, properties, and plain text either have upstream
plugin support or benefit from project-model context.

## Decision

Define a single hardcoded specialized ownership set:

- Extensions: `.hcl`, `.tf`, `.tfvars`, `.proto`, `.dockerfile`, `.containerfile`.
- Stage 0 exclusion globs: `**/*.hcl`, `**/*.tf`, `**/*.tfvars`, `**/*.proto`,
  `**/*.dockerfile`, `**/*.containerfile`, `**/Dockerfile*`, `**/Containerfile*`.

`RewriteRunner` always adds those globs to the Stage 0 exclusion list. When Stage 0 succeeds, it then
builds an owned-only LST with `LstBuilder` restricted to the ownership extensions and with plain-text
masks disabled for that pass. If no owned files are found, the runner returns the plugin-only result
shape unchanged. If owned files exist, the runner loads the same recipe, runs it on the owned source
files, applies those `Result` objects when not in dry-run mode, and returns a merged `RunResult`.

`RunResult.rawDiffs` carries Stage 0 patch diffs and `RunResult.results` carries specialized-pass
OpenRewrite results. `ResultFormatter` emits both when both are present. Diagnostics keep
`stageUsed = PLUGIN`; `parsedFileCount` and `parseFailures` describe the specialized pass when one
actually parsed owned files.

## Consequences

The behavior is default-on and needs no new CLI flag. Stage 0 remains responsible for files the
official plugin can handle, while Docker/HCL/protobuf are partitioned away by construction, avoiding
double-processing even if upstream plugin parser support changes.

The ownership set is intentionally not configurable. A future upstream shift can change the
hardcoded set in one place, but adding user configuration now would make the execution model harder
to explain before there is a real need.

The Stage 0 command text now always includes the specialized exclusions. Projects with no owned files
still keep the old result shape after the owned-only pass finds nothing, but wrapper/debug output can
show the additional exclusion arguments.
