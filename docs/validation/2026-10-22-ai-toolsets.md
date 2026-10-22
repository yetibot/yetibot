# AI toolsets implementation validation

Date: 2026-10-22

## Scope

Implements `docs/plans/2026-10-21-001-feat-ai-configurable-github-search-plan.md` on `feat/ai-github-toolsets`, based on the existing MCP commit `e43e48b` from `origin/feat/ai-mcp-tools`.
The original plan is unchanged during execution.
No changes were made to the user's `.env`, production configuration, deployed services, or live repositories.

## Verification

- **Full JDK 17 suite:** all 637 checks pass, including Clojail's positive and forbidden-operation checks and the new proposal/transport regressions.
- **CI-equivalent coverage:** `CLOVERAGE_VERSION=1.1.2 lein with-profile +test cloverage --codecov --runner midje` passes all 637 checks on JDK 17.
- **Sandbox/transport regression:** a fresh JDK 17 subprocess installs Clojail's SecurityManager before loading MCP, then verifies real JSON/SSE exchanges and worker cleanup on both successful sessions and failed initialization. The same SecurityManager remains installed and forbidden sandbox operations remain denied. This regression initially failed before the transport fix.
- **Runtime qualification:** earlier JDK 25 runs failed three unchanged Clojail checks because that JDK removed SecurityManager support; no tests were suppressed or weakened. Use JDK 17 for the full legacy sandbox suite. The optional CI JDK pin was omitted at the operator's request, so the GitHub workflow is unchanged.
- **Compilation check:** `lein check` reached every namespace, including all new AI namespaces, but did not exit within 120 seconds. It was terminated by the command timeout. Legacy reflection/Unsafe warnings remain; do not treat this as a successful completed check.
- **Whitespace check:** `git diff --check` passes.
- **Runtime used:** a temporary Temurin 17 installation through per-command `JAVA_HOME` and PATH overrides; no global Java configuration was changed.

The focused command is:

```sh
lein test yetibot.test.api.ai-environment yetibot.test.api.ai \
  yetibot.test.api.ai-capabilities yetibot.test.api.mcp \
  yetibot.test.api.mcp-security \
  yetibot.test.models.ai-toolsets yetibot.test.models.ai-tool-policy \
  yetibot.test.models.ai-proposals yetibot.test.models.ai-github \
  yetibot.test.models.ai-tools yetibot.test.models.ai-context \
  yetibot.test.commands.ai yetibot.test.commands.ai-toolsets \
  yetibot.test.integration.ai-tools
```

## Evidence by implementation unit

- U1: Added toolset tests before production code; initially failed because the namespace did not exist. Selection and invalid-reference tests pass, including selected-server isolation and legacy search behavior.
- U2: Observed five expected failing capability checks before implementation. The provider now validates environment booleans, can omit `tool_choice`, and refuses disabled tool use before MCP discovery.
- U3: Added policy tests before production code; initially failed because the namespace did not exist. Stable adapter identity, repository constraints, plain-term search scoping, and provider/model policy pass.
- U4: Added proposal tests before production code. Follow-up tests first failed for missing review-page support. Expiry, actor/thread isolation, path/content limits, all-page review, concurrent one-shot claims, and line-ending disclosure pass.
- U5: Added GitHub executor tests before production code. A later same-length-corruption test failed as expected before blob identity verification was added. Base races and uncertain pushes stop without replay; the happy path uses exact files and `draft=true`.
- U6: Added command tests before production code; toolset parsing/context propagation failed as expected before implementation. Discovery hides private/writer-only entries; approval is direct and one-shot without inference.
- U7: Added environment tests against actual `dec/explode` output; all six initial checks failed before normalization was implemented. The complete documented EDN recipe also validates. The end-to-end test uses actual OpenAI-compatible HTTP and actual MCP Streamable HTTP transports, with local protocol fixtures rather than transport/client mocks. Existing real stdio MCP tests remain passing.

## Review

Simplification and correctness/security/failure-path review were performed inline.
The executor was split into source verification, approved writes, and connection/orchestration helpers without changing its tested operation order.
The pass strengthened source-byte/blob matching, mutation uncertainty cutoffs, credential redaction across filtered connections, exact PR-body review, and review-text hash binding.
After the operator requested independent review, a fresh-context native reviewer examined PR #1241 at `02b991b` and found two P1 issues and one P2 issue:

- Cleanup could remove a proposal between review and update, allowing a partial record to be recreated. Page updates and approval transitions now validate inside atomic CAS updates; deterministic interleaving tests prove later cleanup/staging remain usable.
- The original diff presentation discarded per-line CR/LF positions. Required review text now uses lossless JSON-escaped strings for lines, delimiters and PR metadata; reconstruction tests cover relocated CRLF, lone CR, trailing blanks, missing final newline and Unicode. Invalid UTF-8 strings are rejected.
- Sensitive-path checks omitted common SSH key basenames. RSA/DSA/ECDSA/Ed25519/XMSS families, nested paths and case variants are now covered.

