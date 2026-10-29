# Safe AI provider diagnostics

These diagnostics observe failures on the next ordinary bot request. They do
not issue probes, retries, inference, searches, or catalog requests themselves.
They do not fix or establish the cause of provider HTTP 400 responses.

## Capture

A failed `yetibot.api.ai` request emits one warning named
`ai-provider-diagnostic`. The same closed map is available as
`:ai/diagnostic` in exception data, alongside the existing `:ai/error` marker.
Human error text is unchanged. Successful responses are unchanged and do not
produce diagnostic records. No original exception or cause is retained.

The map contains only these host-owned fields:

- `:stage`: `:initial`, `:follow-up`, `:delegation`, or `:unrecognized`.
  The parent tool runner marks actual turns; single-hop children mark
  delegation. An unmarked chat call defaults to initial. Model discovery has
  an unrecognized stage.
- `:endpoint`: `:chat-completions`, `:responses`, `:models`, or
  `:unrecognized`; `:stream?` is a boolean.
- `:provider`: exactly configured `grok`/`xai`, `openai`, and `cloudflare`
  names map to coarse keyword tags; all other selectors become `:other`.
- `:model`: a small exact allowlist maps known Grok selectors to `:grok`
  and `gpt-oss-120b`/`@cf/openai/gpt-oss-120b` to `:gpt-oss`.
  All other selectors become `:other`. These tags are not provider attestation.
- `:tool-count` saturates at 64; `:item-count` saturates at 256. These are
  shape counts, never token usage, prices, or financial values.
- `:tool-choice`: `:auto`, `:none`, `:required`, `:omitted`, or
  `:unrecognized`, taken after existing provider-specific transformations.
- `:item-types`: distinct finite categories among the first 256 input items:
  message, reasoning, function call/output, web/X search, or unrecognized.
  `:opaque-state?` reports only encrypted-state field presence in those items.
- `:failure`: `:http`, `:stream`, or `:transport`; `:status` is an integer
  from 100 through 599, or `:unrecognized`.
- `:error-class`: invalid request, authentication, model access, rate limit,
  server, or unrecognized, from exact public error-type matches.
- `:error-code`: invalid tool schema, unsupported parameter, missing tool
  output, context limit, model/access, rate limit, or unrecognized, from exact
  public error-code matches. No message-text inference is performed.
- `:parameter`: tools, input, messages, model, token limit, tool choice,
  other known public parameter, or unrecognized. Only the public root family
  is emitted, never an index, subfield, or provider-supplied parameter text.

Unknown values become fixed `:unrecognized` categories; arbitrary provider
and model selectors instead use fixed `:other`. No raw error code, class,
message, body, prompt, header, configuration, URL, path, identity, tool name,
argument/result, request/response object, financial record/value, request ID,
account ID, exception, or stack is logged by this capture.

## Bounds and limitations

Non-stream requests deliberately retain their existing `:as :json` transport.
That transport has already decoded the body before diagnostic inspection.
The new inspection does not serialize or walk it: it accesses only the error
container and three shallow scalar fields, each limited to 128 characters.
This change does **not** impose new byte/depth bounds on the inherited
non-stream JSON decoding. Changing that transport requires separate review.

Existing streaming HTTP errors get best-effort JSON inspection capped at
16 KiB plus one detection byte, with nesting depth at most eight. A
one-second cooperative read budget is checked between reads; a blocking read
remains bounded by the existing socket timeout, not by that shorter budget.
Unread, malformed, oversized, deep, interrupted, or timed-out error bodies
produce unrecognized categories. Cleanup still aborts the HTTP owner before
closing the body, without draining an unbounded remainder.

SSE error events reuse the same shallow classifier after existing bounded
SSE parsing. EOF, malformed streams, and other failures keep their existing
human error text and receive unrecognized error categories when no recognized
public fields are available. Diagnostic logging is best-effort; an appender
failure cannot replace the provider error or bypass transport cleanup.

The private-output logging filter admits only this diagnostics namespace in
addition to the existing proposal audit namespace. It still suppresses
ordinary request/body logs once private execution is active.

## Invariants

The internal namespaced stage option is never selected into JSON or HTTP
options. Diagnostic collection does not change endpoint selection, tools,
tool choice, native search, authorization, history, billing, output-token
caps, success decoding, or child isolation. An uncertain child still halts
later tools and is never automatically retried.

Tests use fake HTTP responses, synthetic secret/financial sentinels, and
local fixtures only. No live provider diagnosis or production mutation is
required. A provider that supplies only prose error messages will deliberately
remain unrecognized; absence of a category is not evidence of a root cause.
