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
