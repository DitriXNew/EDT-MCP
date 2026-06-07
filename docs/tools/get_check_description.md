# get_check_description

Get detailed description of an EDT check by its ID. Returns markdown content with check explanation, examples, and how to fix. Accepts the symbolic check id OR the short UID code shown by get_project_errors (pass projectName so the UID can be resolved). Requires a configured check-descriptions folder (MCP preferences); without it the tool returns a configuration error.

## Parameters
| Parameter | Required | Type | Description |
| --- | --- | --- | --- |
| checkId | yes | string | Check id: the symbolic dash-cased id (e.g. 'begin-transaction', 'ql-temp-table-index') OR the short UID code from get_project_errors (e.g. 'SU23'); a UID is resolved when projectName is also supplied. Precondition: a check-descriptions folder must be configured in MCP preferences, else the tool returns a configuration error. |
| projectName | — | string | Optional EDT project name. Required only to resolve a short UID checkId (e.g. 'SU23') to its symbolic id; ignored when checkId is already symbolic. |

---
*Generated from the live MCP server (`get_tool_guide`) by `docs/generate_tool_docs.py`. Edit the tool's Java source, not this file.*
