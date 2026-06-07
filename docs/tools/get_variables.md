# get_variables

Read variables from a stack frame of a suspended debug thread. Pass frameRef from wait_for_break (preferred) or threadId+frameIndex. Use expandPath to drill into nested structures (dot-separated).

## Parameters
| Parameter | Required | Type | Description |
| --- | --- | --- | --- |
| frameRef | — | integer | Stable frame reference returned from wait_for_break |
| threadId | — | integer | Thread id (alternative to frameRef) |
| frameIndex | — | integer | 0-based frame index when using threadId |
| expandPath | — | string | Dot-separated path to expand a nested variable |

---
*Generated from the live MCP server (`get_tool_guide`) by `docs/generate_tool_docs.py`. Edit the tool's Java source, not this file.*
