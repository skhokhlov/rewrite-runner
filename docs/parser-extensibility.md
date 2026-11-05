# LST parser extensibility

Status: implemented for library callers. The CLI and YAML configuration do not register providers.

Library users can supply their own OpenRewrite parsers, assign project files to them, and override
existing parser configuration. Built-in and user-provided parsers use the same registration
contract. Preserve current logic and functionality wherever possible; defer additional machinery
until a concrete use case requires it.

## Initial API

Expose a configuration callback on `RewriteRunner.Builder` that lets callers inspect and edit the
current parser registrations. Parser registrations are part of runner configuration; a separate
top-level parser configurator is unnecessary.

A registration identifies a parser provider, its file selection rules, and any explicit overrides.
A provider constructs the parser and owns any language-specific settings. Its reference and any
settings needed for construction must be reproducible inside the forked worker. Use a simple
provider reference plus optional settings data; a typed settings helper is not required.

Providers may supply default file assignments when explicitly registered by the application.
Those assignments become ordinary registrations that callers can inspect and change. Adding a
provider dependency alone does not activate it.

Use existing project context where needed. A provider can request the resolved JVM classpath and
shared JVM type cache, as the built-in JVM parsers already receive. Keep this focused on the
existing facilities; a general capability framework is outside the initial scope. Classpath-free
providers must not trigger unnecessary classpath-resolution subprocesses.

## Usage

Implement `io.github.skhokhlov.rewriterunner.lst.ParserProvider` as a public class with a public
no-argument constructor. `create(context, options)` returns your OpenRewrite `Parser`.
`defaultSelection()` can supply file assignments; `requiresClasspath()` defaults to `false`.
The parser must accept the assigned paths and produce source paths relative to `context.projectDir`,
just like the built-in parsers. The runner batches a registration's inputs into one parse call.

For example, this provider supports text migrations on a custom file type. Replace its parser
construction with your language's parser to produce your own semantic trees:

```kotlin
import io.github.skhokhlov.rewriterunner.lst.ParserContext
import io.github.skhokhlov.rewriterunner.lst.ParserFileSelection
import io.github.skhokhlov.rewriterunner.lst.ParserProvider
import org.openrewrite.Parser
import org.openrewrite.text.PlainTextParser

class AcmeParserProvider : ParserProvider {
    override fun defaultSelection() =
        ParserFileSelection(extensions = setOf(".acme"))

    override fun create(context: ParserContext, options: Map<String, String>): Parser =
        PlainTextParser.builder().build()
}
```

Register it explicitly through the builder. Repeated configuration callbacks see earlier changes;
the runner snapshots registrations so later edits to a callback's configuration cannot change a
built runner.

```kotlin
val runner = RewriteRunner.builder()
    .projectDir(projectDir)
    .activeRecipe(recipeName)
    .configure { config ->
        config.registerParser("acme", AcmeParserProvider::class.java)
    }
    .build()
```

An explicit selection replaces the provider defaults. Selectors are alternatives: matching an
extension, exact filename, filename prefix, or project-relative glob selects the file. Extensions
include the leading dot and ignore case; filenames are exact and prefixes ignore case. A glob
starting with a recursive directory prefix also matches files at the project root. Existing global
exclusions and excluded directories still win.

```kotlin
.configure { config ->
    config.registerParser(
        id = "schema",
        provider = AcmeParserProvider::class.java,
        selection = ParserFileSelection(pathGlobs = listOf("schemas/*.xml", "schemas/**/*.xml")),
        options = mapOf("dialect" to "customer-a"), // interpreted by your provider
        overrides = setOf("xml", "maven")
    )
}
```

The public `config.parsers` list also supports inspecting, replacing and removing registrations.
Removing a registration removes that runner parser; it does not disable the official plugin for
those paths. Assign the paths to a replacement provider to take ownership from Stage 0. For example,
replace a built-in provider while keeping its assignments and precedence:

```kotlin
.configure { config ->
    val index = config.parsers.indexOfFirst { it.id == "json" }
    config.parsers[index] = config.parsers[index].copy(
        providerClassName = CustomerJsonProvider::class.java.name,
        options = mapOf("dialect" to "customer-a")
    )
}
```

Built-in registration IDs are `java`, `kotlin`, `kotlin-script`, `gradle-kotlin`, `groovy`, `gradle`,
`yaml`, `json`, `maven`, `xml`, `properties`, `toml`, `hcl`, `protobuf`, `docker`, and `plain-text`.
The built-in providers retain their current construction settings; supply your own provider to
change language-specific parser settings. Options are a string map owned by the selected provider.
Plain-text masks remain the fallback assignment of the `plain-text` registration.

Java callers use the same API:

```java
builder.configure(config ->
    config.registerParser("acme", AcmeParserProvider.class));
```

## Dependencies and worker execution

