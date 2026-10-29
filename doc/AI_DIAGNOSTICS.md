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
  `:opaque-state?` reports only a nonempty string `encrypted_content` value
  in those items, not mere field presence.
- `:failure`: `:http`, `:stream`, or `:transport`; `:status` is an integer
  from 100 through 599, or `:unrecognized`.
- `:error-class`: invalid request, authentication, model access, rate limit,
  server, or unrecognized, from exact public error-type matches or the known
  Grok `invalid-argument` code.
- `:error-code`: invalid tool schema, unsupported parameter, missing tool
  output, context limit, model/access, rate limit, or unrecognized, from exact
  public error-code matches, then bounded known validation phrases when no
  recognized code matches. Only fixed categories escape this inspection.
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
The inspection does not serialize or recursively walk it: it examines one
root/error map and at most three entries of a vector `errors` container (as
used by Cloudflare). Per candidate, `type`, `code`, and `param` strings are
limited to 128 UTF-16 units; one `message` (or fallback `error`) string is
limited to 1024 UTF-16 units. Oversized strings are skipped, not truncated.
Exact codes take precedence over recognized validation phrases in that
candidate. It emits categories only, never inspected text or regex captures.
This change does **not** impose new byte/depth bounds on the inherited
non-stream JSON decoding. Changing that transport requires separate review.

Existing streaming HTTP errors get best-effort JSON inspection capped at
16 KiB plus one detection byte, with nesting depth at most eight. Only for
non-2xx stream error inspection, a one-second timer aborts the saved clj-http
`HttpUriRequest` owner to unblock socket reads; the cooperative read checks
remain too. One shared daemon scheduler carries only that owner, without
inheriting thread locals or conveying dynamic bindings. Its timer is cancelled
in `finally`, and cancelled tasks are removed from its queue. No timer is
scheduled for successful responses or non-stream JSON responses.

Without an abortable owner, inspection is skipped unless the body's exact
class is the in-memory `ByteArrayInputStream` (not an arbitrary subclass).
Unread, malformed, oversized, deep, interrupted, or timed-out error bodies
produce unrecognized categories. Cleanup still aborts the HTTP owner before
closing the body, without draining an unbounded remainder. The timer is an
abort-backed guard, not a real-time JVM guarantee: scheduler pauses and the
HTTP owner's abort implementation can delay cancellation. Existing success
transport, socket timeouts, wire options, and stream budgets are unchanged.

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
required. Known bounded Grok and Cloudflare validation phrases can produce
categories even without structured codes. Arbitrary prose remains
unrecognized; presence or absence of a category is not evidence of a root
cause.
