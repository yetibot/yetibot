# Opt-in AI live checks

`scripts/ai_live_smoke.clj` runs the real provider adapter, tool runner and
bounded native callbacks in a **separate operator JVM**. It does not test
Discord delivery. It never installs a paid schedule or runs live in CI.

## Cost and data boundaries

Get explicit approval for the request and output budget before running.
Every inference POST, including failures and follow-ups, consumes one slot.
The script accepts an existing absolute ledger path; it never creates or
resets a budget. Its ceiling is 18 requests and 2,048 output tokens/request.
These are request/output limits, **not a dollar cap** for reasoning models.

Run one operator process at a time. The ledger is file-locked and reserves
before network. A pending request survives a process crash. Transport or
ambiguous failures stop further requests; there are no automatic retries.
Do not clear pending/stopped state to try again without new authorization.

Only configured Grok/Cloudflare endpoints are used. Native searches and MCP
execution are disabled. Alias tests replace the store with an in-memory
fixture; no real alias is read or created. No history, bank data or external
write tools are available. Credentials stay on the configured host.

## Run

Use the same Java17, classpath and configuration as the deployment, but not
its service JVM or REPL. Make a private ledger only after approval, with the
approved cap, zero used slots and an empty attempts array:

```json
{"cap": 12, "used": 0, "attempts": []}
```

Protect the directory/file with0700/0600 permissions. Keep the same ledger
across all cases in a round. Never overwrite a prior round's ledger.

Example invocation from the repository:

```text
JAVA_TOOL_OPTIONS='-Dyetibot.live-smoke.enabled=true -Dyetibot.live-smoke.budget=/absolute/private/budget.json' lein trampoline run -m clojure.main scripts/ai_live_smoke.clj grok budget
```

Cases: `greeting`, `sum`, `budget`, `aliases`, `unknown`, `random-model`.
Providers: `grok`, `cloudflare`. Most tool cases need two POSTs; a greeting
needs one. A model may fail to follow the requested tool protocol, which is
a failing check, not permission to retry. `unknown` simulates an uncertain
native result with budget remaining and checks the final no-more-tools turn.
`random-model` reads only public Cloudflare catalog metadata, samples actual
chat IDs through the utility adapter, and checks its example with Yetibot's
real AI argument parser. It does not invoke the selected model.

Output contains only fixed assertion flags/counts and closed failure tags.
It never prints prompts, model prose, credentials, tool arguments/results or
configuration. Exit0 means all case assertions passed; exit1 means failure.
A reasoning-only response that reaches its length limit is not a pass.

Offline guard tests use HTTP stubs, not paid calls:

```text
lein midje yetibot.test.scripts.ai-live-smoke
```

## Verified 2026-10-29

18 total authorized POSTs, including the original400 reproduction, the
accepted counterfactual and failed exploratory canaries. Final checks used
2,048 output tokens; the earlier256-token ceiling exhausted Cloudflare
reasoning before visible text. Grok's real utility, fixture-alias and unknown
outcome loops passed. Cloudflare's utility loop and normal-budget greeting
passed. Random selection used35 live chat IDs, executed the real utility,
completed the no-tools follow-up, and produced a parser-valid command.
The earlier random-command formatting assertion failed at256 tokens; its
single-line heuristic was also replaced with the actual command parser.
Those earlier failures remain part of the request count, not hidden retries.

This qualifies the named paths/models, not every Cloudflare catalog entry,
all model answer quality, production alias contents or live banking/Discord.

## No-inference chat smoke checks

```text
!ai tools
!ai tools yetibot
!ai tools yetibot/cf
!ai tools | tail 3 | xargs ai tools
!ai tools yetibot/cf | data show
```

These inspect metadata only. Prompts such as `!grok --no-tools Say hello.`
and `!ai --provider cloudflare --no-tools Say hello.` invoke paid inference;
run them only as part of an approved budget, not as extra uncounted probes.
