# get_translation_project_info

Return LanguageTool metadata for a project: the translation storages declared on it and the available translation provider IDs. Use it to check whether a dictionary storage is attached before translating; an empty storages list means none is attached yet (set up manually in EDT). Requires EDT with LanguageTool installed. Full parameters and examples: call get_tool_guide('get_translation_project_info').

## Parameters
| Parameter | Required | Type | Description |
| --- | --- | --- | --- |
| projectName | yes | string | EDT project name (required) |

## Guide
Reports the LanguageTool (translation) setup for one EDT project so you can decide whether translation is configured before calling other translation tools (e.g. `generate_translation_strings`, `translate_configuration`). Output is Markdown with a `## Storages` list and a `## Translation providers` list, plus front-matter counts (`storagesCount`, `providersCount`).

## When to use
- Before translating: confirm a dictionary storage is attached and which providers (Google, Microsoft, Yandex, history, etc.) are available.
- To diagnose why a translation tool reports no storage: an empty `## Storages` list means the configuration has no dictionary storage attached yet.

## Parameter details
- `projectName` (required) - the EDT project name. Must be an open EDT project; an unknown or closed name returns `Project not found or closed`, and a plain (non-EDT) project returns `Not an EDT project`.

## Storage IDs
Storage IDs look like `edit:default`, `dictionary:common-camelcase`, `dictionary:common`, `context:model`, `context:interface`. The exact set depends on what is attached to the configuration. Each storage and provider id is rendered VERBATIM in backticks so you can copy it straight into `generate_translation_strings` (a storage id -> its `storageId`, a provider id -> its `providerId`) without scraping the prose.

## Attaching a storage (manual, no MCP tool)
If the storages list is empty, a storage must be set up by the user in EDT - there is no MCP tool for it:
1. Create a plain Eclipse project (File -> New -> Project -> General -> Project).
2. Attach it to the configuration via the configuration project's properties (Translation settings).

The configuration itself can also act as its own storage if no separate Eclipse project is desired.

## Gotchas
- Do NOT use any '1C:Enterprise -> Dependent translation project' wizard - the dictionary storage project must be a plain Eclipse project.
- Requires EDT with LanguageTool installed; without it the tool returns `LanguageTool IProjectInformationApi is not available`.

## Example
`{projectName: "MyConfiguration"}`

---
*Generated from the live MCP server (`get_tool_guide`) by `docs/generate_tool_docs.py`. Edit the tool's Java source, not this file.*
