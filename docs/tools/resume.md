# resume

Resume a suspended debug thread or all threads of a debug target. Pass threadId (from wait_for_break) or applicationId. With no arguments, resumes the single active debug launch if exactly one exists.

## Parameters
| Parameter | Required | Type | Description |
| --- | --- | --- | --- |
| threadId | — | integer | Thread id from wait_for_break |
| applicationId | — | string | Application id (real or 'attach:<configName>' — resumes all threads of this target) |

---
*Generated from the live MCP server (`get_tool_guide`) by `docs/generate_tool_docs.py`. Edit the tool's Java source, not this file.*
