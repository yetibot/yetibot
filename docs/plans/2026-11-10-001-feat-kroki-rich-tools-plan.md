---
title: Discovery-first Kroki rendering and native agent tools
type: feat
date: 2026-11-10
---

# Discovery-first Kroki rendering and native agent tools

## Goal Capsule

Replace unchecked Kroki links with verified rendered artifacts shared by chat,
pipelines and typed native tools. Discover engines from the configured server
instead of a type allowlist. Preserve `kroki TYPE SOURCE` and `graphviz-url`.
The operator approved the recommendations in this session and authorized
implementation and PR publication, not merge or production configuration edits.

## Product Contract

- Discover registered engines/versions with `/health`; cache briefly and label
  stale results. Metadata failure must not prevent an otherwise valid render.
- Default endpoint remains `https://kroki.io`. Only operator configuration can
  select another origin; models cannot supply URLs, headers or credentials.
- Render by POST. Auto negotiates PNG then SVG; explicit formats and engine
  options are validated by Kroki. No per-engine format allowlist.
- Commands: bare help, `types`, `info TYPE`, `render TYPE [options] -- SOURCE`,
  and compatible `TYPE SOURCE`. Preserve multiline/fenced source and pipe input.
- Successful results carry value, raw data and explicitly reviewed public data.
  Failures carry `result/error` and reviewed public diagnostics. Never log or
  publish diagram source/encoded source URLs or arbitrary upstream error prose.
- Deliver inline raster media or downloadable non-preview artifacts. Reuse the
  bounded core media store; SVG must not become active same-origin content.
- Native `kroki_types` and `kroki_render` use the same client and existing trust,
  authorization, budget and redaction controls. Host-delivered artifacts must
  survive a model omitting the URL or a later model failure.

## Planning Contract

Keep the separate Kroki plugin (core ADR 0002), with a pure client/result layer
and thin command/native adapters. Extend core media serving and Discord upload
classification narrowly. Do not add a standalone MCP server, schema scraper,
per-engine tools, paid inference probes or production changes.

## Implementation Units

### U1 — Safe core artifacts

Extend `src/yetibot/core/webapp/routes/images.clj` for allowed diagram formats,
MIME-aware download headers and sandboxed SVG, including legacy route aliases.
Extend Discord's existing generated-media upload flow to attach SVG/PDF/text
rather than place them in a media gallery. Preserve normal images, captions,
inspection restoration, message ownership and no-ping behavior.

Verification: focused route/header and real adapter payload/upload regressions.

### U2 — Shared Kroki client and command interface

In yetibot-kroki, extract `client` for discovery, bounded HTTP POST, MIME/size
validation, storage and reviewed results. Keep source encoding/URL helpers for
external compatibility, but never use unchecked URLs as render success. Cache
by configured origin; don't interpret health as per-render availability proof.
Commands parse leading options only, support pipe input and remove one complete
outer code fence. All engine names are strings validated as path segments, not
interned keywords. No source-bearing logs.

Verification: offline transport fixtures for future engine discovery, cache
fresh/stale behavior, Unicode/options/negotiation, explicit formats, invalid
source/type/format, timeouts, oversized/malformed responses, command grammar,
value/data/public/error alignment and no accidental source publication.

### U3 — Native adapters and host artifact retention

Add `models/ai_kroki_tools.clj`, register tool names and route adapters. Recheck
trusted source and tool policy before IO. Keep metadata/result payloads bounded;
never forward binary artifacts or source into public snapshots. Collect only
native Kroki host-owned artifacts per run and append distinct URLs to terminal
results independently of model text; preserve successful artifacts on a later
failure without rerendering. Match the shared source bound in tool argument
validation without relaxing other tools' limits.

Verification: native discovery/allowlist/opt-out, invalid whole-batch denial
before HTTP, shared tool roundtrip, actual artifact data and model omission/
model-failure retention, no arbitrary command execution, no inference in tests.

## Verification Contract

Start with focused failing regressions on existing contracts where practical.
Use the real cached dependencies/JVM plus explicit changed source reloads for
cross-repository development; disclose source-composed tests as such, not as
published-artifact proof. Run existing adjacent adapter/native tests. Avoid
repeated unchanged full suites; GitHub PR validation is the full-suite gate.
A tiny credential-free public Kroki smoke is permitted, but not required for
unit correctness. Never run paid models or production database fixtures.

## Definition of Done

- All three units implemented with focused verification and reviewed diffs.
- Plugin/API/core/app dependency order explicitly documented; do not fabricate
  a published version or silently rely on an unpublished artifact.
- Open PRs with honest CI and dependency state; no merge without operator grant.
- Rich chat outputs, pipeline data and public errors describe the same operation.

## Evidence and Risks

The current app pins plugin `20260605.152659.c135d20`; plugin master a463fc8 has
an SVG-only workaround but still hardcodes 28 types. Public `/health` reported
Kroki 0.36.0 with goat/lini/diagramsnet/dot. Upstream DiagramHandler supports
Accept negotiation and per-engine options; health does not expose format/option
schemas. Core's existing in-memory store is bounded to 100 entries and is not
permanent storage or private authenticated hosting. Public-service rendering
sends source to that service; POST removes source from artifact URLs, not from
the remote renderer. Artifacts expire on eviction/restart.
