# read_method_source

Read a specific procedure/function from a BSL module by name. Returns source code with metadata. Lists available methods if not found. Use this for one method body; to read the whole module source use read_module_source.

## Parameters
| Parameter | Required | Type | Description |
| --- | --- | --- | --- |
| projectName | yes | string | EDT project name |
| modulePath | yes | string | Path from src/, e.g. 'CommonModules/MyModule/Module.bsl' |
| methodName | yes | string | Procedure/function name (case-insensitive) |

---
*Generated from the live MCP server (`get_tool_guide`) by `docs/generate_tool_docs.py`. Edit the tool's Java source, not this file.*
