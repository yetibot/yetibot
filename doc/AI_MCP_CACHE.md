# MCP discovery availability and cache

AI tool discovery is best-effort **per server**. An offline or malformed MCP
server no longer aborts the public catalog or removes native Yetibot tools.
Discovery never invokes a tool, inference provider, help command, or history
query. Server instructions remain public, bounded, explicitly untrusted data;
they are not applied to the model.

## Lifecycle

Pinned `yetibot/core` starts with `-main -> loader/load-all`, which loads
observers before commands. `yetibot.observers.ai-mcp-catalog` registers a hook
on `loader/load-commands`. After command loading finishes, that hook starts
catalog scheduling without waiting for any network connection. Merely requiring
API/model/observer namespaces registers definitions/hooks, but does not create
threads or open connections. No core dependency changes are needed.

The lifecycle starts one timer and a bounded four-worker daemon pool. The timer
reads current application MCP config and schedules discovery immediately, then
every 30 seconds. Named toolsets prewarm their configured read scopes rather
than unrestricted raw servers or write-only connections. Thread creation does
not inherit request thread locals, and jobs receive only MCP connection and
redaction configuration, not an actor, conversation, provider config/key,
callbacks, or native closures. First remote-tool use also starts the lifecycle
for applications embedding the tool API without `load-all`. That use may return
a pending catalog. Native-only calls need no catalog workers.

`adapters/stop` and a JVM shutdown hook call `catalog/stop!`. Explicit
`yetibot.models.ai-mcp-catalog/stop!` is available to embedding applications.
Stop cancels queued work, interrupts workers, clears metadata, and prevents
late results from repopulating it. In-flight sessions close in `finally` as the
SDK returns or responds to interruption. Startup/stop are idempotent. No daemon
or network is started by unit-test namespace import.

## Defaults and bounds

Defaults are implementation constants, not additional environment settings:

- Metadata is fresh for 10 minutes after a successful discovery.
- Last-good data is usable for **at most one hour since that success**,
  including its fresh interval. Stale reads return immediately while refreshing.
- A cold or hard-expired entry returns no remote tools until refresh succeeds.
- At most one refresh is in flight per configuration fingerprint.
- Failures back off exponentially from 10 seconds up to 5 minutes. Reads during
  backoff do not retry; other servers remain independent.
- There are at most 64 metadata entries, with oldest scheduling age eviction,
  four active jobs, and 64 queued jobs. Rejected jobs become unavailable with
  backoff, never synchronous network work. Evicted jobs cannot restore entries.
- A server catalog is limited to 64 tools and 1,048,576 serialized characters.
  Tool names are limited to 256 characters. Description/title/instructions are
  bounded to 20,000 characters. Schema/annotation fields are limited to 32,768
  serialized characters, 4,096 nodes, and depth 32; unsupported fields are
  omitted with a public explanation. SDK page/response/time limits still apply.
- Elapsed TTL/backoff time uses a monotonic clock. Tests inject fake clocks and
  executors; they do not sleep to advance freshness.

The fingerprint is SHA-256 over the current connection, timeout, configured
base server allowlist, effective request/toolset scope, and MCP credential
redaction inventory. Changes cannot use an earlier connection's catalog.
Discovery preserves the effective scope, intersected with any explicit base
allowlist. Different scopes have separate entries: a large unrestricted
catalog cannot suppress a small allowed subset. Identical scopes share public
metadata, but reads still intersect it with **current** allowlists,
actor-visible toolsets, and reserved GitHub policy. A tool absent from current
configuration or advertisement cannot be restored by an older actor's entry.

The cache is memory-only. Its metadata atom contains only bounded JSON DTOs,
hashed keys, and scheduling numbers/booleans/opaque tokens. It contains no MCP
clients, transports, sessions, connection config, credentials, headers,
functions, native closures, or exceptions. Known MCP credentials (including
Bearer and JSON/unicode-escaped forms) are redacted before DTO storage and
bounding. Discovery supplies the complete MCP credential inventory to the
connection helper before initialize instructions receive their first bound;
a different server's credential cannot leak a prefix across that boundary.
Tool names requiring redaction are rejected instead of creating an incorrect
callable identity. Request/provider-specific redaction still happens
at the model/public rendering boundaries.

## Request behavior and authorization

`mcp/with-tools` retains strict, synchronous discovery by default, preserving
existing callers and write/snapshot workflows. AI listing and completion use an
explicit `:tolerant? true` mode. `:catalog? true` retains rich public metadata;
model exposure excludes unsupported schemas.

Tolerant mode returns request-local lazy handles built from **current trusted
config**, not from cached connection data. Existing toolset selection,
authorization, native selections, implicit GitHub help/history/history_count,
argument validation, and model policy narrowing remain separate from metadata.
The cache does not authorize calls. Runtime native closures never enter it.

Discovery sessions always close, including failed listing and empty catalogs.
A model deciding not to call a remote tool opens no request session. An actual
validated remote call connects a fresh session and closes it afterward, without
another `tools/list`. Authorization is checked before connecting. Connection
failure returns a safe tool error saying no call was made. Actual call failure
retains the existing unknown-outcome/no-retry behavior. Connections are not
retried automatically, and sessions are not pooled or cached.

A cold catalog intentionally does not synchronously block on failing servers.
The first request can therefore see only native tools (or no tools). Once the
background discovery completes, later requests see eligible remote tools. A
server that changes tools without changing config may be represented by bounded
last-good metadata until refresh; the server still validates actual calls.

## Public status data

`ai-tools/tool-catalog` adds:

```clojure
{:discovery
 {:servers [{:server "search" :status "fresh" :refreshing false}
            {:server "offline" :status "unavailable" :refreshing false}]}}
```

Statuses are `fresh`, `stale`, `pending`, or `unavailable`. The server array is
in current configured/visible order and never includes invisible toolsets'
servers. Partial catalogs retain healthy/native tools and expose each failed
server's status; no exception details or credential-bearing diagnostics are
returned. Completion includes the same array at
`[:result/data :mcp :servers]`. Public names/order, schemas, annotations,
truncation/omission metadata, and inert server instructions remain available.
A successful empty server is `fresh`, not unavailable.

## Local validation

Use JDK 17 and the project test environment. The focused existing Midje suite:

```text
lein midje yetibot.test.api.mcp yetibot.test.models.ai-tools
```

The deterministic cache/lifecycle suite uses `clojure.test`:

```text
lein trampoline run -m clojure.main -e \
  "(require 'yetibot.test.models.ai-mcp-catalog) \
   (clojure.test/run-tests 'yetibot.test.models.ai-mcp-catalog) \
   (shutdown-agents)"
```

Tests use fake clocks/executors, fake connections, and loopback HTTP fixtures.
The shared HTTP fixture injects a fresh synchronous cache executor and skips
catalog daemon startup, retaining real discovery, filtering, and lazy execution
connections without cross-test state or background races. They make no live
inference, paid/native execution, production, or bank calls.
