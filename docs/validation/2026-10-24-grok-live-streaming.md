# Grok live streaming and channel replies

## Scope

Discord `grok` now shows provider-exposed thinking/summary text, native web/X
search status, validated local-tool activity and a rolling answer draft.
`grok --no-thread` skips automatic thread creation and replies in the invoking
channel. Inside a thread it stays in that thread, not its parent channel.
Other adapters and Chat Completions retain their existing delivery behavior.
No core dependency or production configuration changes are required.

## Provider evidence

The xAI reasoning guide documents Grok 4.7 summaries and both
`response.reasoning_text.delta` and `response.reasoning_summary_text.delta`.
The tool-streaming guide recommends streaming for native tool observability.
The REST Responses reference documents `stream`, completed status and opaque
reasoning state; the Vercel xAI Responses schema includes native web/X search
lifecycle events, output item events and completed/done envelopes.

- https://docs.x.ai/developers/model-capabilities/text/reasoning
- https://docs.x.ai/developers/tools/streaming-and-sync
- https://docs.x.ai/developers/rest-api-reference/inference/responses
- Vercel `xai-responses-api.ts` in the [xAI Responses sources][xai-schema]

[xai-schema]: https://github.com/vercel/ai/tree/main/packages/xai/src/responses

This is a public reasoning preview, not the Grok app's private thinking trace.
Encrypted reasoning is replayed, never rendered. No unsupported summary
request parameter or synthetic model reasoning is introduced.

## Local verification

- Initial test-first run: 33 failed checks / 83 passed.
- Rolling-tail and short-secret regressions failed before their fixes.
- Real Discord edit serialization: 1 failed / 95 passed before the fix.
  Discljord 1.3.1 preserves literal edit JSON keys, unlike message creation;
  edits require `:allowed_mentions`, not `:allowed-mentions`.
- Final focused run: **278 checks passed**.
- Final full Midje run: **1,238 checks passed**; the existing clojure.test
  runner also reported 2 tests, 0 assertions and no failures/errors.
- Final CI-style CloverAge run: **1,238 checks passed**.
- Added-line 80-column and whitespace checks passed. No application lint or
  static typecheck task is configured.

Fixtures use the production parser, shared execution and real local HTTP/MCP.
A gated SSE response proves progress arrives before completion while host
function calls remain unexecuted. The next turn preserves complete opaque
output and function `call_id` replay. Invalid completed batches, stream errors,
disconnects, limits and uncertain tool outcomes cannot dispatch partial calls.

Additional regressions cover CRLF/comments/multiline frames, BOM and Unicode,
JSON-only proxies, abort-before-close ownership, callback failures, credential
splits and short-value masking, moving previews and disabled native tools.
Discord tests cover coalesced views, mention suppression through the actual
pinned client's JSON serializer, delayed create/delete ordering, thread flags,
existing-thread scope and final-answer delivery after progress failures.

## Limits and qualification

SSE is bounded to 16 MiB wire bytes and 4 MiB characters per frame, with a
checked 120-second deadline. Blocking reads can last until socket timeout.
Progress retains bounded rolling excerpts and three recent tool statuses;
credential-sized edges are withheld to prevent split-token leaks. Long known
secrets can suppress text previews. Credential redaction is not general secret
detection, and previews can contain supplied private context. Stream failure
is not retried: upstream work may already have incurred charges.

Manual correctness/security/compatibility and inline simplification checks were
performed. Independent code review was skipped: delegated review was not
authorized. This is not an independent-review receipt.

No live xAI request, paid model call, Discord message, deployment, runtime
reload, production credential/configuration change or alias edit was made.
Provider/model and live Discord qualification remain post-deployment checks.
