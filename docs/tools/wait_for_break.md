# wait_for_break

Wait for a debug suspend event (e.g. breakpoint hit) on the given application. Returns the suspended thread/frame snapshot, or {hit:false} on timeout. applicationId may be real or synthetic 'attach:<configName>'. If omitted and exactly one EDT debug launch is active, that launch is used. Does NOT terminate the launch on timeout — call again to keep waiting.

## Parameters
| Parameter | Required | Type | Description |
| --- | --- | --- | --- |
| applicationId | — | string | Application id of the running debug session (real or 'attach:<configName>'). Optional if exactly one debug launch is active. |
| timeout | — | integer | Wait window in seconds (default: 60) |

---
*Generated from the live MCP server (`get_tool_guide`) by `docs/generate_tool_docs.py`. Edit the tool's Java source, not this file.*
