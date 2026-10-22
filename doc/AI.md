# AI command

`ai` works with any endpoint that supports the OpenAI Chat Completions API
(`POST /chat/completions`) and model list API (`GET /models`). You can configure
multiple providers, each with its own name, base URL, optional API key and default
model. The existing `openai` command is unchanged and uses separate configuration.

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
  Do not include `/chat/completions` or `/models`. Trailing slashes are allowed.
  URLs must use HTTP or HTTPS and cannot contain credentials, query strings or
  fragments. Use HTTPS for remote providers.
- `:key` is optional, for local servers that do not need authentication. When
  present, it is sent as a Bearer token. Keep keys out of version control.
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
!ai -- --provider is literal text in this prompt.
```

Options go before the prompt and may appear in either order. `--model` alone
uses the default provider. `ai models` queries every provider and labels each
model list with the provider's name. A failure from one provider does not hide
results from the others. `ai models <provider>` queries just that provider.

## MCP tools and web search

`ai` can use tools from named MCP servers. This is a general connection, not a
built-in list of search providers: adding another server or allowing another tool
only needs configuration. The model decides when to call the available tools.
For current facts, it is instructed to search and cite source URLs from the results.

MCP is optional. Without it, existing chat requests are unchanged. When enabled,
the selected model **and** its OpenAI-compatible endpoint must support function
calling (`tools`, `tool_choice`, `tool_calls` and tool-result messages). Use
`--no-tools` for a model that does not support this, or to skip MCP for one request.

Add `:mcp` alongside `:providers` inside your `:ai` configuration. For example:

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

- Every server needs a unique `:name` and an explicit, nonempty `:tools` list.
  Only those tools are offered to the model, and calls to other names are rejected.
  Tools with the same name on different servers remain separate.
- Legacy search tools are available to everyone who can run `ai`. Named toolsets
  can restrict access by stable user and conversation IDs. GitHub tools require
  a GitHub toolset with explicit access policy; raw GitHub mutations cannot be
  exposed to the model. Connections use service credentials, not the chat user's
  GitHub identity. Use least-privilege keys and command access controls too.
- Supports local stdio and remote **Streamable HTTP**, including JSON and SSE
  response bodies. Old standalone `/sse` transports, interactive OAuth sign-in,
  MCP prompts/resources and server-requested model sampling are not enabled.
- Remote URLs must use HTTP(S), without embedded credentials, query strings or
  fragments. Put authentication in `:headers` or a local server's `:env` instead.
  Redirects are not followed, so use the exact endpoint, including any required
  trailing slash. Prefer HTTPS for remote servers.
- Each prompt opens fresh sessions and closes them when finished, including on
  failures. It discovers allowed tools before asking the model. If a server is
  unavailable or a configured tool is missing, the request fails clearly; it does
  not silently answer as if a search succeeded. `--no-tools` skips all connections.
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
    :servers [{:name "web" :tools ["web_search_exa"]}]}
   {:name "github-read"
    :kind "github-read"
    :users ["SLACK_USER_ID"]
    :adapters ["slack"]
    :conversations ["ADAPTER_UUID/SLACK_CHANNEL_ID"]
    :repositories ["your-org/your-repo"]
    :providers [{:name "local" :models ["YOUR_TOOL_CAPABLE_MODEL"]}]
    :servers [{:name "github-read"
               :tools ["get_file_contents" "get_commit" "list_branches"
                       "issue_read" "pull_request_read" "search_code"]}]}
   {:name "github-pr"
    :kind "github-pr"
    :users ["SLACK_USER_ID"]
    :adapters ["slack"]
    :conversations ["ADAPTER_UUID/SLACK_CHANNEL_ID"]
    :repositories ["your-org/your-repo"]
    :providers [{:name "local" :models ["YOUR_TOOL_CAPABLE_MODEL"]}]
    :base-branches ["main"]
    :allowed-paths ["src/" "test/" "doc/"]
    :denied-paths ["src/credentials/"]
    :branch-prefix "yetibot/ai-"
    :write-server "github-write"
    :servers [{:name "github-read"
               :tools ["get_file_contents" "get_commit" "list_branches"
                       "issue_read" "pull_request_read" "search_code"]}]}]}}
```

The `github-pr` toolset must include `get_commit` and `get_file_contents` for
host-side snapshots. A `search` toolset cannot expose known GitHub tools; use a
GitHub kind so repository and identity checks cannot be bypassed accidentally.
All tool names must appear in their server's allowlist and its live `tools/list`.
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
export YETIBOT_AI_MCP_TOOLSETS_0_SERVERS_0_TOOLS_0=web_search_exa
```

To configure tokens through the environment, the nested server keys are
`YETIBOT_AI_MCP_SERVERS_1_ENV_GITHUB_PERSONAL_ACCESS_TOKEN` and
`YETIBOT_AI_MCP_SERVERS_2_ENV_GITHUB_PERSONAL_ACCESS_TOKEN` for the example above.
Set them privately in your service configuration. EDN does **not** interpolate
`${ENV_VAR}` or resolve VS Code `${input:...}` variables. Do not commit secrets.
As with other settings, configuration changes and token rotation require restart.

### Identity and repository policy

- `:users` contains platform user IDs, never display names. `:conversations`
  contains `<adapter UUID>/<raw channel ID>`, using Yetibot's configured adapter
  UUID and Slack's channel ID or Discord's channel/thread ID. The adapter UUID
  appears in Yetibot's chat-source diagnostics; it is not a workspace name.
- `:adapters` uses `"slack"` or `"discord"`. Missing IDs, edited/bot messages,
  reaction-triggered requests and unsupported adapters cannot authorize writes.
  Approval also binds the current Slack thread or Discord channel/thread.
- Private reads and review output are allowed only in configured conversations.
  Choose private output channels for private repositories. Shared GitHub tokens
  do not impersonate the requesting chat user.
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

### Usage and guarded PR workflow

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

The model has GitHub read tools and a host-side `stage_changes` function, **not**
raw GitHub mutations. Staging reads source at an identified commit and produces
an immutable proposal. No branch, commit or PR is created during generation.
The preview lists its repository, base, hash and expiration. Review every
numbered page, including the exact PR body and complete file diff; approval is
rejected until all pages have been requested by the original user. Each added or
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
- Approval expires after ten minutes. State is process-local, bounded to 128
  active entries, periodically cleaned, and invalidated on restart. Run one
  executor instance; this is not distributed or durable job coordination.
- Each proposal is one-shot, including failed/uncertain executions. A timeout
  stops further mutations without retry. Inspect the reported branch, commit,
  step and GitHub state before generating another proposal. There is no automatic
  force push, rollback, merge or cleanup of partially created branches.
- The normal model budget counts model-selected calls (staging counts as one).
  Host staging additionally performs at most eleven source reads. Approved
  execution is a fixed sequence of at most sixteen tool operations, never an
  unbounded model loop; each uses the configured MCP operation timeout.
- Draft PRs may trigger CI. Ensure repository CI is safe for generated code
  before enabling writes; denying workflow edits alone does not make builds safe.
  Repository review and branch protections remain mandatory. This workflow does
  not clone repositories, run tests or replace the existing Gemini `code` command.

Enable `search` and `github-read` first. Add `github-pr` only after qualifying the
local model and testing against a disposable repository. To disable writes,
remove the PR toolset/writer connection and restart (pending proposals disappear).
Watch sanitized `ai-proposal` state-transition logs for failures/unknown outcomes;
source text, prompts and credentials are not included in these audit events.

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
