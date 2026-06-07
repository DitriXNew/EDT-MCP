# enable_toolset

Reveal (or hide) tool groups for progressive disclosure. Pass toolsets=[ids] from list_toolsets to reveal them, then RE-REQUEST tools/list to see the newly revealed tools. Set disable=true to hide. The 'core' toolset is always visible and cannot be toggled. When progressive disclosure is off, all tools are already listed and this has no effect until you enable it in EDT Preferences → MCP Server.

## Parameters
| Parameter | Required | Type | Description |
| --- | --- | --- | --- |
| toolsets | yes | array | Toolset ids to reveal (or hide with disable=true), e.g. ["code","debug"]. Call list_toolsets for the valid ids. |
| disable | — | boolean | Hide the listed toolsets instead of revealing them (default false). |

---
*Generated from the live MCP server (`get_tool_guide`) by `docs/generate_tool_docs.py`. Edit the tool's Java source, not this file.*
