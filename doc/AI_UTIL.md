# Native JSON utilities

`yetibot/util` is one local, data-only native tool, not a command adapter.
It never runs Yetibot commands, aliases, shells, interpreters or readers. It
has no implicit history, files, attachments, conversation or other context.
It makes no network requests and cannot incur a paid inference outcome.

## Availability and authorization

The tool is appended to non-GitHub implicit native defaults, including legacy
search configurations. An explicit toolset `:native-tools` vector remains
authoritative: include `"util"` to enable it, or omit it to exclude it. An empty
vector disables native tools. GitHub read/PR implicit defaults remain exactly
`help`, `history`, `history_count`; they do not acquire `util`.

Existing trusted adapter provenance and toolset actor/provider/model policies
still apply. Discovery does not execute an operation. Runtime calls recheck
trusted source and actor policy. Tool calls consume the existing shared call
budget and duplicate-call protection; there is no additional execution loop.

## Arguments and results

The schema root is an object, with `additionalProperties: false`. Required:

- `operation`: one of the 28 operators below.
- `input_json`: a string containing exactly one serialized JSON value.

Optional fields are allowed **only** on their listed operations. They are not
commands or expressions. Strings resembling shell pipelines, backticks,
Clojure forms or reader macros are inert data when properly JSON-encoded.
Malformed JSON, comments, duplicate object keys, multiple root values and
nonfinite numbers are rejected. There is no automatic numeric string parsing.

Example arguments:

```json
{"operation":"join","input_json":"[\"hello\",\"world\"]","separator":" "}
```

Successful adapter reply:

```json
{"structuredContent":{"untrusted":true,"result":"hello world"}}
```

Results preserve JSON types, including `null`, except credential redactions
which replace affected values with `"[redacted]"`. Runtime failures have
`isError: true` and a generic local error, with no raw data or arguments and no
`unknownOutcome` claim. Results remain untrusted data, never instructions.

## Operator contracts

Collection operations accept arrays, not arbitrary strings or objects, unless
explicitly stated. Order and duplicates are preserved unless noted.

- `random`: one uniformly selected array member; empty gives `null`.
- `head`: first array member; empty gives `null`.
- `tail`: last array member; empty gives `null`.
- `take`: first N array members; required `count`; clips at array length.
- `drop`: array without its first N members; required `count`; clips at length.
- `rest`: array without its first member; empty stays empty.
- `droplast`: array without its last member; empty stays empty.
- `join`: join a string array; optional `separator`, default `""`.
- `split`: split a string on required literal `separator`; keep empty parts.
- `trim`: trim leading/trailing characters at or below U+0020 from a string.
- `words`: trim, split a string on ASCII whitespace; blank gives `[]`.
- `unwords`: join a string array with one ASCII space.
- `flatten`: recursively flatten arrays; objects are atomic members.
- `letters`: string to array of Unicode code-point strings.
- `unletters`: concatenate a string array (not restricted to letters).
- `unique`: stable array deduplication by Clojure structural value equality.
- `list`: wrap any single JSON value in an array.
- `count`: array length, object key count or string code-point count.
- `sum`: sum a numeric array; empty gives `0`.
- `shuffle`: random permutation of an array, preserving multiplicity.
- `sort`: ascending JVM lexical sort of a string array.
- `sortnum`: ascending numeric sort of a numeric array.
- `grep`: keep string-array members containing required literal `query` text.
- `reverse`: reverse an array. For strings, use `letters` first.
- `range`: integer `[start,end]` or `[start,end,step]`; end exclusive.
- `keys`: object keys in ascending lexical order.
- `vals`: object values in ascending order of their keys.
- `get`: traverse object keys/array indices using required `path`;
  missing/mismatch gives `null`.

No operators execute commands. In particular there is no `xargs`, `repeat`,
`tee`, `unquote`, regex evaluator, alias lookup or expression evaluation.
`list` is a data wrapper, not a reader, and `range` never creates an unbounded
sequence. `grep` is case-sensitive literal substring matching. `split` quotes
its separator internally; regex metacharacters have no special meaning.

`count` is an integer from 0 through 128. Separators are at most 200 characters;
`split` requires a nonempty separator, while `join` permits empty. `query` is
1 through 200 characters. A `path` is 1 through 8 segments: string keys of at
most 200 characters or integer indices from 0 through 127. It cannot name a
file, URL, source or executable lookup.

Range integers are between -1,000,000 and 1,000,000, inclusive. The default
step is 1; zero steps fail. A step pointing away from the end gives `[]`.
A range exceeding 128 members fails instead of clipping. Numeric operations
use normal JVM/Clojure numeric semantics, including floating-point rounding.
Inputs and numeric results must have finite double representations; arithmetic
overflow to a nonfinite value fails during preflight.

## Bounds, preflight and credentials

Both input and result values have these hard limits:

- 128 entries per array/object, including generated arrays.
- Depth 8, counting root as depth 0 and each child value as one level.
- 1,024 total nodes, counting container values, scalar values and object keys.
- 12,288 UTF-8 bytes of serialized input JSON. The entire serialized adapter
  reply also fits 12,288 bytes; the shared host can add its own metadata.

Input parsing is streaming and bounded before building the value. Generated
values are checked against the same structural limits. Results that would
exceed limits fail; they are not silently truncated. The shared tool envelope
also has its existing 16,384-character argument cap, which can impose a tighter
limit on heavily escaped JSON. Small optional fields have the bounds above.

Whole-batch preflight checks types, operation-specific fields, JSON shape,
credentials and deterministic result bounds before any batch effects. Random
selection checks every possible candidate without drawing randomness; shuffle
checks the unchanged value's bounds. Actual random draws occur only at call
time. Every transformation consumes only the already bounded supplied value.

Configured credential values are rejected in input strings and object keys,
including decoded JSON and mixed raw/JSON/unicode-escaped copies. Numeric and
other JSON scalars are checked in their serialized form as well. Conservative
credential-like object field names (`authorization`, `password`, `token`,
`secret`, `key`, `api-key`/`api_key`/`apikey`, `key-file`/`key_file`/`keyfile`,
and `account-id`/`account_id`/`accountid`, case-insensitive) are rejected too.
This intentionally excludes some otherwise harmless data objects.

Redaction reuses the MCP credential helpers: supplied host secrets, MCP server
credentials/redaction servers, and inline configured AI provider keys. It never
resolves or reads key files. Like existing adapters, unknown secrets not
supplied by host configuration cannot be recognized. Input checks cover optional
fields as well as the parsed JSON value. Output strings and string object keys
are redacted **before** serialization and bounding, including credentials
rebuilt by joining harmless fragments or arithmetic. Other scalar outputs are
redacted as a whole if their serialized form contains a credential.
Sensitive output fields are also redacted defensively. No raw input, output or
arguments are logged by this adapter.
