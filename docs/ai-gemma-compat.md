# Cloudflare Gemma 4 native tool-response compatibility

## Observed failure and evidence

A user selecting `ai --provider cloudflare --model
@cf/google/gemma-4-26b-a4b-it` received literal native control output beginning
`<|tool_call>call:yetibot_ai{...}<tool_call|>` instead of a tool callback. The
alias expansion was correct. The adapter previously read only OpenAI's
`message.tool_calls`, so native calls in `message.content` became an answer.
The exact reported call is the `screenshot` regression fixture in
`test/yetibot/test/api/gemma_tools.clj`.

The observed text establishes the wire-format failure, not a live causal
counterfactual or model-quality comparison. Google's documentation independently
establishes this native grammar and developer-side function-object conversion:

- [Gemma 4 function calling](https://ai.google.dev/gemma/docs/capabilities/text/function-calling-gemma4)
- [Gemma 4 prompt formatting](https://ai.google.dev/gemma/docs/core/prompt-formatting-gemma4)
- [Cloudflare model and OpenAI-compatible API](https://developers.cloudflare.com/workers-ai/models/gemma-4-26b-a4b-it/)

No inference requests were used to develop or validate this compatibility fix.
No prompts, aliases, production configuration, credentials, or provider/model
selection were changed. Documentation GETs are not inference experiments.

## Narrow wire-boundary behavior

`yetibot.api.ai/chat` normalizes only the explicitly requested exact model
`@cf/google/gemma-4-26b-a4b-it` on the official Cloudflare Workers AI account
Chat Completions endpoint. A nonempty structured `tool_calls` batch remains
authoritative. Other models, generic providers, and Responses are unchanged.

Normalization requires actual advertised tools, provider tool calling enabled,
and tool choice other than `none`. A length-limited, filtered, malformed, or
unsupported native control response fails with the static message
`AI provider returned an unsupported tool response.` No raw response or parser
exception is attached to that error. Native control with tools disabled fails
rather than becoming a successful answer.

Only a **whole** control envelope is executable:

```text
<|tool_call>call:NAME{key:VALUE,...}<tool_call|>
```

Repeated complete envelopes are supported. ASCII whitespace may separate
calls, and the documented empty `<|tool_response>` tail is optional. There is
no substring extraction from prose, fenced code, quoted examples, or arbitrary
commands. Leading native thought/response controls are unsupported and fail
statically; the parser does not extract calls from thought text.

`yetibot.api.gemma-tools` uses a cursor-based data parser, not Google's demo
regex, JSON repair, Clojure/EDN readers, dispatch, evaluation, or command
interpretation. It accepts literal `<|"|>`-delimited strings, ASCII identifier
keys, nested objects/arrays, booleans, null, and bounded JSON-style numbers.
Strings are literal: JSON escape decoding is not applied. Duplicate keys,
quoted/reader keys, nonfinite numbers, trailing data, and truncated calls fail.
Additional protocol tokens inside argument strings are rejected conservatively.

Parser limits (independent of stricter downstream limits):

| Resource | Limit |
| --- | --- |
| Complete input | 65,536 UTF-8 bytes and characters |
| Nesting | 16 |
| Value nodes across calls | 4,096 |
| Fields per object | 128 |
| Calls per response | 20 |
| Identifier | 128 ASCII characters |
| String | 16,384 characters |
| Number spelling | 64 characters |
| Absolute numeric value | 10^15 |
| Absolute decimal scale | 308 |

The parser ceiling does **not** increase the host's existing tool-call budget,
argument size, schemas, authorization, or child output limits. Converted calls
get UUID-based IDs and ordinary JSON argument strings exactly once, before
`ai/message` and `ai/continue-messages` consume the response. Thus continuation
reuses the exact same assistant call IDs and corresponding tool reply IDs.
All converted calls pass through the existing whole-batch name/schema/policy
preflight and shared budget. An unknown name or any invalid call in a mixed
batch runs zero callbacks. Uncertain paid outcomes still stop later calls,
without retries.

## Reasoning and replay boundary

Google documents retaining thought context *within* a tool-using model turn.
This change does not parse, expose, summarize, or fabricate thought text. The
official [Cloudflare synchronous input schema](https://developers.cloudflare.com/ai/models/@cf/google/gemma-4-26b-a4b-it/sync-input.json)
at `#/oneOf/1/properties/messages/items/oneOf/3` documents assistant fields
`role`, `content`, `refusal`, `name`, `audio`, `tool_calls`, and `function_call`.
It does not document `reasoning_content`, `reasoning`, `tool_responses`, or a
thought-signature replay field. Tool-role messages document `role`, `content`,
and `tool_call_id`. No speculative fields are added to Chat Completions requests.
Supported reasoning/search replay on Responses remains unchanged. Raw native
thought-prefixed envelopes are deliberately unsupported rather than stripped
and executed. Adding opaque Cloudflare replay fields requires evidence of
request-schema support and separate focused tests; this patch does not claim
that unverified capability.

## Offline proof and limits

`test/yetibot/test/integration/ai_gemma_tools.clj` uses the production native
`tools/complete` runner, native AI callbacks, child `ai/complete`, adapter HTTP
request construction, and parent continuation. Only HTTP, remote catalog, and
configuration boundaries are mocked. It proves:

- The exact screenshot call produces a real native delegation callback.
- Three native calls make exactly three child callbacks/HTTP POSTs and two
  parent POSTs, ending in a parent final answer.
- Every child receives the same supplied question, no parent context/history,
  empty tools, `tool_choice: none`, and wire output caps of 1, 1024, and 2048.
- Catalog eligibility is dynamic fixture data, not a production model allowlist.
- Child structured recursion requests do not start another tool loop.
- Unknown names, invalid/mixed batches, malformed/duplicate arguments,
  unauthorized actors/models, and exceeded shared budgets fail closed.
- Unknown paid outcomes cause one attempted child and skip later children.
- Command-like supplied text remains inert child input.

Parser/API tests additionally cover grammar bounds, readers/dispatch payloads,
unterminated strings/calls, non-Gemma isolation, no-tools behavior, structured
call precedence, and stable continuation IDs. Mocked child answers are fixtures,
not evidence of live model accuracy, availability, or causal success. Full CI,
parent review, merge, deployment, and any future separately approved live test
remain outside this offline proof.

The focused ten-namespace suite passed 621 checks after implementation.
A separate baseline-HEAD `ai.clj` source overlay under `/tmp/gemma-red/src`
(without changing the worktree) made these same new integration regressions
fail: 56 failed checks, including zero screenshot/three-child callbacks. This
is the observed red/green regression, not a live-model experiment.
