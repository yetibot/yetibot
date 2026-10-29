# AI command

`ai` works with OpenAI-compatible Chat Completions endpoints
(`POST /chat/completions`) and xAI Responses (`POST /responses`), with model
listing through `GET /models` or Cloudflare's Workers AI catalog. You can
configure multiple providers, each with
its own name, base URL, optional API key and default model. The `grok` shortcut
shares this configuration and MCP tools. The existing `openai` command is
unchanged and uses separate configuration.

## Configuration

Add `:ai` inside `:yetibot` in `config/config.edn` (or your `CONFIG_PATH` file):

```clojure
{:yetibot
 {:ai
  {:default-provider "openai"
   :providers
   [{:name "openai"
     :url "https://api.openai.com/v1"
     :key "YOUR_API_KEY"
     :model "gpt-4o-mini"}
    {:name "local"
     :url "http://localhost:11434/v1"
     :model "llama3.2"}]}}}
```

Or use Yetibot's environment configuration:

```sh
export YETIBOT_AI_DEFAULT_PROVIDER=openai
export YETIBOT_AI_PROVIDERS_0_NAME=openai
export YETIBOT_AI_PROVIDERS_0_URL=https://api.openai.com/v1
export YETIBOT_AI_PROVIDERS_0_KEY=YOUR_API_KEY
export YETIBOT_AI_PROVIDERS_0_MODEL=gpt-4o-mini
export YETIBOT_AI_PROVIDERS_1_NAME=local
export YETIBOT_AI_PROVIDERS_1_URL=http://localhost:11434/v1
export YETIBOT_AI_PROVIDERS_1_MODEL=llama3.2
```

- Provider names must be unique and cannot contain whitespace.
- `:url` is the API **base URL**, including any required prefix such as `/v1`.
  Do not include `/chat/completions`, `/responses` or `/models`.
  Trailing slashes are allowed.
  URLs must use HTTP or HTTPS and cannot contain credentials, query strings or
  fragments. Use HTTPS for remote providers.
- `:key` is optional, for local servers that do not need authentication. When
  present, it is sent as a Bearer token. Keep keys out of version control.
- Alternatively use `:key-file "/absolute/private/token-file"` (environment:
  `YETIBOT_AI_PROVIDERS_0_KEY_FILE`). It must be an absolute, readable regular
  file containing a nonblank token, at most 8192 bytes. Surrounding whitespace
  is trimmed; the token itself must be printable ASCII without whitespace or
  header newlines. `:key` and `:key-file` are mutually exclusive. Credentials
  are resolved privately for selected requests, including background metadata
  requests to that provider's configured model-list endpoint. Configuration and
  prompt preparation never open every provider's key-file. Missing/invalid files
  fail closed without exposing paths or token contents.
- If `:default-provider` is omitted, the first provider is used.
- A provider's `:model` is optional for listing models. Prompts need either this
  default or a `--model` argument. Use model IDs supported by your server.
- Requests never follow redirects or automatically retry failed requests.
- Configuration is loaded at startup, like other Yetibot configuration.

## Usage

```text
!ai providers
!ai models
!ai models local
!ai Explain the difference between map and reduce.
!ai --provider local Write a short poem about snow.
!ai --provider local --model llama3.2 Explain this error.
!ai --model gpt-4o-mini Summarize this text.
!ai --model @cf/meta/llama-3.1-8b-instruct-fp8 Say hello.
!ai -- --provider is literal text in this prompt.
```

Options go before the prompt and may appear in either order. Without `--model`,
the existing default-provider/default-model behavior is unchanged. Explicit
`--provider` (and pinned commands such as `grok`) is authoritative: it does not
wait for or consult model catalogs. With only one configured provider, `--model`
continues to pass the ID through to that provider.

With multiple configured providers, `--model <exact-id>` alone routes only when
exactly one provider advertises that **exact, case-sensitive ID**, and fresh,
complete model-ID observations exist for **every current configured provider**.
There are no substring/vendor-prefix guesses or fallback to the default provider.
Collisions are errors. On cold startup, failed/unknown/incomplete discovery,
stale observations, or configuration changes, specify `--provider <name>`; no
inference request is sent while automatic routing is uncertain. Catalog presence
does not prove account entitlement, chat compatibility, tool support, or tool
quality. Cloudflare observations cover its full advertised Workers AI catalog,
not third-party Gateway models; use an explicit provider for unlisted IDs.

Cloudflare's pagination total can exceed the number of returned models.
Discovery traverses the entire reported page range, including short or empty
intermediate pages, and checks stable page metadata. It neither invents missing
models nor requires the visible record count to equal that larger total.

Startup schedules nonblocking background discovery, refreshed on a 30-second
timer with five-minute monotonic freshness and bounded failure backoff. Imports
start no workers or network requests. Metadata jobs reuse `ai models`' configured
GET paths only; they never send prompts or paid inference POSTs, follow redirects,
or retry a failed HTTP request. The cache retains IDs and freshness/status only,
not credentials, clients, headers, or execution handles. It is bounded to 32
providers, 8,192 IDs per provider, 512 UTF-8 bytes per ID, and 256 KiB of ID bytes
per provider; over-limit catalogs cannot establish uniqueness. Configured
credential references are resolved only inside each selected metadata request.

`ai models` queries every provider and labels each model list with the provider's
name. A failure from one provider does not hide results from the others.
`ai models <provider>` queries just that provider. These listing commands do not
bypass automatic routing's all-provider completeness requirement.

## Piped structured input

`ai`, `grok`, and aliases expanding to those commands receive the current
pipeline's selected structured data, not just its displayed label. For example:

```text
!cf models | random | gemma Render every returned field with clear headings.
!cf models | random | ai --no-tools Format all fields and units.
!cf models | head 2 | grok --no-tools Compare these model records.
!ai tools | head | ai --no-tools Explain this tool's full input schema.
```

The existing `gemma` alias need not change. `data show` is optional. Aligned
collection data takes precedence over the enclosing API response, so `random`
and `head` pass only the chosen records, not unselected records. Plain piped
text, including multiline text, is also supported. The ordinary appended display
label is removed from the prompt; explicit inline substitutions retain their
existing semantics. A pipe-only AI request responds to the supplied input.

Input is snapshotted before asynchronous work, then attached as a separate
**untrusted user-data** JSON message before the current request. It grants no
tool access, command execution, or write authority. Tools remain policy-bound;
`--no-tools` still disables them. Delegated children do not implicitly inherit
piped data, parent history, or tools. Formatting supplied metadata requires no
additional catalog fetch or child-model invocation.

Only JSON-compatible values are accepted. Keywords become strings while
preserving namespaces; object keys must remain unique. Nested false/null values,
arrays, objects, strings, and finite bounded numbers are preserved. Snapshot
limits are 64 KiB of serialized UTF-8, depth 12, 2,048 nodes, and 128 entries
per container. The final redacted data message is limited to 12 KiB. Known
selected provider/MCP credentials and sensitive structured fields are redacted
before that final limit, including literal copies in rendered alias text. This
is conservative filtering, not universal secret or PII detection. Only the
selected provider credential may be resolved, after normal tool authorization;
preparation reads no credential files or catalogs.

Unsupported or over-limit input stops the pipeline before inference with a
static error asking you to select less data. There is no silent truncation or
reader/eval fallback. Select `random`, `head`, or a smaller subset rather than
sending a large catalog. Existing upstream truncation markers remain data;
formatting a record does not prove account entitlement or model quality.

## Cloudflare inference and model discovery

Cloudflare inference is an AI provider, not a Cloudflare MCP server. Add an
entry to your existing `:ai :providers` vector:

```clojure
{:name "cloudflare"
 :url "https://api.cloudflare.com/client/v4/accounts/ACCOUNT_ID/ai/v1"
 :key-file "/run/secrets/cloudflare-workers-ai-token"
 :model "@cf/zai-org/glm-4.7-flash"
 :gateway-id "my-gateway"}
```

Replace `ACCOUNT_ID` with the account's 32-character hexadecimal ID. Scope the
token to that account with Workers AI Read permission. Inference can consume
account credits/allowances; a management-only AI Gateway token is insufficient.
`:gateway-id` is optional. It sends `cf-aig-gateway-id` for this Cloudflare
provider; Workers AI model IDs beginning with `@` use `default` when omitted.
Third-party models without an explicit gateway use Cloudflare's account default.
Arbitrary provider headers are not configurable.

```text
!ai models cloudflare
!cf models
!cf Say hello.
!cf --model @cf/qwen/qwen3-30b-a3b-fp8 Say hello.
!ai --provider cloudflare --no-tools Say hello.
```

Choose a chat-capable model supported by your account. There is no hardcoded
model menu or mandatory default model: omit `:model` and select one per prompt,
or add `:model` as your preferred default. Discovery does not choose a default.
Changing the shared default provider does not reroute pinned `grok` calls.

For this account REST base, `ai models` calls
`GET /accounts/{account_id}/ai/models/search`, not `/ai/v1/models`. It retrieves
up to 20 pages of 100 entries, deduplicates/sorts IDs, and retains bounded
model metadata in pipeline data under the provider's `:catalog`. The coverage is
**Workers AI only; third-party Gateway models not included**. This is not the
complete AI Gateway catalog or proof that every returned model accepts chat,
images, or tools. Clef/Clairic are labelled decision/extraction interfaces, not
chat models. Text Generation entries are labelled `chat`; other tasks and
unknown tasks remain `model-specific`. Other providers retain standard
`GET /models` discovery.

