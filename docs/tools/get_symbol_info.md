# get_symbol_info

Get type/hover info about a symbol at a position in a BSL module. Returns inferred types, signatures, and documentation.

## Parameters
| Parameter | Required | Type | Description |
| --- | --- | --- | --- |
| projectName | yes | string | EDT project name |
| modulePath | — | string | BSL module path from src/, e.g. 'CommonModules/MyModule/Module.bsl' (canonical; alias: filePath) |
| filePath | — | string | Deprecated alias for modulePath |
| line | yes | integer | Line number (1-based) |
| column | yes | integer | Column number (1-based) |

---
*Generated from the live MCP server (`get_tool_guide`) by `docs/generate_tool_docs.py`. Edit the tool's Java source, not this file.*
