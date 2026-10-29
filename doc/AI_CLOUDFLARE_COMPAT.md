# Cloudflare chat compatibility

Workers AI models do not all accept the same OpenAI-compatible message
shapes. Some reject `content: null` on the assistant turn carrying tool
calls, even though other models accept it. This can make the first model
turn succeed and the post-tool follow-up fail with400.

At the Chat Completions wire boundary for an official Cloudflare account:

- Normalize absent/null message content to an empty string.
- Join all-text content-part arrays with newlines.
- Preserve mixed/image/unknown parts unchanged: never silently drop media.
- Preserve tool-call IDs, arguments, results, roles and ordering.
- Leave other providers and Responses replay unchanged.

Cloudflare's own provider documents the non-null requirement:
https://github.com/cloudflare/ai/blob/main/packages/workers-ai-provider/src/utils.ts

The multi-turn/per-model mismatch is independently documented here:
https://github.com/cloudflare/cloudflare-os/issues/54

This is a wire compatibility fix, not proof that every model always returns
valid tool calls or completes within a deadline. A follow-up transport/read
failure remains an uncertain outcome: no automatic retry or silent model
substitution is added. Explicit model selection is respected.

Non-2xx diagnostics also accept raw JSON strings, since clj-http may leave
error responses undecoded despite `:as :json`. Inspection is byte/depth
bounded before parsing and emits only fixed categories. Oversized/deep or
unknown errors remain unrecognized. Successful/nonstream transport decoding
is unchanged and is not newly bounded by this diagnostic inspection.