For the initial version, provider code is supplied through ordinary application build dependencies
and made available on the existing worker runtime classpath. Gradle/Maven manifests or version
catalogs remain the source of dependency versions, so dependency-update tools can maintain them.
Do not require versioned artifact strings in application registration code.

Retain the existing worker launcher and its current packaging behavior. Custom classloaders and
nonstandard distributions are not automatically reconstructed from a provider object; code needed
by the provider must actually be available to the worker. Existing launcher customization remains
available for nonstandard packaging. Do not introduce a new build plugin, generated dependency
index, or custom provider-bundle format for this feature.

Support both default forked execution and explicit in-process execution. An already-created
parser instance alone does not meet the forked contract. Keep existing result shapes and resource
cleanup in each execution mode.

Recipe names and recipe artifacts can still be supplied dynamically per customer request. Keep
the application-managed parser baseline stable. Unknown recipe compatibility does not block a
run, and ordinary dependency-version differences do not establish incompatibility. Preserve
existing dependency resolution and error handling, with contextual provider diagnostics for loading and construction failures. No compatibility certification, approved-combinations list, or automatic parser
replacement is required.

## File ownership and existing behavior

Use explicit overrides to resolve deliberate overlaps. For example, the built-in Maven
registration selects files named `pom.xml` and takes precedence over ordinary XML parsing. A
user-provided parser can override selected files through the same mechanism.

Report an unresolved overlap before recipe execution with the file and competing registration
IDs. Registration order and inferred glob specificity do not break ties. If Maven and a custom
schema registration both override XML and both select `schemas/pom.xml`, they still need an
explicit winner between them.

Preserve current default file selection, exclusions, directory filtering, filename conventions,
and plain-text fallback. Preserve parser batching, shared JVM type information, source-set and
version markers, build-model markers, and common provenance enrichment.

Preserve Maven batch parsing, individual retries after URI failures, and its narrow recovery to
XML. Preserve Gradle DSL recovery to Kotlin/Groovy parsing. Parser recovery is separate from file
ownership; matching another parser does not automatically make it a recovery parser.

Preserve ordinary per-file and batch parse-failure diagnostics, `ParseError` trees, and reporting
of silently dropped inputs. Preserve fatal errors and existing language-specific terminal
failures. Loading or construction failures of a configured provider are reported through the
existing LST execution failure contract, rather than silently omitting its files: full-fallback
failures are terminal, while specialized-pass failures preserve a successful Stage 0 result with
a warning. Preserve dry-run, apply, write outcomes, and terminal failure/result behavior.

## Stage 0 and specialized ownership

Custom parsers follow the existing specialized route used by Docker/HCL/protobuf. Registering a
custom parser or overriding file assignments does not automatically disable Stage 0. Generalize
the current hardcoded specialized ownership set using the effective parser registrations, while
retaining its existing default assignments and execution order.

- Exclude custom-owned files from Stage 0 alongside the existing specialized exclusions. Derive
  Stage 0 exclusions and specialized-pass file selection from the same ownership decisions, so
  an override does not result in both paths processing the same file.
- On Stage 0 success or no changes, run the existing owned-only specialized pass over built-in
  specialized files and custom-owned files. Do not run the full fallback over the project.
- If no owned files are in scope, preserve the existing plugin-only result behavior and avoid
  unnecessary worker execution. The candidate check must account for custom registrations.
- If Stage 0 fails, is unavailable, or is explicitly skipped, run the existing full LST path with
  both built-in and user-provided parsers. Preserve existing classpath-resolution fall-through.
- For partial Stage 0 results, preserve the existing hybrid full-fallback behavior and collision
  protection for files already changed by Stage 0.
- Preserve result aggregation, apply/dry-run behavior, execution diagnostics, and the existing
  specialized-pass failure policy. Resolve requested project context only when the selected
  parsers need it; classpath-free custom parsers do not trigger JVM classpath resolution.

For customized configurations, Stage 0 exclusions are derived from all specialized-owned files,
with literal paths escaped as globs in addition to the existing exclusions. As with other runner inputs, callers must not
modify the project concurrently with a run. Files generated during Stage 0 are discovered again
by the specialized pass. Paths containing commas cannot be safely encoded in the existing Maven
exclusion property; custom-owned paths containing commas use full LST fallback with a diagnostic warning. This avoids reporting a successful run that processed a file twice.

Stage 0 and the specialized pass execute recipes against separate source sets, as they do today.
The initial feature does not promise one combined LST across those processes. A caller needing
one source set can use the existing `skipPluginRun(true)` option; the full LST path then includes
all eligible files handled by built-in and custom parsers. A combined source set does not itself
add language-specific type attribution.

## Deferred work

Defer typed configuration builders, automatic provider activation, custom artifact provisioning,
provider bundles and build plugins, compatibility frameworks, and unrelated configuration or
CLI redesigns. The first release should expose the existing parsing machinery through a small
extension contract and verify the new custom-language and override paths alongside the existing
behavioral checks.
