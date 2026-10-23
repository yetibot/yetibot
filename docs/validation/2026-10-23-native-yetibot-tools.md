# Native Yetibot tools validation

## Scope

Opt-in help, current-stored-channel history and count in the shared AI runtime.
No arbitrary commands, cross-channel selection, coding-agent migration or
production configuration changes. Both `ai` and pinned-provider `grok` use the
same host authorization, validation, call budget and redaction.

## Evidence

Local runs used JDK 17 and an isolated PostgreSQL 17 instance. Tests create
unique tables/views/functions and remove them in `finally` blocks.

- Application full suite: 841 checks passed in CI-style coverage.
- Core history suite: 838 checks passed in CI-style coverage; 80 focused checks.
- Core regressions first reproduced 44 failures. Review regressions separately
  reproduced lost grep spaces and three query-hint merge failures.
- Native-only configuration/discovery failed before implementation. Final
  coverage includes the opt-in, schema, provenance and provider-policy checks.
- Real PostgreSQL tests cover timestamp ties, keyset pages, other-room and
  other-connection exclusion, literal search, exact counts, shortened fields,
  escaped JSON output and disabled-history behavior.
- A PostgreSQL view attempts an INSERT during a read: the read-only transaction
  rejects it, and a separate query confirms no marker was written.
- A view with `pg_sleep(5)` verifies that the fixed query timeout cancels reads
  with a sanitized error instead of returning a count.
- Local Responses HTTP fixtures exercise the real parser and both commands,
  retain native xAI web/X tools and return `function_call_output` using call ID.
  `--no-tools` sends no tool definitions. Existing MCP, Chat Completions,
  proposal/staging and Discord-progress tests remain green.
- Native invalid-argument batches, repeated calls and budget overflow fail
  before any history DB query. Missing/disallowed actors and provider/models
  fail before model requests. Discovery hides inaccessible native tools.
- Tool messages remain valid JSON when credential replacement exceeds the
  native output cap: the model receives a structured error, not a cut page.
- The documented EDN recipe and exploded native-tool environment keys are
  tested. Provider and toolset `:native-tools` settings remain separate.
- Core scoped Kibit, whitespace and added-line 80-column checks passed.
  The application has no configured lint/typecheck command.

Commands from each repository, with a test `YETIBOT_DB_URL` configured:

```sh
lein midje :all
CLOVERAGE_VERSION=1.1.2 lein with-profile +test cloverage \
  --codecov --runner midje
```

## Review coverage

Code review: skipped (ce-code-review unavailable)

The loaded full review workflow requires delegated reviewers; none ran under
this request's delegation boundary. No completed independent review is claimed.
A manual correctness/security/API/resource/test-value scan was performed.
It found and fixed the grep-whitespace and caller-query-hint issues above.

## Rollout and limitations

These are local fixture results, not live xAI or Slack/Discord qualification.
No deployment, production configuration or live credentials were changed.

Native tools need no new core API or dependency version. Human `history`
corrections are a separate core PR and require its release and adoption.

History tools are disabled unless listed on a named toolset with explicit
user/conversation/adapter/provider/model policy. Enabling one permits channel
records, including commands and bot output, to reach that provider. Results
are untrusted data; credential replacement does not detect every secret.

Scope uses the trusted event's stored adapter/room key. Slack stores channel
names, so renamed/reused names and historical membership cannot be reconciled
with raw IDs by this schema. Enable only where that limitation is acceptable.
Discord reads the incoming channel/thread key, not a newly-created reply thread.
See `doc/AI.md` for configuration, paging, output and timeout limits.
