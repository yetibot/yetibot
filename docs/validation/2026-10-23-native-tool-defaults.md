# Native Yetibot tools enabled by default

## Report and cause

The live `!ai tools` screenshot listed Cloudflare, Exa and GitHub MCP tools,
but no Yetibot help/history tools. PR #1248 required a named toolset with an
explicit `:native-tools` list. Flat MCP configurations never advertised local
definitions, and the completion path skipped them too. This was a default
behavior mismatch, not merely a missing line in discovery output.

## Corrected behavior

- Trusted Slack/Discord requests receive `help`, `history`, `history_count`
  by default, even with no MCP configuration. They appear under `yetibot`.
- Existing named toolset identity/provider/model policy still applies.
  Explicit native lists remain overrides; `:native-tools []` on a search
  toolset disables its built-ins. `--no-tools` disables all tools.
- Non-chat callers do not receive implicit native tools. Trusted event scope,
  private-output logging, argument validation, read-only transactions, resource
  bounds, shared budgets and redaction are retained.
- Text-only providers without MCP or explicit native requests stay usable.
- The documented source name `yetibot` is reserved against MCP collisions.

This deliberately changes disclosure defaults: tool-enabled trusted requests
can send stored current-channel messages, including commands and bot responses,
to the selected provider without another native-tool configuration step.
Existing Slack room-name storage ambiguity still applies; see `doc/AI.md`.

## Verification

- The first regression run reproduced 31 failing checks (80 passed).
- The first fixed focused run passed 111 checks. Additional default-scope,
  named-policy and text-only-provider regressions were then added.
- Final full Midje suite: **973 checks passed**. The clojure.test runner
  reported 2 tests with zero failures/errors.
- CI-style coverage: **973 checks passed**, on JDK 17 with PostgreSQL 17.
- Real Responses HTTP fixtures run both `ai` and pinned `grok` with absent,
  empty, explicit and implicit native configuration. Actual PostgreSQL reads
  verify count and scope. Existing MCP fixtures select their intended function
  by name rather than assuming it is the last advertised function.
- Manual fix-only correctness/security/compatibility/simplification scan;
  no independent delegated review was run.

## Rollout

No live configuration, credentials or deployment changed during verification.
Merge and deploy this correction; no extra native-tool enablement configuration
is needed afterward. Expected additional `!ai tools` entries:

```text
yetibot/help
yetibot/history
yetibot/history_count
```
