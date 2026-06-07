# revalidate_objects

Revalidate EDT project or specific objects. If objects array is empty or missing, revalidates entire project. FQN examples: 'Document.SalesOrder', 'Catalog.Products', 'CommonModule.Common'. Russian type names are also supported (e.g. 'Документ.ПриходнаяНакладная', 'Справочник.Номенклатура').

## Parameters
| Parameter | Required | Type | Description |
| --- | --- | --- | --- |
| projectName | yes | string | EDT project name (required) |
| objects | — | array | FQNs to revalidate (e.g. ['Document.SalesOrder']). Russian type names supported (e.g. 'Документ.ПродажаТоваров'). Empty array = full project revalidation |

---
*Generated from the live MCP server (`get_tool_guide`) by `docs/generate_tool_docs.py`. Edit the tool's Java source, not this file.*
