# Grok shared tools: local validation

Validated on 2026-10-22 using an isolated Temurin JDK 17 and Leiningen.
No production configuration or live credentials were inspected or changed.

## Automated evidence

- `lein midje :all`: **742 checks passed**, zero failures or errors.
- `CLOVERAGE_VERSION=1.1.2 lein with-profile +test cloverage --codecov
  --runner midje`: **742 checks passed**.
- Final runs include upstream GitHub directory-decoding fix `8138dc5`.
- Local HTTP Responses and MCP servers exercised mixed native/client tools,
  encrypted output replay, `call_id` continuation, MCP validation and budgets,
  uncertain outcomes, safe final turns, redaction, and connection cleanup.
- The real interpreter routed both `ai --provider grok --toolset ...` and
  `grok --toolset ...` through shared authorization. Disallowed users and models
  were rejected before an MCP connection opened.
- Chat Completions compatibility, configuration/environment normalization,
  provider/model selection, and legacy absent-config fallback passed.
- `--no-tools` disables native search and MCP. Failed/incomplete Responses
  results produce safe errors rather than partial answers.
- Hook checks preserve direct `grok agent ...` and `gi ...` routing. Prompts
  containing `agent` after flags stay on the shared Grok path.
- Discord tests cover success/failure replies, late progress creation, creation
  and deletion failures, and deletion waiting for an in-flight edit.

## Review follow-up

Eight review lenses produced three confirmed P2 findings. All were applied
inline: anchor legacy-agent routing; delete late-created Discord status
messages; exercise active progress edits during cleanup. Routing and
late-creation regressions failed before their fixes. The edit test uses
synchronization gates rather than arbitrary test sleeps. No confirmed review
findings remain open.

## Not established by local tests

- Live xAI support/account access for the chosen model and configured native
  tools, service-side billing, or encrypted replay behavior in production.
- Authenticated Cloudflare MCP compatibility or actual token permissions.
  Read-only access requires narrowly scoped Read permissions; allowing the
  Cloudflare `execute` tool alone does not make a token read-only.
- Live Discord transport, rate limits, thread permissions, or bot deployment.
- Container startup: the local Docker daemon was unavailable. JVM tests ran
  directly; the temporary Java installation did not change global setup.

Before deployment, configure the Responses provider and a restricted named
MCP toolset using [AI.md](../../doc/AI.md), then qualify native search, an
authorized Cloudflare read, an unauthorized-toolset attempt, `--no-tools`,
and Discord progress cleanup. Existing `gi` image generation still requires
`xai.key`; it does not use the shared text-provider configuration.
