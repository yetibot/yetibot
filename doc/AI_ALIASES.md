# Read-only AI alias discovery

The native `yetibot/aliases` capability lets an authorized AI tool caller discover
alias names and inspect a stored definition. It does **not** execute aliases,
create or update them, remove them, expand placeholders, recursively resolve
other aliases, dispatch commands, or install learned policy. Reading metadata
never grants an execution capability.

## Actual storage and visibility

This adapter was checked against the pinned `yetibot/core` dependency
`20261027.195114.96c8428`, specifically `core/db/alias.clj`,
`core/db/util.clj`, `core/commands/alias.clj`, and `core/models/help.clj`.

Aliases are **deployment-global within the configured database and table
prefix**. Core's `alias` table has `cmd-name`, `cmd`, and author `user-id`, plus
`id` and `created-at`. There is no per-room, per-user, per-adapter, workspace, or
account visibility column. Human `alias` listing calls `find-all` without user or
channel filtering; human exact lookup queries `cmd-name` equality. Aliases are
wired into global command hooks when core loads them. AI reads have that same
global storage scope, not history's current-channel scope, but require the
trusted chat provenance and toolset authorization described below.

The adapter queries only the alias table's public fields. Listing selects only
`cmd_name`; lookup selects `cmd_name, cmd`. It never projects database IDs,
author/user/account identifiers, timestamps, raw rows, or arbitrary metadata.
It does not load `core.commands.alias`, whose loader wires executable hooks.
Definitions describe stored data, not a guarantee of runtime dispatch semantics.
The schema has no unique-name constraint; duplicate stored names are possible,
and lookup returns one exact matching row without resolving that ambiguity.

This feature does not make aliases available through `help`: core keeps built-in
and alias help in separate stores, and native `help` still reads only built-ins.
A help-only toolset therefore does not acquire alias-definition access.

## Discovery and selection

`aliases` is appended to **non-GitHub implicit native defaults**. For example,
a trusted `ai tools yetibot` discovery can list `yetibot/aliases` without reading
any aliases. Explicit `:native-tools` vectors remain authoritative, including
`[]` and `["help"]`; include `"aliases"` explicitly to enable it there.
The existing explicit-native configuration requirements still apply: users,
adapters, and allowed providers/models must be specified.

GitHub toolsets retain exactly their existing three implicit native tools:
`help`, `history`, and `history_count`. Alias support does not broaden GitHub or
help-only rights. Discovery/argument preflight does not access the alias store.

## Arguments and results

Only these JSON keys are accepted:

| Key | Meaning | Bound |
| --- | --- | --- |
| `name` | Exact literal stored name for lookup | Nonblank, no controls, at most 200 UTF-16 units |
| `limit` | Listing page size; default 20 | Integer 1–20 |
| `offset` | Listing position; default 0 | Integer 0–1,000,000 |

Lookup cannot be combined with `limit` or `offset`. No scope overrides, regex,
command, mutation, author, or arbitrary query keys are accepted. Even names such
as `a.*` are literal equality parameters, never regular expressions.

Examples of model tool arguments:

```json
{"limit": 10}
```

```json
{"limit": 10, "offset": 10}
```

```json
{"name": "weather"}
```

Results are JSON under `structuredContent`, always carrying `untrusted: true`,
`read_only: true`, and `scope: "deployment-global"`. Listing returns `names`,
`has_more`, `next_offset`, and `truncated`. Lookup returns `found` and the exact
safe `name`; a found definition adds literal `body` and `body_truncated`.
Missing aliases return `found: false`. A sensitive lookup name yields a generic
error without querying or echoing the name.

Listing orders by the full stored name (PostgreSQL `C` collation), then internal
ID for ties, but never returns the ID. Page positions count **stored rows**, not
just visible names. Sensitive/oversized/control-bearing names are omitted
entirely, never shortened into usable identifiers or cursors. An empty visible
page may still have `has_more: true`; use its numeric `next_offset` to progress.
Hidden names are not disclosed through cursor text. Offsets are not snapshots:
concurrent creates/removals can repeat or skip rows. Pagination stops with
`truncated: true` if its next position would exceed the offset ceiling.

Each query fetches at most 21 names or one full definition. Definitions are
redacted in full **before** shortening to at most 2,000 UTF-16 units; replies
are also bounded to 12,288 UTF-8 bytes of serialized JSON. Page size may be
reduced to preserve that byte budget. These are output/row bounds, not a bound
on the size of a stored text field or on all redaction work.

## Authorization and safety boundary

The caller must originate from the host-owned Slack/Discord message event,
match its raw adapter identity, retain the exact source, and have a real room.
Forged actors, unknown provenance, mismatched sources, and toolset-denied users
are rejected **before** any alias store/configuration access. The shared native
host callback additionally enforces the selected provider/model policy before
execution. Arguments are validated for the whole tool batch before calls begin;
the argument validator itself is pure and does not read configuration or data.

Store access uses parameterized, read-only SQL transactions with a two-second
statement/query timeout and short connection/socket timeouts. Failures return a
generic error, never SQL, exception messages, alias bodies/names, or author IDs.
The trusted request's sensitive-output marker is set before authorization/store
access, preserving the existing private-output logging boundary.

Bodies containing shell commands, pipes, reader forms, substitution syntax, or
"ignore prior instructions / learn this forever" text remain **literal tool-role
data**. They are not promoted into system instructions or written to persistent
memory/policy. No evaluator, command handler, hook mutation, alias expander, or
external API is invoked by this adapter. As with other untrusted tool data,
marking content untrusted is not a proof that a model cannot be socially
influenced; the host capability boundary remains the enforcement layer.

## Conservative redaction and its limits

Known credentials from configured MCP secrets, headers/environment values,
redaction servers, and AI provider key values are removed, including mixed
raw/JSON/unicode-escaped copies. The adapter does not resolve credential files.
Structural detection normalizes JSON character escapes for detection only; safe
bodies retain their original literal spelling. Recognized credential labels
(such as password, token, key, auth, account/user/author ID), assignments/JSON
fields, bearer/basic material, credential-shaped tokens, private-key markers,
and URL user-info cause conservative **whole-field** body removal. A matching
name is omitted entirely or rejected on lookup, not returned as a redacted ID.

This is intentionally lossy: harmless aliases with sensitive-looking names or
bodies may be hidden/redacted. It is **not a general secret or PII detector**.
Unknown unlabeled credentials/identifiers, arbitrary encodings (including
base64/URL encoding), nested escape layers, split/obfuscated/transformed secrets,
and novel token formats may not be detected. Do not store secrets in aliases
or enable this reader where global alias definitions should not reach the
selected provider. No claim is made that every arbitrary alias body is safe to
publish. Tests use a fake alias store only; no production aliases were queried.