`cf models` shows the **full Workers AI catalog**, with concise task/interface
labels. Its `:result/collection-path` links each label to a structured model
record, so `!cf models | random | data show` shows the selected record, not
just its display string. No coverage/header row enters the model collection.
Records retain description, task, catalog UUID/source/timestamps/tags, known
properties (including price, terms/license and deprecation when supplied),
limits, and a compact schema summary. Prices retain the provider's exact units
and currency; token defaults are not presented as maximum output limits.
`function-calling` distinguishes explicit reported `supported: false` from
`status: unknown`, or `status: advertised` when tools/tool_calls appear in the
schema. Advertising a schema does not prove tool quality, successful follow-up
serialization, or account entitlement. Missing metadata is not a negative
capability or a zero price. For example, the official public
[llama-3.1-8b-instruct-fp8 docs][cf-llama-fp8] advertise `tool_calls`.

This projection follows the official
[Workers AI model records][cf-model-records] and their `properties` array; it
does not merge the third-party AI catalog or hardcode a model capability menu.
Unknown fields and full schemas are omitted. Credentials are redacted before
truncation; records are capped at 8,000 UTF-8 JSON bytes, strings at 2,000
characters, containers at 32 entries and nesting at five levels. Oversized
metadata sets `metadata-truncated`; omissions do not imply lack of support.

[cf-llama-fp8]:
  https://developers.cloudflare.com/workers-ai/models/llama-3.1-8b-instruct-fp8/
[cf-model-records]: https://github.com/cloudflare/cloudflare-docs

Public record fixtures are in that repository under
`src/content/workers-ai-models/`; they are documentation, not live quotes.

`cf [--model <id>] [--toolset <name>] <prompt>`
uses the fixed `cloudflare` provider and only the supplied prompt, with no
implicit history, child tools, native searches, or recursion. Each request is
capped at 2048 output tokens on the wire (`max_tokens` for Workers AI Chat
Completions). Only catalogued `chat` interfaces are accepted; Clef, Clairic,
image, audio, embedding and unknown interfaces are denied before inference.
Catalog discovery is not evidence of inference entitlement or billing access.
Use normal `ai` for its existing conversation/tool behavior.

Reuse an existing intent-router token by configuring the **same absolute
`:key-file` path** on the provider. There is no implicit credential/account
inheritance and no token copy into EDN. Append this provider without changing
`:default-provider`, existing provider order, or router enablement. The model
above is a recommendation, not a hardcoded menu or inference-access claim.

Use supported third-party Gateway IDs explicitly with `--model`; their absence
from this catalog does not mean they are unavailable. See Cloudflare's
[Workers AI catalog][cf-models] and [Gateway REST documentation][cf-rest].
Review Gateway logging and caching settings before sending private content.

## Standalone Clef and Clairic

`clef` returns decisions/probabilities; `clairic` extracts typed values.
They use dedicated Workers AI APIs, not Chat Completions, and return data only:
neither command invokes the automatic-PR router, enables it, calls MCP tools,
or grants permission to edit repositories or publish changes.

Both reuse the existing `:ai :intent-router` account and server-side token file,
**even when `:enabled` is false**. Add this inside `:yetibot :ai`:

```clojure
{:intent-router
 {:enabled false
  :account-id "0123456789abcdef0123456789abcdef"
  :key-file "/run/secrets/cloudflare-workers-ai-token"
  :gateway-id "my-gateway"}}
```

Keep the token file absolute, readable only by the bot's service user, and out
of version control. Use an account-scoped Workers AI Read token. These commands
need no AI provider entry. Optional `:gateway-id` sends the routing header for
that Gateway; it does not change the existing automatic intent classifier's
transport or enabled state. To use prepaid Gateway credits, configure its
Workers AI billing setting to Unified billing and supply its ID. Account/model
access and billing still need live qualification. These settings also
normalize from `YETIBOT_AI_INTENT_ROUTER_ACCOUNT_ID`,
`YETIBOT_AI_INTENT_ROUTER_KEY_FILE`, and
`YETIBOT_AI_INTENT_ROUTER_GATEWAY_ID`.

### Decisions

```text
!echo service is down | clef Is this an unresolved outage?
!clef Is this a receipt? https://example.com/receipt.png
!echo down | clef --questions '{:x {:type :noul :instructions "Bad?"}}'
```

Plain questions produce an answer named `decision` with `P(yes)`. Supply state
through a pipe or image, rather than asking Clef to classify its own question.
`--questions` accepts one quoted EDN map with 1–64 questions. Each entry has
`:type` and `:instructions`; instructions may be text or structured data.
Question IDs contain letters, digits, `_`, `.`, or `-`, up to 100 characters.
Supported types are:

- `:noul`: yes/no probability. Optional `:criteria` describes true/false.
- `:choice`: `:criteria` maps 2–255 option IDs to descriptions.
- `:score`: `:criteria` is an ordered vector of 2–10 level descriptions.

For example, a choice schema is:

```clojure
{:team {:type :choice
        :instructions "Which team should review this?"
        :criteria {:ops "Service incidents" :sales "Purchase questions"}}}
```

Pass it as a single quoted argument to `clef --questions`. State may follow the
schema inline or arrive through a pipe. EDN is parsed as data, never evaluated.

### Quick Clairic experiments (no schema)

Start with `!clairic demo`. It makes a real Clairic request using built-in text
`Balloon count: 3.`, then reports whether the extracted count is **3**.
It deliberately ignores attachments and piped input so this is a repeatable
text-only smoke test. It is not a canned answer or an image test.

For your own input, start the request with one of these verbs. Yetibot builds
the typed fields locally; there is no extra model call, provider, or credential.
These are four small presets, not an arbitrary question-answering interface.

| Request | Result field | Meaning |
| --- | --- | --- |
| `count …` or `how many …` | `count` | Integer count from text/images |
| `rate …` | `rating` | Subjective estimate on a 0–10 grid, just for fun |
| `read …` | `text` | Text extracted from the supplied source text |
| `list …` | `items` | List of text extracted from the supplied source text |

Try these as single-line commands:

- Attach a meme, then send `!clairic count helicopters`.
- Attach a meme, then send `!clairic rate how absurd this commute is`.
- `!echo red balloon, blue balloon | clairic count balloons`
- `!echo callsign TRASH PANDA ONE | clairic read the callsign`
- `!echo pilots Raccoon and Goose | clairic list the pilots`
- `!echo balloons: 2 | clairic count balloons | data $.answers.count.value`

Direct HTTPS image URLs work after the request or through a pipe, just like
attachments. Pipe source text separately from the request: the request itself
is instructions, not input to count or extract. `read` and `list` extract source
text; they do **not** invent captions, roasts, hazards, or image descriptions.
Use `ai`/`grok` for creative writing and `clef` for yes/no probabilities.
Image counts can still be wrong, and ratings are not calibrated measurements.

Ratings display as `rating: 8/10`; the pipeline value remains a number at
`$.answers.rating.value`. Counts use `$.answers.count.value`; read/list use
`$.answers.text.value` and `$.answers.items.value`. Generated fields are visible
at `$.request.fields`, alongside the unmodified inference answers and usage.
Quick image commands warn when Cloudflare explicitly reports zero image tokens;
`data $.usage.image_tokens` can help diagnose whether an image was processed.
A successful text demo does not establish that image inference works.

### Advanced extraction and downstream data

Explicit fields still work unchanged:

- `!echo total 42 | clairic --fields '{:amount :number}'`
- `!clairic --fields '{:amount :number}' https://example.com/receipt.png`
- `!echo 42 | clairic --fields '{:x :number}' | data $.answers.x.value`

`--fields` accepts 1–64 fields, either shorthand (`:amount :number`) or full
specifications (`:amount {:type :number :unit "USD" :decimals 2}`). Supported
types are text, number, date, time, datetime, duration, list, estimate, and
forecast. Lists require `:items` of text/number/date; estimates and forecasts
require `:min`/`:max`. Cloudflare validates the remaining type-specific options.
Field IDs start with a letter or `_`, use letters/digits/`_ . -`, and contain at
most 64 characters. See the [Clairic schema][cf-clairic] for options and units.

Piped text/collections supply state, not the question/schema. String collections
join with newlines; other collections become structured Clef state or JSON text
for Clairic. No conversation history is fetched implicitly; pipe `history` if
that is the intended input. To forward a prior command's structured results,
use `data` explicitly; ordinary piping forwards its readable value.

Readable answers include probabilities/confidence where present. Long answer
lines shorten to 160 characters with a notice. The complete response remains
in `:result/data`, including answers, nulls, guesses, spans, distributions,
usage, and truncation metadata. `data $.answers.x.value` selects the actual
value, not a possibly incorrect `guess`. Truncated input produces a warning.
Well-formed outputs can still be wrong; confidence is not execution approval.

### Images and bounds

Attach an image or include a direct image URL in the input, a plain Clef
question, or a quick Clairic request, separated from surrounding text by
whitespace. Inline URL recognition
uses common image file extensions; extensionless URLs can arrive as image
attachments. Yetibot downloads the image itself and sends embedded bytes,
not merely a remote URL. Fetches require public HTTPS, no URL credentials,
fragments, nonstandard ports, cookies, JVM proxies, retries, or redirects. Every
resolved address is checked and DNS is pinned for the connection. The
Cloudflare token is never forwarded to image hosts.

