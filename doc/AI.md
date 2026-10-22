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
- Allowed tools are available to everyone who can run `ai`. Connections use the
  configured service credentials, not the chat user's identity. Start with search
  tools and narrowly scoped keys. Adding a tool that changes data grants that
  ability too; this release does **not** provide per-user tool permissions or an
  interactive approval step. Use Yetibot's command access controls where needed.
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
