# AI usage and cost reports

```text
ai usage
ai usage 2026-11
ai usage 2026-11-01 2026-12-01
ai usage 2026-11-01T00:00:00Z 2026-11-05T12:00:00Z
```

`ai usage` defaults to the current UTC calendar month. One `YYYY-MM` selects a
calendar month. Two bounds select an arbitrary interval: start inclusive, end
exclusive. Date-only bounds mean midnight UTC. Instants must include `Z` or an
explicit offset; ambiguous local timestamps and reversed/empty ranges are
rejected. The report prints the normalized UTC interval.

## Access and scope

Any trusted human chat actor may read bot-wide totals; administrator status is
not required. Bot, forged and unauthenticated requests fail before database
reads. There is no dedicated native usage-report tool. Reading a report does
not invoke a model, fetch a provider catalog or connect to MCP.

The report covers supported Yetibot inference boundaries recorded in this
database, not entire provider accounts or other applications. Normal runtime
capture includes text, Responses, streams, each tool round, delegated models,
structured classifiers, images, video, legacy OpenAI/xAI and Gemini CLI sessions.
Metadata/catalog GETs, downloads and video status polls are not extra inference.
Opaque external tools and their service fees are not observable unless included
in the provider or CLI's reported statistics.

## Reading the totals

- **Recorded events:** HTTP inference reservations and CLI sessions, not human
  messages. Pending rows can represent in-flight work, a crash, or a failed
  final ledger write. Unknown transport outcomes may still have incurred cost.
- **Known requests:** confirmed HTTP receipts plus available CLI-native request
  counts. The report separately counts events with unavailable request counts.
  A session without statistics is not invented as one request.
- **Reported USD:** accepted per-request figures from existing known-origin
  provider cost fields. They are not account invoices or entitlement proofs.
- **Estimated USD:** existing published token-rate or configured media estimates.
  Estimates can omit tool fees, media, tiers or discounts. A zero known subtotal
  does not price any of the separately listed unpriced events as free.
- **Unpriced events:** at least one model summary lacks usable dollar accounting.
  Available units remain visible; missing costs are not substituted with zero.

Each actual wire turn, including a delegated child model, has its own receipt.
The ledger does not scrape or re-add accumulated parent answer captions. A
model's reported and estimated subtotals can coexist over a timeframe.

Provider/model labels identify requested routes, except CLI summaries use their
reported model IDs. They do not identify hidden backend models behind aliases.
Input/output, cached, uncached and thinking counters retain provider-native
units. In Gemini/CLI summaries, output can mean candidate tokens while thinking
is separate; other APIs can include reasoning inside output. Do not add cached
or thinking counters blindly or infer a bill from a mixed-provider token sum.
Images, video seconds and neurons keep their own units.

CLI summaries are aggregates attributed to session start, not individual HTTP
traces. A multi-model session appears in several model groups, but global
session counts are distinct. Group event counts are therefore not additive.
CLI's older family-based budget calculations do not become reported USD.

The model breakdown is limited to 200 groups. Global totals still include all
groups, and truncation is explicit. Reports run as one consistent, read-only
snapshot with bounded database waits; unavailable reports return a static error
rather than a fabricated empty total.

## Rollout and failures

The coverage marker records when runtime ledger tracking began. A window that
starts earlier is partial; a pre-rollout window with no records does not mean
zero usage. Missing coverage metadata also prevents a completeness claim.
There is no historical backfill from answer captions, old logs or approximate
Gemini image-equivalent budget rows.

Before inference or CLI spawn, the bot must commit a pending row. If that write
fails, no inference is sent. A final ledger write or metrics-normalization
failure does not replace a completed model result: the pending row remains an
honest uncertainty. Inference is never retried just to repair accounting.
Existing model, transport, media-budget and delivery errors retain their normal
handling; the ledger is not a replacement for those components.

The existing approximate Gemini monthly media/agent budget remains separate
and unchanged. Its totals are not precise ledger history or provider bills.
No new billing credentials or provider-account reconciliation are required.

## Explore data

Direct reports provide a reviewed public projection for frozen Discord data
navigation. It contains only scope, UTC bounds, coverage, safe provider/model
labels, numeric subtotals and accounting caveats. It contains no event IDs,
actor/channel identities, prompts, completions, raw reasoning, credentials or
private tool payloads. Error replies do not echo invalid arguments and stop
remaining pipeline stages rather than sending their error text to inference.

The schema-1 public counter fields are `input_count`, `output_count`,
`uncached_count`, `cached_count`, `thinking_count` and
`text_counter_unknown_events`. These are numeric token counters, not token
credentials or reasoning text. Their names let the existing generic snapshot
redaction remain strict; no secret-field filter is relaxed for reports.

Opening, expanding or paging retained data reads the frozen result, not the
current database. Normal ownership, revision, replay and snapshot bounds still
apply. Large frozen trees can be shortened independently of the SQL model
breakdown; the readable report's global totals remain the full query totals.