PNG, JPEG, and WebP are supported: at most four images, 4 MiB each, 8 MiB total,
and 16 megapixels each. URLs are limited to 4096 characters. Input state is
limited to 60,000 characters; schemas/questions to 16,384. JSON responses are
stream-bounded to 1 MiB. Image bodies have a 30-second read deadline and
five-second connection/socket timeouts; Cloudflare uses a 60-second body read
deadline/socket timeout and five-second connection timeout. A blocking read may
outlast the deadline until its socket timeout; DNS uses the host resolver.

Only explicitly supplied input and attachments are sent. That content can
include private history or documents, and Cloudflare/Gateway logging settings
apply. Original chat messages and upstream pipeline commands remain subject to
normal bot history/logging. Known token strings are rejected in inputs/outputs;
this is not comprehensive secret detection or prompt-injection protection.
Provider error bodies and credential-file errors are not echoed to chat. Local
fixtures verify these contracts; account billing/model availability still need
live qualification after configuration.

### Clef and Clairic in the AI tool catalog

The same inference APIs are also native tools in the existing `ai` catalog;
there is no additional MCP server or command-interpreter bridge. When the
account ID and absolute token-file path above are configured, `clef` and
`clairic` are automatically available to trusted chat requests using ordinary
(non-GitHub) toolsets. Discovery checks configuration only: it does not read
the token, fetch images, or run inference. The token is read only on execution.
The router may remain `:enabled false`; neither tool enables automatic routing
or repository writes. Configuration is not proof of token/model/billing access.

An explicit `:native-tools` allowlist remains authoritative. Add the exact names
`"clef"` and/or `"clairic"` to the selected toolset when using one; an empty
allowlist disables them. GitHub toolsets do **not** acquire inference tools by
default. Existing user, adapter, provider/model, and trusted-event policies
still apply. These tools are grounded classification/extraction capabilities,
not creative text or image generation, and their answers never grant authority.

For a tool-capable configured chat model:

```text
!ai Classify this with Clef: is "service is down" an unresolved outage?
!ai Extract the amount as a number with Clairic from "total USD 42".
!ai Use Clairic to extract amount:number and date:date from this receipt.
```

Attach the receipt to the last message. These are ordinary executable `ai`
prompts, not new `ai classify` or `ai extract` subcommands. The chat model
chooses whether to invoke an available tool. Its structured arguments are:

- `clef`: `question` (plain yes/no, returned as `decision`) **or** `questions`
  (1–64 named specifications), plus `input` and/or images. Typed questions use
  `type` (`noul`, `choice`, `score`) and `instructions`. Choice requires a
  `criteria` map of 2–255 named descriptions; score requires 2–10 ordered
  descriptions. Native instructions/descriptions are bounded plain strings.
- `clairic`: `fields` (1–64 named specifications), plus `input` and/or images.
  Each specification has `type`: text, number, date, time, datetime, duration,
  list, estimate, or forecast, plus optional `instructions` (up to 2,000
  characters) describing what to extract. There is **no boolean type**. Number
  supports `unit` and `decimals` (0–6); duration units are seconds, minutes,
  hours, days, weeks, months or years. List requires `items` of
  text/number/date.
  Estimate and forecast require matching numeric or strict ISO-date
  `min`/`max` bounds in increasing order. This is a bounded API-schema subset,
  not the standalone CLI or an unmerged plain-English Clairic feature.

Both accept an optional `images` array of explicit public HTTPS URLs, including
extensionless URLs. Only image attachments on the actual incoming trusted event
are added automatically: the model cannot supply chat identity, an event,
provider/host, account, token, headers, or secret-file path. URLs embedded in
`input` remain text; use `images` for fetching. The existing image-loader
safeguards and limits above apply, including at most four images total, 4 MiB
each and 8 MiB combined. All arguments and attachment URLs are checked before
any image fetch or inference. No arbitrary conversation/history is read.

The native catalog limits **all serialized arguments together to 16,384 JSON
characters** (including schema and escaping); native `input` is at most 12,000
characters, unlike the standalone command's 60,000-character input allowance.
Results expose only typed answers, confidence/null metadata where present,
model and truncation flags. Provider extras, raw requests, image bytes/base64,
and error bodies are excluded. Strings/lists may be shortened with
`output_truncated`; sanitized structured content is bounded to 18,000 JSON
characters, below the shared catalog's 20,000-character reply cap. Known raw
Cloudflare, provider and MCP credential values are rejected from arguments and
redacted structurally from results, including quotes/backslashes.

Input and current attachments leave the chat for Cloudflare; Gateway logging,
caching, privacy and paid usage apply. Private-output logging safeguards are
required. Validation/image failures occur before inference; after an attempted
inference, a failure has `isError` and `unknownOutcome` because a charge or
successful request cannot be ruled out. Neither tool retries paid inference
automatically. Mocked local tests are not a live billing/model smoke test.

[cf-models]: https://developers.cloudflare.com/workers-ai/models/
[cf-rest]: https://developers.cloudflare.com/ai-gateway/usage/rest-api/
[cf-clairic]: https://developers.cloudflare.com/workers-ai/models/clairic/

## Grok and xAI Responses

Add a provider like this to your existing `:ai :providers` vector. Keep your MCP
servers and toolsets, including `cloudflare_read`, in the same `:ai` map:

```clojure
{:name "grok"
 :url "https://api.x.ai/v1"
 :key "YOUR_XAI_API_KEY"
 :model "grok-4.7"
 :api "responses"
 :native-tools ["web_search" "x_search"]
 :max-turns 6}
```

Use a model supported by your xAI account. Set `:default-provider "grok"` to
make it the default for `ai` too. A sole provider is selected automatically.
Existing providers default to `:api "chat-completions"`; they need no
configuration change. `:native-tools` and `:max-turns` are Responses-only
options.

```text
!ai --provider grok --toolset cloudflare_read List our Workers.
!grok --toolset cloudflare_read Which Cloudflare account is connected?
!grok What are people on X saying about Clojure today? Include sources.
!grok --model grok-4.7 --toolset cloudflare_read Summarize our account.
!ai --provider local --model llama3.2 Explain this error.
!grok --no-tools Explain map and reduce without searching.
!grok --no-thread Search X for recent Clojure discussions.
!grok --no-thread --no-tools Explain transducers.
```

Plain `grok` uses the provider named `grok`, never the shared default or an
unrelated sole provider. If no `grok` provider is configured, an existing
`xai.key` supplies a Grok 4.7 Responses provider with native web/X search while
still using the shared MCP runtime and policy. Without either configuration,
`grok` returns a configuration error instead of silently using another model.
`grok --model` selects a model on the Grok provider. `grok` rejects
`--provider`: use `ai --provider` to select another provider, or plain `ai`
for your shared default.

Both commands use the same thread context, MCP connections, toolset selection
and user/adapter/provider/model authorization. Discord `grok` creates a reply
thread by default. Use `--no-thread` to skip thread creation and reply in the
invoking channel. Inside an existing thread, it stays there: the flag never
moves that conversation into its parent channel or changes history/tool scope.
The flag is Grok-only, can combine with `--model`, `--toolset` or `--no-tools`,
and must precede the prompt. Use `--` to begin a prompt with a literal flag.

### Live Discord progress

With a Responses provider, Discord `grok` streams into its temporary progress
message automatically; no extra flag or configuration is needed:

- Provider-exposed thinking text/summary deltas, when the model supplies them.
  xAI documents reasoning summaries for Grok 4.7; this is not a promise of the
  Grok app's complete private thinking trace.
- Native web/X search activity and validated host function start/finish status.
  Tool arguments, tool result bodies and opaque encrypted state are not shown.
- A rolling draft-answer excerpt, updated as answer text arrives.

Updates coalesce about every two seconds instead of posting a message per
token. The view keeps the latest thinking/answer excerpts and three recent
tool statuses, within Discord's message limit. Previews are provisional and
may change; they are not tool authorization or evidence of publication.
Mentions are disabled on preview creation/edits. The temporary view is deleted
before posting the complete final answer and selected-model footer. A failed
progress create/edit/delete does not discard the final answer.

Configured provider/active-tool credentials are masked before previews reach
Discord. Credential-sized edges are buffered to prevent leaks across chunks or
rolling-window boundaries; very long configured secret values can suppress
text previews. This is not comprehensive secret detection. Thinking previews
can include supplied history or tool-derived private content, so use a channel
appropriate for the request. Normal platform history/logging still applies.

Only a completed provider response can reach host tool dispatch. Partial
function-call events never execute tools, and the existing whole-batch policy,
call budget, duplicate protection and uncertain-outcome handling still apply.
Opaque reasoning/search output replays unchanged on subsequent model turns.
A disconnect, malformed/incomplete stream or provider error fails without
resampling the request. Native provider work may already have incurred charges.

SSE accepts up to 4 MiB characters per event and 16 MiB wire bytes per model
turn, with a checked 120-second read deadline. A blocking read may outlast that
deadline until the existing socket timeout (120 seconds). JSON-only Responses
proxies can still return a bounded completed response, without live deltas.
Other adapters and Chat Completions providers retain ordinary final-answer
delivery; host tool status can still appear in Discord. No live xAI/Discord
qualification is implied by local fixtures.

