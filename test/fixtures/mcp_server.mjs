// Local MCP fixture. No network calls or external dependencies.
import { createInterface } from "node:readline";

const lines = createInterface({ input: process.stdin, crlfDelay: Infinity });

for await (const line of lines) {
  const message = JSON.parse(line);
  if (!Object.hasOwn(message, "id")) continue;

  let result;
  switch (message.method) {
    case "initialize":
      result = {
        protocolVersion: message.params.protocolVersion,
        capabilities: { tools: {} },
        serverInfo: { name: "stdio-search", version: "1.0" },
      };
      break;
    case "tools/list":
      result = {
        tools: [{
          name: "web_search",
          description: "Search the web.",
          inputSchema: { type: "object", properties: { query: { type: "string" } } },
        }],
      };
      break;
    case "tools/call":
      result = {
        content: [{
          type: "text",
          text: JSON.stringify({
            query: message.params.arguments.query,
            explicit_key_present: process.env.MCP_TEST_KEY === "fake-mcp-key",
            bot_secrets_inherited: Object.keys(process.env).some((key) =>
              /^(YETIBOT_|YB_|GH_TOKEN|GITHUB_TOKEN)/.test(key)),
          }),
        }],
        isError: false,
      };
      break;
    default:
      process.stdout.write(`${JSON.stringify({
        jsonrpc: "2.0",
        id: message.id,
        error: { code: -32601, message: "Unknown method" },
      })}\n`);
      continue;
  }

  process.stdout.write(`${JSON.stringify({ jsonrpc: "2.0", id: message.id, result })}\n`);
}
