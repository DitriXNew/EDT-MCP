# step

Step a suspended debug thread. kind ∈ {over, into, out}. Blocks until the next SUSPEND event (or timeout) and returns the new frame snapshot.

## Parameters
| Parameter | Required | Type | Description |
| --- | --- | --- | --- |
| threadId | yes | integer | Thread id from wait_for_break (required) |
| kind | yes | string (one of: over, into, out) | Step kind: over, into, out (required) |
| timeout | — | integer | Wait window in seconds (default: 30) |

---
*Generated from the live MCP server (`get_tool_guide`) by `docs/generate_tool_docs.py`. Edit the tool's Java source, not this file.*
