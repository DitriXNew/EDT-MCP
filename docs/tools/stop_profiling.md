# stop_profiling

Stop performance measurement on the active debug target. Counterpart to start_profiling: deterministically switches profiling off. Idempotent: if profiling is not active for this applicationId it returns a benign result, not an error. Call get_profiling_results afterwards to retrieve the collected coverage.

## Parameters
| Parameter | Required | Type | Description |
| --- | --- | --- | --- |
| applicationId | yes | string | Application id of the running debug session (required) |

---
*Generated from the live MCP server (`get_tool_guide`) by `docs/generate_tool_docs.py`. Edit the tool's Java source, not this file.*
