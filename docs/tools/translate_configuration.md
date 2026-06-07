# translate_configuration

Run EDT 'Translate configuration' on a configuration project - reads the dictionaries from the storages bound to it (external dictionary storage projects with the dependentProjectNature, or in-configuration storages) and regenerates the translated artifacts. Equivalent of the context-menu action Translation -> Translate configuration. Requires LanguageTool installed in EDT.

## Parameters
| Parameter | Required | Type | Description |
| --- | --- | --- | --- |
| projectName | yes | string | Project name (typically the source, e.g. the ru project). Required. |
| targetLanguages | yes | array | Target language codes to synchronize (e.g. ["en"]). Required. |

---
*Generated from the live MCP server (`get_tool_guide`) by `docs/generate_tool_docs.py`. Edit the tool's Java source, not this file.*
