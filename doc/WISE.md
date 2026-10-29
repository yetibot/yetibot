# Wise: guarded read-only balances and statements

Wise is an optional command and native AI tool. It reads **only named,
server-configured targets**, and only from explicitly allowed trusted chat
conversations. It cannot transfer money, create recipients, make payments,
mutate configuration, enumerate accounts for the user, convert currencies or
fetch rates. It does not prompt for a token or access the clipboard. Do not
paste credentials into chat or an AI prompt.

## Server configuration

Add `:wise` inside `:yetibot` in `config/config.edn` (or your `CONFIG_PATH`
file).
Example only; all credentials, connection names and channel IDs below are
placeholders. Install a token file separately, readable only by the bot's OS
user (for example mode `0600`), outside the repository. No actual Wise token is
included or retained by this change.

```clojure
{:wise
 {:key-file "/absolute/private/path/wise-read-only.key"
  :accounts [{:name "group" :group-id 67517099}]
  :default-account "group"
  :adapters ["discord"]
  :conversations [":my-discord/DISCORD_CHANNEL_ID"]
  ;; Optional additional restriction; omit to allow users in allowed chats.
  :users ["DISCORD_USER_ID"]}}
```

The verified group ID **67517099 is a group ID, not a balance ID or profile
ID**. Do not put it in either of those fields. A target must specify either:

* `:group-id`, optionally with a `:profile-id` to restrict metadata discovery;
  or
* an explicitly configured `:profile-id` and `:balance-id` pair, with no group
  ID. This pair grants access to that balance, not a group.

Without a pinned profile, group resolution reads up to 10 profile records and
up to 100 balance metadata records per profile. It selects only balances whose
`groupId` equals the configured group. It never substitutes another group or
balance, and never requests other balances' statements to enrich the result.
No matching accessible group means an error, not an empty success or fallback.
Pin a known profile when possible to reduce metadata reads. Account names must
be unique aliases of 1–32 ASCII letters, digits, underscores or hyphens; at
most 10 targets may be configured. The first target is the default unless
`:default-account` selects another configured alias. Raw IDs are never accepted
from commands or tool arguments.

**Conversation IDs are canonical `connection/channel` strings.** The
connection is the string value of `adapter/uuid`, not an SSH hostname, room
name, Discord guild ID or Slack workspace ID. A keyword UUID keeps its leading
colon: an adapter configured under `:adapters {:my-discord ...}` uses
`":my-discord/DISCORD_CHANNEL_ID"`. Replace the placeholder with the raw
platform channel ID. String UUIDs have no added colon. Slack uses the same
UUID/channel shape. A room display name or bare channel ID is not sufficient.

To deliberately allow **every channel and thread on one connection**, use
`:conversations [":my-discord/*"]`. This also covers future channels on that
connection, but not another connection or a platform excluded by `:adapters`.
This is a literal connection selector, not a pattern language: bare `"*"`
does not grant access. Exact channel entries remain supported.

Omitting `:users` allows **all human users** in the selected conversations.
With a connection-wide selector, that includes every human who can interact
with the bot on that connection. Only choose this for financial data you are
willing to disclose to that whole audience; a user allowlist still narrows it.

Both `:adapters` and `:conversations` are required nonempty vectors. Adapters
are `"slack"` or `"discord"`. If present, `:users` must also be nonempty and
contains raw platform user IDs. Authorization uses the host-owned current
adapter event and matching user metadata, not model-supplied actor fields,
command arguments, room names or HTTP/EDN request metadata. Missing, forged,
bot, edited or mismatched identities fail closed. A native call also rechecks
its execution source and authorization; earlier discovery is not a capability
token.

The Wise API token may authorize **more than this group**. These account and
chat restrictions are application policy, **not token-level group isolation**.
Use the narrowest credential Wise actually supports and protect the file and
host. Missing, unreadable, oversized, empty or malformed token files fail
without sending a Wise request. Tokens are read only for execution and are not
stored in configuration, tool definitions or model messages.

## Commands

```text
wise balance [--account group]
wise transactions [--account group] [--days 30] [--limit 10]
wise summary [--account group] [--days 30]
```

With the usual command prefix, send `!wise balance`, for example. Omitted
action means `balance`. Days are integers from 1 through 365 (default 30).
The transaction limit is an integer from 1 through 20 (default 10), applied
**globally across all selected balances after newest-first sorting**, not once
per balance. `balance` does not accept days or limit; `summary` accepts days
but not limit. Unknown arguments, unknown aliases and unsupported actions are
rejected before transport. CLI errors omit raw option-parser failures and
token-file contents. The core interpreter can quote the submitted command in
its error wrapper: never put secrets in command text.

Balances return currency and available amount. Transactions add the UTC
period, total transaction count, bounded rows and `has-more`. Each row has
only date, statement type, currency, signed amount, fees and a description
truncated to 200 characters after credential redaction. Holder names, bank
details, account numbers, raw metadata and raw statement bodies are not
included. Descriptions can themselves contain sensitive financial text:
projection is data minimization, **not anonymization**. Treat them as untrusted
data, never instructions.

