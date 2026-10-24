# Clef, Clairic and Cloudflare discovery validation

## Scope

Application-only changes on `feat/clef-clairic-commands`:

- Standalone `clef` and `clairic` command hooks for piped text/structured state,
  direct text with typed schemas, attachments and explicit image URLs.
- Existing intent-router account/token-file settings are reused even when its
  enabled flag is false. Optional gateway IDs apply to the new inference calls.
- Fixed-host Workers AI inference; readable answers plus complete structured
  pipeline data. No automatic-PR classifier, tool invocation or write authority.
- Cloudflare account REST providers discover the Workers AI catalog through
  `/ai/models/search`, with explicit third-party Gateway coverage limitations.
  Existing OpenAI-style providers still use `/models`.
- Provider-specific gateway headers and the implicit `default` Gateway for
  Workers AI chat IDs. No arbitrary provider-header configuration.

## Tests-first evidence

Initial command checks failed before implementation: **13 failed / 1 passed**.
Catalog/routing/response validation checks then failed before wiring discovery:
**9 failed / 31 passed**. A real-parser regression exposed a primitive boolean
being appended to a question: **1 failed / 43 passed**, then fixed.

Final focused command/API/environment/model-listing run: **201 passed**.
Final full Midje run: **1147 passed**.
CI-style CloverAge run: **1147 passed**.

Both full runs used JDK 17 and the isolated local PostgreSQL 17 fixture on
`127.0.0.1:55432`. No production database or configuration was used. There is no
configured application lint/typecheck task; compilation/full tests, whitespace
and added-line-width checks were used.

```sh
lein midje :all
CLOVERAGE_VERSION=1.1.2 \
  lein with-profile +test cloverage --codecov --runner midje
```

## Observable contracts exercised

- Production parser/interpreter/hooks, not just direct function invocation.
- Primitive, multiline, collection and structured state remains separate from
  the question/schema; boolean false is preserved.
- Quoted EDN field/question schemas and noul/choice/score results.
- All nine supported Clairic types, nullable values, confidence and metadata.
- `clairic ... | data $.answers.amount.value` selects the numeric result.
- A local HTTP fixture exercises streamed JSON requests/responses, the actual
  account/model endpoint selection and authorization/gateway headers.
- Router-disabled configuration works; a throwing classifier fixture ensures
  the standalone commands cannot invoke automatic PR classification.
- Attachments and URLs become embedded image bytes. Duplicate attachment/URL
  references and multiple images retain consistent global image numbering.
- Query-string URLs inside structured state are extracted per string,
  not from serialized JSON punctuation.
- Readable answers shorten with a notice while full values remain in data;
  null values do not silently become guesses. Truncated input produces a
  warning; answer previews do not split Unicode surrogate pairs.
- Worker catalog pagination, sorting, coverage metadata and specialized API
  labels; ordinary provider listing behavior remains covered by existing tests.

## Security and resource checks

- Account IDs/model names constrain inference to the fixed Cloudflare host.
  Only `clef` and `clairic` specialized inference endpoints are selected.
- Credential files remain server-selected and absolute. Input/output checks
  reject the known token; error bodies and file errors are not echoed.
- HTTPS image destinations reject URL credentials, fragments, nonstandard ports
  and private/local/reserved addresses, including IPv6 transition ranges.
- DNS is checked before connecting and pinned in the connection manager. Image
  requests bypass JVM proxies, disable cookies/auth caching and follow neither
  redirects nor retries. They never receive the Cloudflare Authorization
  header.
- Four images maximum, 4 MiB each, 8 MiB combined, 16 megapixels each; URLs
  have a 4096-character limit. Images have bounded streaming/read deadlines.
- HTTP requests abort before stream close, preventing Apache from draining an
  oversized/slow body after a limit or deadline was reached.
- State/schema sizes, 1 MiB streamed responses, catalog pagination and typed
  probability/value validation are exercised. Invalid inputs avoid inference.
- Known-token checks are not comprehensive secret detection. Original chat and
  upstream pipeline logging/history remain subject to existing bot behavior.
  Explicit input can disclose private history/documents to Cloudflare.

A separate credential-free manual check downloaded public samples from
`https://httpbin.org/image/{png,jpeg,webp}` through the production image helper:
PNG **8090 bytes**, JPEG **35588 bytes**, WebP **10568 bytes**. This verifies
real TLS/connection-manager/image path, not Cloudflare inference availability.

## Review and limits

An inline simplification pass retained the shared transport/image helpers,
credential-reuse boundary, protocol checks and proof tests. A manual diff scan
checked security, correctness, existing-provider compatibility and
configuration/documentation.
It is not an independent delegated review.

**Code review: skipped (ce-code-review unavailable)** — its required delegated
review could not run: delegation was not authorized for this request.

No live Cloudflare inference or credential/account/billing qualification was
performed. No deployment, runtime reload, alias update or automatic-PR routing
change was made. Workers AI discovery is not a complete third-party Gateway
catalog, a capability guarantee, or automatic default-model selection.
