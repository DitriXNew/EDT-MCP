# set_breakpoint

Set a line breakpoint on a 1C BSL module. Accepts either an EDT module-relative path (e.g. 'CommonModules/Foo/Module.bsl') or an absolute filesystem path. Use wait_for_break afterwards to block until the breakpoint is hit.

## Parameters
| Parameter | Required | Type | Description |
| --- | --- | --- | --- |
| projectName | — | string | EDT project name (required when modulePath is module-relative) |
| modulePath | — | string | Module identifier — EDT module path (CommonModules/Foo/Module.bsl) or absolute file path (required) |
| module | — | string | Legacy alias for modulePath (deprecated) |
| lineNumber | yes | integer | 1-based line number (required) |

---
*Generated from the live MCP server (`get_tool_guide`) by `docs/generate_tool_docs.py`. Edit the tool's Java source, not this file.*