See xAI's [reasoning docs][xai-reasoning] and
[streaming tool docs][xai-streaming-tools].

[xai-reasoning]: https://docs.x.ai/developers/model-capabilities/text/reasoning
[xai-streaming-tools]: https://docs.x.ai/developers/tools/streaming-and-sync

There are two distinct tool paths:

- **Native search:** xAI executes `web_search` and `x_search` on its servers.
  These are opt-in through `:native-tools`, which accepts only those names.
  They are independent of local MCP toolset allowlists. `:max-turns` (default 6,
  range 1–20) bounds server-side agentic turns **per xAI request**, not the
  whole conversation; each continuation starts a fresh server-side budget.
  Native searches can incur charges in addition to model tokens.
- **Host functions:** Grok returns structured function requests to Yetibot.
  These can be MCP tools or the built-in Yetibot tools described below.
  Yetibot validates the complete batch, applies toolset policy and the call
  budget, executes permitted tools, and returns results. Grok never receives
  MCP authentication headers or executes these functions itself.

`--no-tools` disables native search, MCP and native Yetibot tools. At the
MCP call limit, or after an uncertain MCP outcome, the final turn also has all
tool definitions removed. `:tool-calling false` continues to prohibit
client-side MCP/native Yetibot execution; server-side search is controlled
separately by the provider's `:native-tools`.

Requests are outbound-only: no public Yetibot URL, webhook or inbound callback
is needed. Responses uses `store: false` and replays the complete output plus
function results within the request loop, including opaque encrypted agentic
state. It does not rely on remotely stored response IDs. The configured model
provider still receives the prompt and allowed tool results; treat native search
queries as external disclosures too. A Cloudflare server is read-only only when
its API token has Read permissions: allowing a tool named `execute` does not
make that tool read-only.

Existing `xai.key` credentials work whether or not shared `:ai` providers are
configured. An explicitly configured `grok` provider takes precedence over
`xai.key`. Invalid shared configuration fails rather than bypassing its policy.
Image commands still use their separate `xai` configuration.

The coding agent is separate: use `agent <prompt>` when that integration is
configured. `grok agent <prompt>` is ordinary Grok chat text, not a coding-agent
shortcut. Grok help does not advertise the coding agent. This change does not
replace its existing Gemini backend or add MCP auto-discovery.

See xAI's [function calling][xai-functions] and [agentic state][xai-state] docs.
Mixed-tool behavior is covered by local HTTP/MCP fixtures; a live xAI/Cloudflare
account is still needed to qualify your deployment and model's tool support.

[xai-functions]: https://docs.x.ai/developers/tools/function-calling
[xai-state]: https://docs.x.ai/developers/tools/advanced-usage

## Native Yetibot tools

Local tools are **enabled by default** for both `ai` and `grok` in trusted
Slack/Discord chat requests. Help and history need no extra toolset, MCP server
or opt-in configuration. Media and inference tools appear when their services
are configured; Wise additionally requires account and chat allowlists.
`!ai tools` lists them alongside existing MCP tools:

- `help`: page through registered command topics or read an exact topic's docs.
  This never runs a command. Runtime aliases and their bodies are not exposed.
- `history`: read recent messages or search by literal, case-insensitive text.
- `history_count`: count messages, optionally matching the same literal text.
- `meme`: search Imgflip templates and create captioned memes when Imgflip is
  configured.
- `gi`: create/edit images through the existing Grok image pipeline when
  `xai.key` is configured. These are paid image-generation calls.
- `clef`: classify supplied input or current attachments with Cloudflare.
- `clairic`: extract typed fields from supplied input or current attachments.
  Both inference tools use the configured account/token described above.
- `ai`: list configured providers/models or ask a bounded text-only child.
- `cf`: list Workers AI models or ask a bounded text-only Cloudflare child.
  These appear only when their respective AI provider configuration exists.
- `wise`: read configured balances, transactions or period totals only from
  explicitly allowed chats. See [Wise configuration and limits](WISE.md).

This is separate from `:native-tools ["web_search" "x_search"]` on a provider,
which enables server-side xAI search. Local tools are host function calls; they
need a function-calling model and use the same host call budget as MCP tools.

Existing named toolset user/adapter/provider/model restrictions still apply.
Wise always enforces its separate account and conversation policy as well.
Without named toolsets, trusted chat can use the other configured defaults;
history remains scoped to the requesting stored channel. Requests without
trusted chat provenance do not receive implicit native tools.

Optional: use this native-only named toolset if you want explicit allowlists
and a separate selection. Merge it into your existing `:ai :mcp` configuration;
keep existing servers/toolsets if you also want external tools:

```clojure
{:servers []
 :max-tool-calls 6
 :toolsets
 [{:name "yetibot_read"
   :kind "search"
   :servers []
   :native-tools ["help" "history" "history_count"]
   :users ["U0123456789"]
   :adapters ["slack"]
   :providers [{:name "grok" :models ["grok-4.7"]}]}]}
```

Replace the example identities with yours. An explicit nonempty `:native-tools`
override requires user, Slack/Discord adapter and provider/model allowlists.
Named toolset selection does not require a channel or thread allowlist; Wise
still requires its own allowed conversations.
Only `search` toolsets accept explicit native overrides. GitHub toolsets inherit
only `help`, `history` and `history_count`, never media, inference, delegation
or Wise. Explicit lists are not expanded automatically: add `"meme"`, `"gi"`,
`"clef"`, `"clairic"`, `"wise"`, `"ai"` or `"cf"` to allow them. Explicit inclusion
cannot bypass Wise's account/chat restrictions. Set `:native-tools []` on a
search toolset to disable its built-ins, or use `--no-tools` for one request.
`yetibot` is reserved as the built-in discovery source name.

The `grok` provider can still come from `xai.key`; sharing tools does not select
the generic AI default model.

```text
!ai servers
!ai tools
!ai tools yetibot
!ai tools yetibot/cf
!ai tools cf
!ai tools | tail 3 | xargs ai tools
!ai tools yetibot/cf | data show
!grok What Yetibot commands can help with this?
!grok Summarize the recent launch discussion here.
!ai --provider grok How many launch messages are here?
```

Discovery hides tools from unauthorized users or adapters. The configured
default toolset, or first named toolset if none is configured, applies when
`--toolset` is omitted. `--no-tools` disables these tools too.

### Bounded text delegation (`ai` and `cf`)

These are adapters in the existing shared native/MCP catalog, not separate
servers, command interpreters, or agent frameworks. `ai` supports actions
`providers`, `models`, and `ask`; `cf` supports `models` and `ask` with the
provider fixed to `cloudflare`. Optional `provider` selects only a configured
provider for `ai`. `ask` accepts `prompt`, optional `model`, and optional
`max_output_tokens` (integer 1–2048, default 2048). It cannot accept URLs,
headers, credentials, token paths, user identities, tools, or skills.

The current toolset's user/adapter and provider/model policy governs child
selection too. Include allowed child provider/models in that policy; selecting
an allowed parent model does not grant access to other providers. Trusted
adapter identity is rechecked before credential resolution and network work.
Each child is one existing host tool call, counts against the existing parent
budget, and calls the direct provider API once, **never** the agent/tool loop.
All child tool/native search lists are empty. Responses uses
`max_output_tokens`; generic Chat Completions uses `max_completion_tokens`.
The child sees only its supplied prompt, not automatic history, files, or bank
results. Parent models must not send unrelated private data in that prompt.

Static validation of the **whole batch** precedes any effects. Unknown
provider, denied provider/model, malformed or oversized arguments invalidate
the entire batch without executing any call. Cloudflare catalog/interface
eligibility is changing remote state: its read-only metadata GET happens
inside the host call, before the paid POST, not during pure preflight. Such
runtime failures may follow earlier successful calls. Transport/output failure
after an attempted paid request is an unknown outcome: no retry, later batch
calls (including Wise/CF) are skipped, and further parent tools are disabled.

Child results contain only bounded untrusted text plus provider/model, not
raw provider bodies, headers, credentials, tool calls or reasoning state.
Configured credentials are scanned as raw strings before outbound work; JSON
strings are decoded and sensitive fields/credentials redacted before output
truncation. Model listing pages the full catalog (default/max 20 entries) with
`after`/`limit` and `next_after`, preserving the same whitelisted metadata.
Pages stay below 12,000 UTF-8 JSON bytes, so a page may contain fewer than the
requested number of entries. The cursor follows the last returned record;
continue until `has_more` is false. `cf models` at the command line returns the
full bounded catalog.

### Memes and Grok images

`!ai tools` and `!ai tools <server>` list one tool per line, in catalog order:
`` `server/name` — short description ``. Only the display ID is wrapped in
backticks; structured `:id` and `:name` values remain raw. Descriptions are
whitespace-collapsed and limited to 120 characters; command punctuation is
made literal for `xargs`. Wrapped IDs and complete summary rows are accepted
as selectors without evaluating their backticks as command substitutions.
IDs stay unchanged, and no header rows interrupt collection pipelines.
Each row stays paired with its projected metadata through collection commands.
`!ai tools <server/name>` shows focused help: description, input names/types,
required fields, enum choices and conditional required alternatives. A unique
bare tool name works too; ambiguous names require the qualified ID. Exact
server names retain their existing source-filter meaning.

