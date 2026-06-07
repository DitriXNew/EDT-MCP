# start_profiling

Start performance measurement on the active debug target. Enables line-level profiling: call counts and timing for every executed BSL line. Start-only and idempotent: if profiling is already active for this applicationId it stays on. Call stop_profiling to stop, then get_profiling_results to see which code was covered. Requires an active debug session (debug_launch or debug_yaxunit_tests).

## Parameters
| Parameter | Required | Type | Description |
| --- | --- | --- | --- |
| applicationId | yes | string | Application id of the running debug session (required) |

---
*Generated from the live MCP server (`get_tool_guide`) by `docs/generate_tool_docs.py`. Edit the tool's Java source, not this file.*
