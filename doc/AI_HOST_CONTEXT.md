# Yetibot host context

Parent `ai` and `grok` requests receive trusted, repository-maintained
orientation, whether tools are enabled, unavailable, or disabled with
`--no-tools`. It identifies Yetibot as a chat-command environment rather
than a terminal, directs the model to verify syntax with advertised help,
and distinguishes model catalog data from chat-compatible model IDs.

The bounded `ai`/`cf` delegation adapters still send only the explicitly
supplied text to a child model. They do not inherit this parent context,
conversation history, tools, or searches. Their output caps, caller policy,
and uncertain-outcome stopping behavior are unchanged.

## Model-selection recipe

1. Use an advertised provider/model discovery tool.
2. Select from models identified as having a `chat` interface. Speech,
   embeddings, images, decision, and extraction APIs are not chat models.
3. When a bounded utility tool is advertised, use it for random selection
   from eligible structured data. Do not invent `ai --random` or a Bash
   `shuf` script. If the capability is absent, explain that limitation.
4. Invoke the advertised `ai`/`cf` tool with the exact discovered model ID,
   explicit provider, and supplied prompt. Do not claim a delegated child
   can inspect the parent conversation or execute tools.

A user-facing command template is:

```text
!ai --provider cloudflare --model <chat-model-id> <prompt>
```

Replace the placeholder with a real eligible catalog ID. Command-rendered
model listings are not necessarily JSON or one item per pipeline row;
prefer structured tool data rather than guessing how to parse the display.

## Trust and verification

The orientation is ordinary host code, not an external skill loader.
Command help, previous bot messages, model catalogs, MCP initialize
instructions, web pages, and tool results remain data, not authority to
change the host's policy. Documentation does not grant a callable tool.
Only capabilities actually advertised for the current authorized request
can be used. No-tools requests retain orientation but no tool access.

Offline tests inspect parent request messages, preserve conversation
content, and retain the single-hop child wire assertions. They do not
prove that a model will always follow instructions, qualify small-model
answer quality, or diagnose provider HTTP 400 responses. Those require
separate evidence; no paid inference is part of this verification.