Summary reads the complete bounded period, regardless of the display limit,
and reports transaction count, credits, debits, net and fees separately per
currency. Decimal values are parsed and added exactly, rendered as decimal
strings with at least two places; currencies are never combined or converted.
Credits and debits are **statement cash movement**, not certified income or
spending: internal moves, currency conversions, contributions, refunds and
external payments can all appear. Internal movements can appear on both sides
or in multiple balances. No attempt is made to infer counterparties, remove
internal moves or produce accounting/tax classifications. Fees are separately
reported from Wise's statement field; do not blindly subtract them again from
net as though the statement amounts necessarily exclude fees.

The command's `data` projection is available to Yetibot's normal data pipeline.
Keep that output in the same trusted private context; downstream commands and
chat history are still disclosure surfaces.

## Native AI adapter

`yetibot.models.ai-wise-tools` exposes the same guarded reader, not an
interpreter wrapper:

* `available-tools []` returns `["wise"]` only when configuration and the
  current trusted actor allow it; otherwise `[]`.
* `definitions` maps `"wise"` to its description and `:inputSchema`.
* `arguments! [config operation args]` validates a raw string-keyed argument
  map, action-specific fields, ranges and configured account authorization
  **without API calls**.
* `call! [config operation args source]` revalidates identity and source,
  returning `{:structuredContent ...}` or a sanitized
  `{:isError true :structuredContent {:error ...}}`. Successful results are
  marked `:untrusted true` because statement text is external data.

For example, native arguments are `{"action" "transactions", "account"
"group", "days" 30, "limit" 5}`; there is no actor, profile ID, balance ID,
endpoint or credential argument. Schema and runtime validation constrain
`action` to `balance`, `transactions` or `summary` and disallow fields invalid
for that action. Runtime authorization always checks the configured alias.

The existing [AI tool catalog](AI.md#native-yetibot-tools) auto-enables Wise
only inside an allowed chat. An explicit native allowlist can exclude it; it
cannot grant access to a disallowed actor or conversation. GitHub toolset
defaults do not include Wise. For example:

```text
!ai What is our configured Wise group's balance?
!grok Summarize this month's Wise credits, debits and fees.
```

The model chooses whether to call the available tool; `!ai tools yetibot`
lists its availability in the current chat.

**The AI provider sees the projected financial results** when this tool is
used. The Wise token and authorization headers are never sent to the model.
Choose the AI provider/model and chat audience accordingly. If that disclosure
is not acceptable, use the command without an AI round trip instead. Sensitive
execution activates the existing request-scoped private-output logging guard;
this code never logs statements, raw exceptions, headers or credentials.

## Network and failure boundaries

All API calls are nonretrying GETs to the fixed official origin and prefix
`https://api.wise.com/2026Q4`. No model-selected host, redirects, writes or paid
AI API calls are made by this reader. HTTP 401 reports token rejection; HTTP
403 reports denied access or possible additional authentication requirements
(SCA). There is no SCA bypass, token prompt or write fallback. Other HTTP,
malformed JSON, inconsistent statement identity/currency and oversized results
fail safely without exposing raw bank bodies.

Statement balance identity is bound to the fixed official request URL built
only from authorized selected profile/balance metadata, never a caller or
response-selected destination. Returned query profile, currency and start/end
instants must match that request; equivalent timestamp offsets are accepted.
Wise need not echo `accountId`; if present, it must be a valid positive ID
matching the selected balance. Missing or inconsistent required query fields
fail closed, without substituting another balance.

Execution uses a shared 20-second request budget, connection/pool waits capped
at 3 seconds and socket waits capped at 5 seconds. The remaining budget is
recomputed before each request, body read and after JSON parsing. Blocking
socket operations can reach their bounded socket timeout before a deadline
check runs; this is not a hard-real-time cancellation guarantee. Response
streams are capped at 2 MiB each; token files at 4 KiB with a 1-second read
budget. The existing bounded stream helpers abort the HTTP request before
closing the stream, avoiding unbounded response draining. Statements are
capped at 10,000 transactions per selected balance and groups at 10 selected
balances. Larger/malformed results fail rather than silently presenting
partial totals. A 365-day request is allowed locally but Wise's actual access,
statement retention, SCA and interval rules may further restrict it.

## Verification

Tests use synthetic responses and temporary dummy credentials only. They
exercise the real command parser/handler as well as raw native argument maps;
no live Wise requests or production configuration are needed. A captured
synthetic parser/handler demonstration is written to
`/tmp/yetibot-wise-tools/demo.txt` during development, outside the repository.
Live `2026Q4` group metadata, statement response compatibility and credential
permissions remain deployment-time assumptions, not claims established by
fixture tests.