A complete summary row can be passed back to the command, so this works:
`!ai tools | tail 3 | xargs ai tools`. Each lookup discovers only its source
when the ID identifies one. No tool callbacks or model inference execute.

Pipe a tool lookup to `data show` for that tool's full projected metadata only.
The default/server lists still retain their aggregate structured data for
advanced inspection: `:tools` entries contain `:id`, `:server`, `:name`,
`:description`, `:inputSchema`, and advertised public `:title`/`:annotations`
(standard annotation hints only).
Native metadata comes from the existing definitions, not from running help,
history, media, inference, or Wise tools. The same visibility and allowlist
rules apply to both outputs, including Wise's chat restrictions.

The data also includes `:instructions`: `:runner` is the shared tool-runner
instruction text. `:servers` records whether each selected MCP server supplied
initialize instructions; any supplied text is labelled
`UNTRUSTED/NOT APPLIED TO MODEL`. Server instructions are inspection data only:
they are **never** added to model prompts. Descriptions/instruction text is
bounded to 20,000 characters; schema/annotation data to 32,768 serialized
characters, 4,096 nodes and depth 32. `:truncated` and `:omitted` make limits
explicit; an omitted schema is `nil`, not a usable replacement schema.
Configured credentials (including JSON-escaped copies) are redacted before
text truncation. Connection settings, client/session internals, callbacks and
private metadata are not included.

`:skills` in aggregate data honestly reports
`{:supported false :items []}`: listing does not
invent per-tool skills or fetch/apply MCP prompts or resources. Each listing
uses one discovery/session pass and closes sessions normally. Listing does not
call tool host functions, read bank data, or make model/inference requests;
connecting to configured MCP servers for initialize and discovery is still
required for remote metadata.

`meme` uses existing `imgflip.username` and `imgflip.password` settings. `gi`
uses the existing `xai.key`, independently of the selected chat provider's key.
Configure credentials normally and restart Yetibot; no MCP server is needed.
Use `!ai tools yetibot` to see which tools are available. An explicit native
allowlist remains authoritative even when both services are configured.

Try `!grok Make a Drake meme about deploying on Friday.` or
`!ai Generate a picture of Yetibot commuting by helicopter.` The model chooses
the tool calls and captions/prompts; you do not write schemas. A successful
call returns an image URL for the final answer, not binary image data.

- `meme` searches with a literal query, returning up to 20 template IDs/names.
  It checks the popular-template catalog, then at most one search page for an
  unmatched query. Generation uses a template ID and top/bottom captions, each
  at most 1000 characters; one caption may be empty. Captions are data, not
  Yetibot commands. Existing Discord mention resolution is retained.
- `gi` accepts a prompt of at most 2000 characters. It reuses image generation,
  storage and current-message attachment/inline-image editing behavior from
  the normal `gi` command. Arguments cannot choose another chat source,
  provider, executable or arbitrary command.
- These tools use shared service credentials. Imgflip captions/images are
  publicly hosted; Grok prompts and input images go to xAI, and results are
  served by Yetibot. Do not include private channel information unless it is
  appropriate to send to those services and publish in an image.
- Calls share the existing tool budget (default six); this is not a monetary
  spending cap or a new per-user image quota. An uncertain generation failure
  stops further calls in its batch and disables more tool use for that request.
  The host does not automatically retry generation.
- Known configured credentials are rejected in media arguments and redacted
  from results. Error details and the GI utility's debug stdout are not sent
  to the model or leaked through ordinary prompt logging. This is not general
  secret detection. Existing identity/model restrictions still apply.

### History boundary and limits

**Tool-enabled AI requests can send stored channel messages to the selected
provider/model**, including commands and bot responses, without extra native
configuration. Tell participants and choose user/channel/provider restrictions
accordingly. Use `--no-tools` or a toolset's empty native override to opt out.
Configured credentials are redacted from results, but this is not
general-purpose secret detection.

- Scope is fixed by the trusted incoming adapter event: its connection and
  stored room key. Arguments cannot select a user, room, connection or SQL.
  Untrusted HTTP metadata and missing actors cannot enable native tools.
  Disallowed named-toolset identities/models fail before tools or model calls.
  No arbitrary command execution is exposed.
- Slack history uses stored channel names, not raw IDs, and is channel-wide,
  including threads. Renamed/reused room names are not remapped to historical
  IDs; the existing schema cannot prove historical membership or distinguish
  name reuse. Enable only where that storage limitation is acceptable.
- Discord uses the incoming channel/thread ID as stored. Creating a Grok reply
  thread does not retarget the read to that new thread or its parent.
- `history` accepts `query` (1–200 characters), `limit` (integer 1–20, default
  10), and `before_id` (positive stored row ID). Results are chronological
  within each newest-first page, ordered by timestamp then ID. For older
  messages pass `next_before` as `before_id` when `has_more` is true. The cursor
  must itself belong to the current stored scope; other-scope IDs return empty.
- SQL fetches at most `limit + 1` rows. Bodies are cut to 1000 database
  characters and names to 100 before retrieval. JSON escaping can shorten a
  page further; the cursor follows the messages actually returned. `truncated`
  and per-field flags make shortening explicit. History pages are bounded to
  12000 serialized characters before shared redaction; the final host envelope
  is at most 20000. An oversized native result becomes a structured error.
- `history_count` accepts only `query`. It is an exact count, not limited to the
  current page. Regex and SQL wildcard syntax are not interpreted. Searches and
  counts can still scan many rows, so queries have a 2-second PostgreSQL
  statement/JDBC timeout in read-only transactions, a 2-second connection login
  timeout and 3-second socket timeout. Failures return a sanitized tool error,
  not a partial count. History-disabled configuration also disables these reads.
- `help` lists up to 20 topics per page (default 20), with `after`/`next_after`
  cursors. With `topic`, omit `after` and `limit`. Long docs are shortened
  with `truncated: true`; its serialized payload is bounded to 10000 characters.
- Retrieved text is marked untrusted, and the shared instructions explicitly
  prohibit following instructions found in tool results. This is not a promise
  that a model can never be prompt-injected; keep the allowlist narrow.

The native reader uses the existing history schema and needs no new core API or
core dependency bump. Human `history` SQL correctness fixes are a separate core
change and require a core release/dependency update to reach the deployed bot.
The default built-ins need no MCP configuration changes after deployment.

## MCP tools and web search

`ai` can use tools from named MCP servers. This is a general connection, not a
built-in list of search providers: adding another server or allowing another tool
only needs configuration. The model decides when to call the available tools.
For current facts, it is instructed to search and cite source URLs from the results.

MCP is optional. Without it, existing chat requests are unchanged. When enabled,
the selected model **and** its endpoint must support function calling: Chat
Completions uses `tools`, `tool_choice`, `tool_calls` and tool-result messages;
xAI Responses uses `function_call` and `function_call_output` items. Use
`--no-tools` for a model that does not support this, or to skip all tools for
one request.

Add `:mcp` alongside `:providers` inside your `:ai` configuration. A server
needs only its name and connection; `:tools` is optional:

```clojure
{:mcp {:servers [{:name "devth" :url "https://devth.com/mcp"}]}}
```

Yetibot calls `tools/list` on each request and exposes the eligible tools with
their advertised descriptions and input schemas. For devth this discovers
`list_content`, `search_content`, `read_content` and `get_hit_count`. No
handwritten list, schema or separate devth toolset is needed. Use
`!ai tools devth` to inspect
it, then `!ai Search Trevor's blog for Yetibot and cite the posts.`

If you already use named toolsets, also append `{:name "devth"}` to the
`:servers` of your existing default `"search"` toolset. Keep its current
Cloudflare/Exa references and other toolsets unchanged. This name-only reference
inherits the server's tool policy; it does not require `--toolset devth`.
Unreferenced servers remain disconnected, so adding a public server never
implicitly enables a GitHub connection.

For reviewed subsets, keep an explicit `:tools` allowlist, as in these examples:

```clojure
{:mcp
 {:max-tool-calls 6
  :timeout-ms 30000
  :servers
  [{:name "exa"
    :url "https://mcp.exa.ai/mcp"
    :tools ["web_search_exa"]}
   {:name "tavily"
    :url "https://mcp.tavily.com/mcp/"
    :headers {"Authorization" "Bearer YOUR_TAVILY_API_KEY"}
    :tools ["tavily-search"]}
   {:name "searxng"
    :url "http://localhost:8080/mcp"
    :tools ["searxng_web_search"]}]}}
```

For remote servers you can also use environment configuration:

```sh
export YETIBOT_AI_MCP_SERVERS_0_NAME=tavily
export YETIBOT_AI_MCP_SERVERS_0_URL=https://mcp.tavily.com/mcp/
export YETIBOT_AI_MCP_SERVERS_0_HEADERS_AUTHORIZATION='Bearer YOUR_TAVILY_API_KEY'
export YETIBOT_AI_MCP_SERVERS_0_TOOLS_0=tavily-search
export YETIBOT_AI_MCP_MAX_TOOL_CALLS=6
```

