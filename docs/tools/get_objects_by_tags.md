# get_objects_by_tags

Get metadata objects filtered by tags. Returns objects that have any of the specified tags, including tag descriptions and object FQNs.

## Parameters
| Parameter | Required | Type | Description |
| --- | --- | --- | --- |
| projectName | yes | string | EDT project name (required) |
| tags | yes | array | Array of tag names to filter by (e.g. ['Important', 'NeedsReview']). Returns objects that have ANY of these tags. Required. |
| limit | — | integer | Maximum number of objects to return per tag. Default: 100 |

---
*Generated from the live MCP server (`get_tool_guide`) by `docs/generate_tool_docs.py`. Edit the tool's Java source, not this file.*
