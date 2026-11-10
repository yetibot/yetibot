# Kroki diagrams and native agent tools

Kroki renders supplied diagram source; it does not call a text or image model.
The default renderer is public `https://kroki.io`. The operator can configure
`:kroki :url` (or `YETIBOT_KROKI_URL`), but a model cannot select an endpoint.
Source goes to that service. Artifacts are public temporary bearer URLs in the
existing bounded media store, and expire on eviction/restart.

## Commands

```text
!kroki
!kroki types
!kroki info goat
!kroki render d2 --option layout=tala -- x -> y
!kroki render graphviz --format pdf -- digraph { A -> B }
```

The shorthand `kroki TYPE SOURCE` remains. Pipe literal text into
`kroki render TYPE`, or use a complete multiline Markdown fence around source
containing pipes, backticks, quotes or `$(...)`. Fenced contents stay literal in
the updated core parser. Leading flags precede source; `--` ends flag parsing.
Repeat `--option KEY=VALUE` for engine options. Preserve the diagram source,
not a prose description, when asking an agent to render a specific definition.

Types/aliases/versions come from `/health`, with a five-minute cache and explicit
stale labels after discovery failure. No static type allowlist blocks new
engines. Discovery is metadata, not a format/options schema or render guarantee.
Rendering does not depend on discovery succeeding. Auto requests PNG then SVG;
explicit `png/svg/jpeg/pdf/txt/utxt/base64` support is engine-specific.

## Native tools

When the upgraded plugin is installed, ordinary authorized native discovery
includes `yetibot/kroki_types` and `yetibot/kroki_render`. Explicit native
allowlists must include the desired names; `:native-tools []`, GitHub toolset
isolation, caller/adapter/provider/model policy and tool-call budgets still apply.
No shell, command interpreter, arbitrary URL or cross-channel argument is exposed.

`kroki_types` accepts optional `type`, `after`, `limit` (1–50, default 20).
It returns engine/version rows, server version, successful-check time, stale
state, `has_more` and `next_after`. Catalog discovery performs no rendering.

`kroki_render` requires literal `type` and `source`; `format` and scalar `options`
are optional. Example function arguments:

```json
{"type":"d2","source":"agent -> diagram","options":{"layout":"tala"}}
```

The shared client limits source to 256 KiB UTF-8 and output to 8 MiB. Larger
JSON-escaped source is admitted only for this native tool, without changing
other tools' argument limits. Configured credentials in source/options are
rejected, not silently changed. This is not a general secret detector.

The model receives compact structured metadata, never artifact bytes. The host
retains actual generated artifacts independently of model prose, appends missing
artifact URLs to terminal answers, and preserves successful diagrams if a later
model/tool step fails. It never rerenders to recover a lost summary. Multiple
diagrams each receive a Discord upload; duplicate URLs do not duplicate uploads.
Raster previews remain inline. SVG/PDF/text are downloadable file components,
not unsupported image galleries. Existing ownership, no-ping and frozen
inspection controls remain in force.

## Data and errors

Command success carries `:result/value`, artifact `:result/data` and reviewed
`:result/public-data`. Artifact fields are type, actual format/MIME, byte count,
ID, URL and `ephemeral=true`. `data show`, pipelines and Explore data receive
metadata, not source or binary. Catalog collection operations preserve the
corresponding reviewed row and discovery freshness.

Failure carries `:result/error` and reviewed `:result/public-error`. Native
responses use `isError` and structured diagnostics. Root Error details retains
safe Kroki kind/type, HTTP status and numeric source line/column when available,
in addition to the ordinary host execution trace. Arbitrary error bodies,
options, headers, exceptions and source are not exported to inspection. No
redirect, automatic HTTP retry, model retry or fallback service is introduced.

## Release ordering

These are separately published repositories. Adopt core's MIME-safe generated
artifact routes/Discord downloads and literal fences first, publish the updated
Kroki plugin second, then update the app to their actual published versions.
The app adapter detects the shared plugin module before advertising tools;
old plugin artifacts do not expose nonfunctional capabilities. That readiness
check does not replace adopting the required core release.

The development tests use source-composed changed repositories and offline
transports in addition to genuine pinned dependencies. This does not prove
unpublished artifacts are present in a deployed bot. No production configuration
or provider defaults need changing to adopt the feature.
