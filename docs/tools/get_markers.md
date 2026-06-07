# get_markers

List workspace markers: bookmarks and/or task markers (TODO, FIXME, XXX, HACK). Filter by markerKind (bookmark | task; omit to list both), projectName, filePath substring, and - for task markers only - priority. Returns a markdown table of Kind, Type, Priority, Message, Path and Line.

## Parameters
| Parameter | Required | Type | Description |
| --- | --- | --- | --- |
| projectName | — | string | Filter by project name (optional) |
| filePath | — | string | Filter by file path substring (optional) |
| markerKind | — | string (one of: bookmark, task) | Which markers to list: 'bookmark' (manual navigation bookmarks) or 'task' (TODO/FIXME/XXX/HACK code markers). Omit to list both. |
| priority | — | string (one of: high, normal, low) | Filter task markers by priority (optional). Applies to task markers only; bookmarks have no priority and are unaffected. Cannot be combined with markerKind=bookmark. |
| limit | — | integer | Maximum number of results (default: 100, max: 1000) |

---
*Generated from the live MCP server (`get_tool_guide`) by `docs/generate_tool_docs.py`. Edit the tool's Java source, not this file.*
