# get_tool_guide

Get the full on-demand how-to for a tool: its description, every parameter (type, required, allowed values) and extended examples/preconditions kept OUT of the always-loaded tool list to save context. Pass the exact tool name from tools/list. Also available as the resource guide://<toolName>.

## Parameters
| Parameter | Required | Type | Description |
| --- | --- | --- | --- |
| toolName | yes | string | The exact name of the tool to document, as it appears in tools/list (e.g. 'read_module_source'). |

## Guide
Use this when the lean tools/list entry is not enough — it returns the same guide a client gets from the resource guide://<toolName>. Pass the exact tool name (run it on itself: toolName=get_tool_guide) to see a worked example. An unknown name returns an error pointing back to tools/list.

---
*Generated from the live MCP server (`get_tool_guide`) by `docs/generate_tool_docs.py`. Edit the tool's Java source, not this file.*
