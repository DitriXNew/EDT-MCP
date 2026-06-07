# list_subsystems

List 1C subsystems of a configuration as a flat table (FQN, Synonym, Comment, InCommandInterface, content count, children count). Walks the whole tree by default (recursive=true); use get_subsystem_content for one subsystem's objects. Full parameters and examples: call get_tool_guide('list_subsystems').

## Parameters
| Parameter | Required | Type | Description |
| --- | --- | --- | --- |
| projectName | yes | string | EDT project name (required) |
| nameFilter | — | string | Case-insensitive partial match on Name only (not Synonym) |
| recursive | — | boolean | Include nested subsystems (default: true) |
| limit | — | integer | Max results (default from preferences: 100, max 1000) |
| language | — | string | Synonym language code, e.g. 'en'/'ru' (default: configuration default) |

## Guide
List the subsystems of a configuration as a flat table. Each row carries the subsystem FQN, its synonym in the chosen language, comment, the IncludeInCommandInterface flag, the number of objects in the subsystem (content count) and the number of direct child subsystems (children count).

## When to use
- Get a configuration-wide map of subsystems before drilling into one.
- Find a subsystem by a partial Name (`nameFilter`).
- To inspect the objects inside a specific subsystem, follow up with `get_subsystem_content` using the FQN from this table.

## Parameters
- `projectName` (required) - EDT project name.
- `nameFilter` - case-insensitive substring matched against the subsystem **Name only**, not its Synonym. Omit to list everything.
- `recursive` - default `true`: walk the whole tree so the AI sees every subsystem at once. Set `false` to list only top-level subsystems.
- `limit` - max rows returned; default from preferences (100), clamped to 1000. A truncation notice is appended when results are capped.
- `language` - language code for the Synonym column (e.g. `en`, `ru`). Defaults to the configuration's default language.

## FQN format
Top-level: `Subsystem.Sales`. Nested subsystems repeat the segment: `Subsystem.Sales.Subsystem.Orders`. These FQNs are the IDs you pass to `get_subsystem_content`.

## Examples
- Whole tree: `{projectName: "MyProject"}`.
- Top-level only: `{projectName: "MyProject", recursive: false}`.
- Filter by name: `{projectName: "MyProject", nameFilter: "Sales"}`.
- Russian synonyms: `{projectName: "MyProject", language: "ru"}`.

## Notes & gotchas
- `nameFilter` matches the programmatic Name, never the localized synonym; searching by a translated caption will not match.
- The Synonym column is rendered for `language` only; an unconfigured language yields an empty synonym, not an error.
- Output is Markdown; table cells are escaped so `|` in a comment/synonym does not break the table.

---
*Generated from the live MCP server (`get_tool_guide`) by `docs/generate_tool_docs.py`. Edit the tool's Java source, not this file.*
