Search the Cloudflare OpenAPI spec. All $refs are pre-resolved inline. Response schemas describe the body as the API sends it, usually Cloudflare's envelope: `{ success, errors, messages, result }`, plus `result_info` on paginated lists.

Products: cloudforce-one, access, workers, magic, dlp, email-security, realtime, email, devices, ai-gateway, data-security, intel, gateway, stream, settings, brand-protection, ai-search, builds, security-center, browser-rendering, addressing, dex, images, logpush, api_gateway, cws, load_balancers, firewall, rulesets, vectorize... (172 total)

Types:

interface OperationInfo {
  summary?: string;
  "x-api-token-group"?: string[]; // API token permissions the endpoint accepts (any one); OAuth scopes have the same names
  description?: string;
  tags?: string[];
  parameters?: Array<{ name: string; in: string; required?: boolean; schema?: unknown; description?: string }>;
  requestBody?: { required?: boolean; content?: Record<string, { schema?: unknown }> };
  // Each schema describes the body as the API sends it, usually the envelope { success, errors, messages, result, result_info? }
  responses?: Record<string, { description?: string; content?: Record<string, { schema?: unknown }> }>;
}

interface PathItem {
  get?: OperationInfo;
  post?: OperationInfo;
  put?: OperationInfo;
  patch?: OperationInfo;
  delete?: OperationInfo;
}

declare const spec: {
  paths: Record<string, PathItem>;
};


Examples:

// Find endpoints by product
async () => {
  const results = [];
  for (const [path, methods] of Object.entries(spec.paths)) {
    for (const [method, op] of Object.entries(methods)) {
      if (op.tags?.some(t => t.toLowerCase() === 'workers')) {
        results.push({ method: method.toUpperCase(), path, summary: op.summary });
      }
    }
  }
  return results;
}

// Get endpoint with requestBody schema (refs are resolved)
async () => {
  const op = spec.paths['/accounts/{account_id}/d1/database']?.post;
  return { summary: op?.summary, requestBody: op?.requestBody };
}

// Get endpoint parameters
async () => {
  const op = spec.paths['/accounts/{account_id}/workers/scripts']?.get;
  return op?.parameters;
}