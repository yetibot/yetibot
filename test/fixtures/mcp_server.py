"""Local MCP fixture. No network calls or external dependencies."""
import json
import os
import sys

for line in sys.stdin:
    message = json.loads(line)
    if "id" not in message:
        continue
    method = message["method"]
    if method == "initialize":
        result = {
            "protocolVersion": message["params"]["protocolVersion"],
            "capabilities": {"tools": {}},
            "serverInfo": {"name": "stdio-search", "version": "1.0"},
        }
    elif method == "tools/list":
        result = {"tools": [{
            "name": "web_search",
            "description": "Search the web.",
            "inputSchema": {"type": "object", "properties": {"query": {"type": "string"}}},
        }]}
    elif method == "tools/call":
        result = {"content": [{"type": "text", "text": json.dumps({
            "query": message["params"]["arguments"]["query"],
            "explicit_key_present": os.environ.get("MCP_TEST_KEY") == "fake-mcp-key",
            "bot_secrets_inherited": any(
                key.startswith(("YETIBOT_", "YB_", "GH_TOKEN", "GITHUB_TOKEN"))
                for key in os.environ
            ),
        })}], "isError": False}
    else:
        print(json.dumps({"jsonrpc": "2.0", "id": message["id"],
                          "error": {"code": -32601, "message": "Unknown method"}}), flush=True)
        continue
    print(json.dumps({"jsonrpc": "2.0", "id": message["id"], "result": result}), flush=True)
