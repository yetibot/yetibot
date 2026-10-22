# AI toolsets implementation validation

Date: 2026-10-22

## Scope

Implements `docs/plans/2026-10-21-001-feat-ai-configurable-github-search-plan.md` on `feat/ai-github-toolsets`, based on the existing MCP commit `e43e48b` from `origin/feat/ai-mcp-tools`.
The original plan is unchanged during execution.
No changes were made to the user's `.env`, production configuration, deployed services, or live repositories.

## Verification

- **AI/MCP regression and integration tests:** 335 checks pass, covering all AI API, capability, environment, MCP transport, toolset/policy/proposal/GitHub model, context, command, and integration namespaces.
- **Full suite:** 520 checks pass; three fail in the unchanged `test/yetibot/test/commands/clojure.clj` at its Clojail evaluations (lines 23, 29, 33). The available JDK 25 reports `Setting a Security Manager is not supported`. Neither the Clojail implementation nor its tests differ from the baseline. These failures were not suppressed or modified.
- **Compilation check:** `lein check` reached every namespace, including all new AI namespaces, but did not exit within 120 seconds. It was terminated by the command timeout. Legacy reflection/Unsafe warnings remain; do not treat this as a successful completed check.
- **Whitespace check:** `git diff --check` passes.
- **Runtime used:** Homebrew OpenJDK through per-command `JAVA_HOME` and PATH overrides; no global Java configuration was changed.

The focused command is:

```sh
lein test yetibot.test.api.ai-environment yetibot.test.api.ai \
  yetibot.test.api.ai-capabilities yetibot.test.api.mcp \
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
No independent subagents or cross-model review were launched: delegation was not requested.
A dedicated multi-agent code review was therefore not run; independent review is still recommended before merge for this credentialed-write feature.

## Remaining qualification

A deployed local inference server/model and GitHub/search credentials were not supplied for live smoke testing.
Protocol fixtures prove the integration boundaries, not a particular model's tool-use quality or a particular deployed GitHub server version's response shapes.
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
