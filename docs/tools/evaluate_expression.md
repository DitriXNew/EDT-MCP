# evaluate_expression

Evaluate a BSL expression in the context of a suspended stack frame. Pass frameRef from wait_for_break and the expression text. WARNING: this executes arbitrary BSL code in the running 1C application.

## Parameters
| Parameter | Required | Type | Description |
| --- | --- | --- | --- |
| frameRef | yes | integer | Stable frame reference from wait_for_break (required) |
| expression | yes | string | BSL expression to evaluate (required) |

---
*Generated from the live MCP server (`get_tool_guide`) by `docs/generate_tool_docs.py`. Edit the tool's Java source, not this file.*