All three findings were reproduced by failing regressions before correction. The same read-only reviewer independently checked the fixes and transport context/lifecycle changes and returned no remaining blockers. Initial review run: `33451105-d875-4cdd-8baf-74fc5b636984`; follow-up: `20cb3ff2-a559-4641-8ef4-70ab9e1afd90`. No external CLI fallback or cross-model review was used.

The CI investigation separately reproduced a JDK 17 failure when Clojail initialized SecurityManager before the Java HTTP callbacks: an empty callback access-control context denied JSON reflection and follow-up HTTP requests. MCP now confines application permissions to the fixed SDK JSON codec and schedules request publication on a session-owned application-context daemon worker. Workers are disposed on success and failure; sandbox policy and protected operations are unchanged. The focused regression prevents suite load order from hiding this failure.

## PR review feedback resolution

The seven inline findings from the maintainer's review were checked against the
current branch. The expiry race and lossless line-ending findings were already
fixed in `cd3b2f7`; their deterministic regressions still pass, including the
reviewer's relocated-CRLF shebang example. Five remaining findings are fixed:

- **Trusted identities:** only the host adapter `handle-raw` boundary establishes
  provenance. Policy requires that context, matching chat/user metadata, and a
  stable adapter user ID matching the raw event. The real `/api` route cannot
  authorize private discovery or reads with fabricated chat-source EDN.
- **Direct consent:** the real parser tree must equal a standalone review or
  approval command from the original platform message. Generated `cmd` pipelines,
  subexpressions, and the existing observer implementation cannot mark pages
  reviewed or approve using inherited user identity. Successful direct review
  and one-shot approval are also exercised through the actual interpreter.
- **Private output logging:** a request-scoped logging filter is installed before
  existing middleware/appenders, and activated before private tools/review output
  are accessed. The real adapter/interpreter/history/chat path runs with trace
  logging: both synthetic original/replacement markers reach the authorized chat
  output, neither reaches the logger, sanitized proposal audits remain, and a
  subsequent public request still logs normally. No shared core sources or global
  logging configuration are modified.
- **Real GitHub response shape:** the official v2.0.3 `MinimalCommit` source was
  verified to omit parents. A synthetic fixture uses that actual field shape,
  without invented ancestry. With explicit operator approval, the sole non-MCP
  exception is authenticated, fixed-host GitHub REST ancestry reads. A separate
  explicit read token is required, redacted, and never sent to the model; API
  access is preflighted before any mutation. Post-push ancestry must be exactly
  the approved base. Missing/mismatched/merge ancestry, redirects, HTTP failures,
  invalid responses and exceptions stop safely without retries or draft-PR
  creation. All mutations remain MCP-only and are mocked in tests.
- **Environment compatibility:** flat EDN keyword/string environment names keep
  their original spelling, including lowercase proxy variables; only nested
  exploded settings reconstruct uppercase names. Compatibility and normalization
  idempotence tests pass.

Validation: `lein midje :all` and the CI-equivalent coverage command above both
pass 637 checks on JDK 17. Live GitHub/model qualification is still not claimed.

After the fixes were pushed, the prerequisite PR #1240 was found merged via
`1f9fbbd`; GitHub had retargeted this PR to master and conflicts prevented CI.
The updated master was merged without rewriting branch history. Its Node stdio
fixture replaces the removed Python fixture, and its sandbox regression form is
retained. The feature/security changes remain intact; both validation commands
still pass 637 checks against the integrated base.

## Remaining qualification

A deployed local inference server/model and GitHub/search credentials were not supplied for live smoke testing.
Protocol fixtures prove the integration boundaries and the source-verified v2.0.3
MinimalCommit shape, not a particular model's tool-use quality or a live deployed
GitHub server's behavior.
Before enabling writes, qualify the exact endpoint/model, pinned GitHub MCP server, source metadata/content schemas, repository token scopes, chat identities, and disposable-repository draft PR flow described in `doc/AI.md`.
Unsupported or incomplete source/result shapes fail closed.
The implementation currently verifies github.com PR URLs; GitHub Enterprise-specific URL qualification is not included.

## Post-deploy monitoring and validation

- **Owner:** The operator enabling the toolsets; maintainers retain PR review and merge ownership.
- **Window:** First hour after read-only activation, then the first approved disposable-repository PR before production write activation.
- **Logs:** Search `ai-proposal` events by proposal ID/hash and status (`pending`, `executing`, `succeeded`, `failed`, `unknown`). Expect one transition into execution and one terminal result, with no source text or credential values.
- **Healthy signals:** Search citations come from actual results; unselected servers receive no requests; GitHub reads stay within allowed repositories; staging produces no GitHub writes; approval creates one bot branch, one approved commit and one draft PR.
- **Failure signals:** Unsupported schemas, permission denials for expected users, stale-base rejection, missing terminal events, unexpected repeated mutations, or an `unknown` outcome.
- **Mitigation:** Inspect known branch/commit/PR IDs without replaying a failed mutation. Remove the PR toolset/writer connection and restart to disable writes and invalidate pending proposals. Rotate/revoke credentials immediately if exposure is suspected.
