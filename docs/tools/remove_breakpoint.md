# remove_breakpoint

Remove a 1C BSL line breakpoint. Either pass breakpointId (returned from set_breakpoint) or projectName+module+lineNumber to look it up by coordinates.

## Parameters
| Parameter | Required | Type | Description |
| --- | --- | --- | --- |
| breakpointId | — | integer | Marker id returned by set_breakpoint |
| projectName | — | string | EDT project name (when looking up by coordinates) |
| modulePath | — | string | EDT module path or absolute path (when looking up by coordinates) |
| module | — | string | Legacy alias for modulePath (deprecated) |
| lineNumber | — | integer | 1-based line number (when looking up by coordinates) |

---
*Generated from the live MCP server (`get_tool_guide`) by `docs/generate_tool_docs.py`. Edit the tool's Java source, not this file.*