Configure only the servers you want. Exa's hosted server offers limited anonymous
access. Tavily's example uses an API key in a header. The SearXNG URL above must
point to an independently running **MCP adapter**, such as
[mcp-searxng](https://github.com/ihor-sokoliuk/mcp-searxng), not the SearXNG search
page itself. Its HTTP service must be configured separately and reachable from
Yetibot. See the [Exa](https://github.com/exa-labs/exa-mcp-server) and
[Tavily](https://github.com/tavily-ai/tavily-mcp) server docs for their current tools
and authentication requirements. Exact tool names matter; Tavily uses a hyphen.

Local MCP processes are also supported. Use `:command`, `:args` and `:env`
**instead of** `:url` and `:headers` on a server:

```clojure
{:mcp
 {:servers
  [{:name "exa"
    :command "npx"
    :args ["-y" "exa-mcp-server"]
    :env {"EXA_API_KEY" "YOUR_EXA_API_KEY"}
    :tools ["web_search_exa"]}
   {:name "searxng"
    :command "npx"
    :args ["-y" "mcp-searxng"]
    :env {"SEARXNG_URL" "http://your-searxng-instance:8080"}
    :tools ["searxng_web_search"]}]}}
```

These examples require Node.js/npm and the server's runtime requirements on the
machine running Yetibot. For production, pin reviewed package versions or use
preinstalled server executables rather than downloading a package on each prompt.
Only run trusted servers. Executables and arguments come exclusively from the
administrator's configuration, never from the model. Local processes receive a
small standard environment (such as `PATH` and `HOME`) plus explicit `:env`
entries, not Yetibot's full environment or its other API keys.

```text
!ai servers
!ai tools
!ai tools exa
!ai What changed in Clojure this week? Include sources.
!ai --provider local --model my-tool-capable-model Find today's news.
!ai --no-tools Explain map and reduce without searching.
```

### Access and limits

- Every server needs a unique `:name`. Omit `:tools` to discover its eligible
  catalog, or provide a nonempty list to restrict it. Existing explicit lists
  still exclude every unlisted tool. `nil` and empty lists are invalid settings.
  Calls must belong to the request's discovered, policy-filtered catalog; an
  invented tool name is rejected before transport. Tools with the same name on
  different servers remain separate.
- Discovery trusts the configured server's current and future eligible tools;
  it is not a general read-only guarantee. Only add trusted servers. Use
  explicit allowlists and least-privilege credentials when a catalog includes
  operations you do not want exposed. Tool annotations do not grant GitHub
  permissions.
- Legacy search tools are available to everyone who can run `ai`. Named toolsets
  can restrict access by stable user IDs and adapters. GitHub tools require
  a GitHub toolset with explicit access policy. Discovery in GitHub toolsets
  exposes only the host's supported read operations, never unknown operations or
  raw GitHub mutations. Reserved GitHub names remain excluded from search.
  Connections use service credentials, not the chat user's GitHub identity.
  Use least-privilege keys and command access controls too.
- Supports local stdio and remote **Streamable HTTP**, including JSON and SSE
  response bodies. Old standalone `/sse` transports, interactive OAuth sign-in,
  MCP prompts/resources and server-requested model sampling are not enabled.
- Remote URLs must use HTTP(S), without embedded credentials, query strings or
  fragments. Put authentication in `:headers` or a local server's `:env` instead.
  Redirects are not followed, so use the exact endpoint, including any required
  trailing slash. Prefer HTTPS for remote servers.
- Each prompt opens fresh sessions and closes them when finished, including on
  failures. It discovers and filters tools before asking the model. If a server
  is unavailable or a configured tool is missing, the request fails clearly;
  it does not silently answer as if a search succeeded. `--no-tools` skips all
  connections.
- `:max-tool-calls` defaults to 6 and accepts 1–20. The limit includes every call
  across all servers and model turns. At the limit the model gets one final turn
  with tool use disabled. Repeated calls with identical arguments are rejected,
  even if the model changes their call IDs. Failed or uncertain calls are not
  automatically retried. If a call has an unknown outcome, remaining calls in its
  batch are skipped and the model can only give a final answer, not run more tools.
- `:timeout-ms` defaults to 30000 and accepts 100–120000, per MCP operation.
  At most 64 tools are offered per request. Arguments are limited to 16384
  characters, and each returned tool result is shortened to 20000 characters.
  Binary image/audio results are not sent to the text model.
- Search queries and other tool arguments go to the chosen MCP server and its
  services. Tool descriptions and results go to the AI provider. Configured keys
  are removed from returned text, and tool results are marked as untrusted data
  in the model's instructions. No connection credentials are deliberately sent
  to the AI provider.

### X (Twitter) search: read-only hosted MCP

[X's official MCP server](https://docs.x.com/tools/mcp) is available at
`https://api.x.com/mcp`. Its direct, app-only Bearer-token route uses Streamable
HTTP and custom headers, which Yetibot's MCP client supports. It does not
require hosting a server, installing `xurl`, or performing interactive OAuth
in Yetibot.

**Read-only does not mean anonymous or free.** Before configuring it:

1. Create an app in the [X Developer Console](https://console.x.com) and obtain
   its **App-only Bearer token** from its keys/tokens settings.
2. Ensure the app has access and credits for the search endpoints you intend to
   use. X API [billing](https://docs.x.com/x-api/getting-started/pricing) and
   rate limits still apply; a Yetibot tool-call limit is not a spending cap.
3. Discover the hosted server's tools with that token and select only the public
   post-search tools you need. Inspect their descriptions and input schemas;
   never copy the complete tool list, which can include write operations.

An **xAI console API key is not an X Developer app token** and cannot
authenticate this connection. Grok's
[`x_search`](https://docs.x.ai/developers/tools/x-search) is a separate, billed
xAI service. The existing `grok` command already enables that tool using
`:yetibot :xai :key`; this MCP recipe instead lets a tool-capable
`ai` provider, including a local model, search through the X API directly.

For discovery, use an MCP client that supports Streamable HTTP and custom
headers. For example, with Node.js **22.19.0 or newer** and
[MCP Inspector's CLI][inspector-cli], set `X_APP_BEARER_TOKEN` securely in your
shell, then run:

[inspector-cli]:
  https://github.com/modelcontextprotocol/inspector/tree/main/clients/cli

```sh
npx @modelcontextprotocol/inspector@2.12.0 --cli https://api.x.com/mcp \
  --transport http --stored-auth-only \
  --header "Authorization: Bearer ${X_APP_BEARER_TOKEN}" \
  --method tools/list
```

This lists tools without invoking them. Run it on a trusted machine: the
expanded header is a process argument; do not enable shell tracing or share
credentials or diagnostic output. `--stored-auth-only` prevents an interactive
OAuth fallback if the supplied token fails. Follow any `tools/list` pagination
before concluding a tool is absent.

The official hosted-server documentation does not specify exact post-search tool
names. **The following is a template, not a ready-to-run allowlist:** replace
`POST_SEARCH_TOOL_FROM_TOOLS_LIST` with an exact, reviewed name from your
authenticated discovery, and replace the token placeholder. Names from a local
X MCP implementation or X's OpenAPI spec are not proof of hosted tool names.

Merge this connection into `:yetibot :ai :mcp :servers`, preserving any existing
servers and provider settings:

```clojure
{:name "x"
 :url "https://api.x.com/mcp"
 :headers {"Authorization" "Bearer YOUR_X_APP_ONLY_BEARER_TOKEN"}
 :tools ["POST_SEARCH_TOOL_FROM_TOOLS_LIST"]}
```

Prefer injecting the header through Yetibot's environment configuration rather
than committing a token. For example, if X is server index **1** after an
existing Exa connection, the equivalent connection settings are:

```sh
export YETIBOT_AI_MCP_SERVERS_1_NAME=x
export YETIBOT_AI_MCP_SERVERS_1_URL=https://api.x.com/mcp
export YETIBOT_AI_MCP_SERVERS_1_HEADERS_AUTHORIZATION=\
"Bearer ${X_APP_BEARER_TOKEN}"
export YETIBOT_AI_MCP_SERVERS_1_TOOLS_0=POST_SEARCH_TOOL_FROM_TOOLS_LIST
```

Choose an unused server index (use `0` if X is your only server), replace the
allowlist placeholder, and restart Yetibot. EDN does not interpolate environment
variables. Keep only the reviewed search tools in `:tools`; the app-only token
provides no user context and cannot act as an X user.

Verify discovery before making a potentially billable search:

```text
!ai servers
!ai tools x
!ai Search X for recent public posts about Clojure and cite the post URLs.
```

`ai tools x` lists only your configured allowlist, not all upstream tools; a
missing or incorrect name fails discovery. A successful listing does not prove
that your app can call a search endpoint. Complete the search round trip to
verify endpoint access and the selected model's tool calling. This recipe has
not been authenticated end-to-end against the hosted service.

Flat configuration connects every configured server for a tool-enabled prompt,
so an unavailable X connection can also block ordinary web search. Use
[named toolsets](#named-toolsets-github-and-search) to select X separately from
Exa/Tavily, and keep your existing web-search toolset as the default if desired.
If you already use named toolsets, adding a server alone does not expose it: add
an entry referencing server `x` and its reviewed tools to a `:kind "search"`
toolset, then select that toolset with `!ai --toolset <name> <prompt>`.
Without access restrictions, configured search is available to everyone who can
run `ai`, using the shared app's credentials and API budget. Use a dedicated app
and restrict chat access or the toolset's users/conversations as appropriate.

## Named toolsets: GitHub and search

Toolsets separate connection settings from what a request may use. Only selected
servers connect, so an unavailable GitHub server does not break search. Existing
flat search configuration still works without toolsets; migrate any flat GitHub
configuration to the access-controlled recipes below.

Add the following under `:yetibot :ai`, alongside your existing provider settings.
Replace the model, IDs, executable paths and token placeholders before use:

```clojure
{:providers
 [{:name "local"
   :url "http://localhost:1234/v1"
   :model "YOUR_TOOL_CAPABLE_MODEL"
   :tool-calling true
   :tool-choice "standard"}]
 :default-provider "local"
 :mcp
 {:default-toolset "search"
  :max-tool-calls 6
  :timeout-ms 30000
  :servers
  [{:name "web"
    :url "https://mcp.exa.ai/mcp"
    :tools ["web_search_exa"]}
   {:name "github-read"
    :command "/usr/local/bin/github-mcp-server"
    :args ["stdio" "--read-only" "--tools" "get_file_contents,get_commit,list_branches,issue_read,pull_request_read,search_code"]
    :env {"GITHUB_PERSONAL_ACCESS_TOKEN" "READ_TOKEN_PLACEHOLDER"}
    :github-api-token "READ_TOKEN_PLACEHOLDER"
    :tools ["get_file_contents" "get_commit" "list_branches"
            "issue_read" "pull_request_read" "search_code"]}
   {:name "github-write"
    :command "/usr/local/bin/github-mcp-server"
    :args ["stdio" "--tools" "create_branch,push_files,create_pull_request"]
    :env {"GITHUB_PERSONAL_ACCESS_TOKEN" "WRITE_TOKEN_PLACEHOLDER"}
    :tools ["create_branch" "push_files" "create_pull_request"]}]
  :toolsets
  [{:name "search"
    :kind "search"
    :servers [{:name "web"}]}
   {:name "github-read"
    :kind "github-read"
    :users ["SLACK_USER_ID"]
    :adapters ["slack"]
    :repositories ["your-org/your-repo"]
    :providers [{:name "local" :models ["YOUR_TOOL_CAPABLE_MODEL"]}]
    :servers [{:name "github-read"}]}
   {:name "github-pr"
    :kind "github-pr"
    :users ["SLACK_USER_ID"]
    :adapters ["slack"]
    :repositories ["your-org/your-repo"]
    :providers [{:name "local" :models ["YOUR_TOOL_CAPABLE_MODEL"]}]
    :base-branches ["main"]
    :allowed-paths ["src/" "test/" "doc/"]
    :denied-paths ["src/credentials/"]
    :branch-prefix "yetibot/ai-"
    :write-server "github-write"
    :servers [{:name "github-read"}]}]}}
```

The `github-pr` toolset must include `get_commit` and `get_file_contents` for
host-side snapshots. Its `get_commit` server also requires an explicit
`:github-api-token` with Contents read permission. GitHub MCP v2.0.3 returns
`MinimalCommit` without parents; ancestry is therefore checked with two host-owned,
authenticated GETs to `https://api.github.com/repos/<owner>/<repo>/git/commits/<sha>`:
a preflight read before any mutation and a verification read after pushing. This
is the sole REST exception; all writes remain MCP-only. Credentials are never
inferred from MCP headers or environment, redirects/retries are disabled, and
unsupported/error responses stop the workflow. Only GitHub.com is supported.
A `search` toolset cannot expose known GitHub tools; use a
GitHub kind so repository and identity checks cannot be bypassed accidentally.
A name-only server reference inherits its connection's `:tools` restriction, or
live discovery when that restriction is omitted. A reference may supply its own
`:tools` to narrow access, never widen a connection's explicit allowlist. Every
exposed tool must appear in live `tools/list`. Missing required snapshot or
write capabilities fail closed; discovery does not change identity, repository,
path, review, ancestry or approval checks. Writer-only connections stay hidden
and connect for host-controlled execution, with the fixed write-tool subset.
Only trusted administrators may configure tools: an arbitrary third-party tool
named like a search function is not automatically safe or read-only.

Configuration uses the existing environment mapping, for example:

```sh
export YETIBOT_AI_PROVIDERS_0_TOOL_CALLING=true
export YETIBOT_AI_PROVIDERS_0_TOOL_CHOICE=standard
export YETIBOT_AI_MCP_DEFAULT_TOOLSET=search
export YETIBOT_AI_MCP_TOOLSETS_0_NAME=search
export YETIBOT_AI_MCP_TOOLSETS_0_KIND=search
export YETIBOT_AI_MCP_TOOLSETS_0_SERVERS_0_NAME=web
```

To configure tokens through the environment, the nested server keys are
`YETIBOT_AI_MCP_SERVERS_1_ENV_GITHUB_PERSONAL_ACCESS_TOKEN` and
`YETIBOT_AI_MCP_SERVERS_2_ENV_GITHUB_PERSONAL_ACCESS_TOKEN` for the example above.
The separate REST verification token is
`YETIBOT_AI_MCP_SERVERS_1_GITHUB_API_TOKEN`; it may use the same read-only PAT,
but must be explicitly configured, including for hosted MCP. Set tokens privately
in your service configuration. Flat EDN environment keys (including lowercase
keywords such as `:http_proxy`) retain their exact spelling; nested exploded
environment paths are reconstructed with uppercase underscore-separated names.
EDN does **not** interpolate
`${ENV_VAR}` or resolve VS Code `${input:...}` variables. Do not commit secrets.
As with other settings, configuration changes and token rotation require restart.

### Identity and repository policy

- `:users` contains platform user IDs, never display names. Tools are available
  to authorized users in any channel or thread on an allowed adapter. There is
  no conversation allowlist; old `:conversations` entries are ignored and can
  be removed without replacement.
- `:adapters` uses `"slack"` or `"discord"`. Missing IDs, edited/bot messages,
  reaction-triggered requests and unsupported adapters cannot authorize writes.
  Approval also binds the current Slack thread or Discord channel/thread.
  Identity additionally requires the host adapter entry point and a matching
  adapter-supplied user ID. Caller-supplied `/api` chat-source metadata cannot
  establish identity, even when it copies an allowed user’s raw event.
- Authorized users can request private repository reads in any channel or
  thread. Choose private output channels for private repositories. Proposal
  review and approval still require the original user, channel and thread.
  Shared GitHub tokens do not impersonate the requesting chat user. History
  tools still read only the request's stored channel or thread.
- GitHub search accepts plain terms, not query operators. The host adds the
  repository qualifiers. Use structured owner/repo reads for precise inspection.
- `:providers` lists exact provider names and model IDs. It is mandatory for
  `github-pr`, including model overrides. Pin it to your intended local endpoint;
  a name such as `local` is not itself a network security boundary. There is no
  automatic fallback to a hosted model.
- Use repository-limited fine-grained GitHub tokens. The reader needs Contents,
  Issues and Pull requests read permissions only as required by enabled tools.
  The writer needs Contents and Pull requests write permissions for selected
  repositories, not workflow or administration privileges.

For hosted GitHub MCP, replace each connection's command/args/env with
`:url "https://api.githubcopilot.com/mcp/"` and a private
`:headers {"Authorization" "Bearer YOUR_TOKEN"}`. Set `X-MCP-Readonly` to `"true"`
on the read connection and `X-MCP-Tools` to its exact comma-separated allowlist.
For the writer, set `X-MCP-Tools` to `create_branch,push_files,create_pull_request`
and do not enable read-only mode. Pin a reviewed local server version when
reproducible tool/result schemas are required. Interactive OAuth is not supported.

### Clef intent routing and automatic draft PRs (opt-in)

By default, GitHub changes still require chat proposal review/approval. To replace
that interaction with **automatic draft PRs for explicit change requests**, add
this sibling of `:providers` and `:mcp` under `:yetibot :ai`:

```clojure
:intent-router
{:enabled false                  ; enable only after reviewing the write policy
 :account-id "YOUR_32_HEX_CLOUDFLARE_ACCOUNT_ID"
 :key-file "/root/yetibot/config/.clef-api-token"
 :github-toolset "github-pr"
 :min-probability 0.95}
```

Use a Cloudflare Workers AI token authorized for inference in that account. Keep
the raw replacement token in the server-side file (mode 0600), not in Git, command
arguments, chat or example payloads. A token pasted into chat must be revoked.
The client reads the file on each request, so token-file rotation does not require
a restart; changing router settings does. The token-verification endpoint
`/user/tokens/verify` does not perform classification. Inference uses the fixed
HTTPS endpoint `/accounts/<account-id>/ai/run/@cf/cloudflare/clef`;
redirects and automatic HTTP retries are disabled.

Only a trusted, standalone `!ai` message from a user/channel/provider/model
already authorized by the named GitHub PR toolset is eligible:

| Request | Behavior |
| --- | --- |
| `!ai In your-org/your-repo, fix the typo in src/example.clj` | Clef classifies; the existing generation model reads/stages, then the host pushes verified bytes and opens a draft PR |
| `!ai Explain how this parser works` | Normal answer using the existing default tools; no automatic PR |
| Unclear change intent, no approved `owner/repo`, or multiple approved repositories | Clarification, no writes |
| `!ai --toolset github-pr ...` | Existing manual proposal/review/approve workflow |
| `!ai --no-tools ...` | Bypass routing and tools |
| `!grok ...`, pipelines, aliases, observers, generated commands or copied `/api` identity | Never start automatic GitHub writes |

Clef receives **only the latest request text**, not retrieved repository files,
thread history or connection settings. Code pasted into that request is therefore
sent to Cloudflare too. Requests containing recognized/configured credentials
are rejected; known credentials are also redacted from generation context and
rejected in staged files. Clef does not generate code or execute tools. Generation
uses the selected existing provider, which must match the toolset's exact
provider/model policy; pin that policy to the intended local endpoint.

Writes require both the chosen `code_change` probability and the separate
`explicit_change.noul` score to meet `:min-probability` (default 0.95, minimum
0.9). Clef's `confidence` is a different distribution-derived score, not the
option probability. Valid read-only `answer` decisions do not need a write
threshold. Malformed responses, outages and low-confidence change decisions
never fall through into mutations. **Scores are routing hints, not authorization
or a guarantee against misclassification.**

There are no user-facing proposal pages or `!ai approve` in automatic mode.
An internal immutable manifest still captures exact files, source identities,
policy and request identity. All existing repository/base/path/size/secret and
post-push verification checks remain. No additional model turn edits the manifest
during execution. Only branch creation, file push and **draft** PR creation are
available; never automatic merging. The PR body discloses automatic generation
and that changes were not locally tested.

**Enabling this mode authorizes pre-review pushes.** Draft PRs can trigger CI;
GitHub review happens after the push, not before it. Use trusted users/private
channels and small approved repository scopes. No tests or arbitrary shell
commands are run by this MCP-only workflow.

Duplicate deliveries share bounded, process-local receipts (128 code-work entries
plus up to 128 read-only decisions, with a 24-hour completed-receipt TTL). Older
read-only decisions may be evicted; questions do not consume code-work capacity. Automatic branch names derive from the platform message
identity. After a restart, an existing branch causes branch creation to stop
rather than repush/reopen; receipts themselves do not survive restart. This is
not a durable exactly-once job queue. Do not delete an uncertain branch and
blindly replay the request. Failures/unknown mutation outcomes are not retried
automatically; inspect any reported branch/commit first.

#### Clef request and response examples

- [Request payload](examples/clef-intent-request.json)
- [Sanitized response sample](examples/clef-intent-response.json): captured from a
  live classifier-only qualification; scores vary, and this did not perform any
  repository writes.
- [Official model/API schema](https://developers.cloudflare.com/workers-ai/models/clef/)

To reproduce without placing the token in process arguments or shell history:

```sh
export CLOUDFLARE_ACCOUNT_ID=YOUR_ACCOUNT_ID
{
  printf 'Authorization: Bearer '
  tr -d '\r\n' < "$HOME/yetibot/config/.clef-api-token"
  printf '\n'
} | curl --silent --show-error --fail-with-body \
  "https://api.cloudflare.com/client/v4/accounts/${CLOUDFLARE_ACCOUNT_ID}/ai/run/@cf/cloudflare/clef" \
  --header @- --header 'Content-Type: application/json' \
  --data-binary @doc/examples/clef-intent-request.json
```

### Local endpoint compatibility

`:tool-calling false` (or the environment value `false`) rejects tool-enabled
requests before discovery. Plain `--no-tools` requests still work.
`:tool-choice "omit"` omits `tool_choice` for endpoints needing that compatibility
mode; the default `"standard"` sends `auto`/`none`. Host-side budgets, call-ID
validation and approval restrictions remain enforced in both modes.

A working `/models` response does not prove tool support. Qualify the exact
server version/model/template with a search round trip first. Ollama, vLLM,
llama.cpp and LM Studio vary by version and model; vLLM may require its automatic
tool-choice flag and model-specific parser/template. The bot only executes real
OpenAI `tool_calls`, never JSON/XML/prose that merely looks like a tool call.

### Usage and manual guarded PR workflow

```text
!ai toolsets
!ai servers
!ai tools github-read
!ai --provider local --toolset search Find this week's Clojure news with sources.
!ai --provider local --toolset github-read Explain your-org/your-repo's PR #123.
!ai --provider local --toolset github-pr In your-org/your-repo, fix the typo in src/example.clj and propose a draft PR.
!ai proposal <id>
!ai proposal <id> 1
!ai proposal <id> 2
!ai approve <id>
!ai cancel <id>
```

`--toolset` and `--no-tools` are mutually exclusive. Discovery commands hide
inaccessible toolsets and writer-only connections. The configured default toolset
is used when the flag is omitted; otherwise the first named toolset is the default.

The following review steps describe manual mode (the default, or an explicit
`--toolset` override), not the opt-in automatic route above.
The model has GitHub read tools and a host-side `stage_changes` function, **not**
raw GitHub mutations. Staging reads source at an identified commit and produces
an immutable proposal. No branch, commit or PR is created during generation.
The preview lists its repository, base, hash and expiration. Review every
numbered page, including the exact PR body and complete file diff; approval is
rejected until all pages have been requested by the original user. Review,
approval and cancellation must be standalone commands directly sent by that user;
pipes, subexpressions, aliases, generated `cmd` commands and observers cannot
supply this consent. Each added or
removed diff line is a JSON-escaped string including its line terminator: `\r\n`,
`\n`, `\r`, tabs and non-ASCII characters remain distinguishable. Concatenating
those decoded strings reconstructs the original/proposed text exactly, including
trailing blank lines or a missing final newline.

Approval never calls the model. It rechecks policy/provider identity and base
freshness, creates an unpredictable bot branch, verifies its base SHA, pushes
exactly the approved bytes, verifies commit ancestry/content and opens a
**draft** PR. The PR body discloses that changes were not locally tested; that
footer is visible in the review pages before approval.

Limits and failure behavior:

- At most five text-file additions/updates and 8 KiB of new UTF-8 content; model
  tool arguments remain limited to 16,384 characters. Existing files must be
  complete UTF-8 text no larger than 20,000 bytes. Ambiguous, truncated or
  unsupported GitHub result shapes fail closed.
- No deletions, binary files, symlinks, submodules, traversal, `.git`, `.env`,
  secret/key files or workflow-file edits. Allowed/denied path entries are literal
  prefixes, not globs. New files require an existing, complete parent directory
  listing; directories with 1,000 or more entries are rejected conservatively.
- Proposals containing configured credentials, including source text matching
  a configured secret, are rejected rather than redacted into different code.
  Avoid putting public feature-flag values into MCP `:env` where possible;
  the existing conservative redactor treats explicitly supplied env values as
  sensitive. Use server command arguments for public options such as read-only.
  Valid GitHub `X-MCP-Readonly` Boolean values and an `X-MCP-Tools` value matching
  the server's declared tool list are public transport controls, not credentials;
  authentication and unknown/custom header values remain protected.
- Approval expires after ten minutes. State is process-local, bounded to 128
  active entries, periodically cleaned, and invalidated on restart. Run one
  executor instance; this is not distributed or durable job coordination.
- Each proposal is one-shot, including failed/uncertain executions. A timeout
  stops further mutations without retry. Inspect the reported branch, commit,
  step and GitHub state before generating another proposal. There is no automatic
  force push, rollback, merge or cleanup of partially created branches.
- The normal model budget counts model-selected calls (staging counts as one).
  Host staging additionally performs at most eleven source reads. Approved
  execution is a fixed sequence of at most sixteen MCP tool operations and two
  ancestry-only REST reads, never an unbounded model loop. REST connection/read
  timeouts use the configured MCP operation timeout.
- Draft PRs may trigger CI. Ensure repository CI is safe for generated code
  before enabling writes; denying workflow edits alone does not make builds safe.
  Repository review and branch protections remain mandatory. This workflow does
  not clone repositories, run tests or replace the existing Gemini `code` command.

Enable `search` and `github-read` first. Add `github-pr` only after qualifying the
local model and testing against a disposable repository. To disable writes,
remove the PR toolset/writer connection and restart (pending proposals disappear).
Watch sanitized `ai-proposal` state-transition logs for failures/unknown outcomes;
source text, prompts and credentials are not included in these audit events.
Once a request accesses private GitHub tools or proposal output, request-scoped
logging filters suppress other log events before ordinary logging middleware and
appenders see them, including interpreter result, pipeline, history and chat
formatting logs at trace/debug levels. Sanitized `ai-proposal` audit events remain
available. Other requests keep their existing logging configuration.

## Thread conversations

Outside threads, every prompt stands alone. No channel conversation or previous
AI requests are stored or sent as context.

In a **Slack thread** or **Discord thread**, `ai` fetches all prior text messages
in that thread, including its starting message and bot replies. It follows all
history pages, orders messages oldest first and appends the current prompt once.
Only Yetibot's own replies get the `assistant` role; other participants are
labelled as users. Messages after the triggering message are excluded.

This sends the thread's text to the **selected provider**, even if earlier
answers came from a different provider. Files and image contents are not read.
No other channels or threads are included. Replies stay in the current thread
through the existing Slack/Discord adapter behavior.

The bot needs access to the thread and its history. Slack needs the appropriate
conversation history scopes (for example `channels:history` or `groups:history`)
and a token allowed to call `conversations.replies` for that conversation type;
some conversations require a user token rather than a bot token. Discord needs
View Channel and Read Message History, plus access to private threads when used.
If history cannot be read completely, the command reports an error instead of
sending a partial conversation or silently dropping context.

History is not silently shortened. Very long threads may exceed the selected
model's context limit; the provider's HTTP error is reported. Provider errors do
not expose API keys, request headers or provider response bodies.
