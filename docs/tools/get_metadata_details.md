# get_metadata_details

Get detailed properties of one or more 1C metadata objects (basic info by default, or every reflected section with 'full: true'). Use it after get_metadata_objects to inspect a known object's attributes/forms/commands; in full mode each section is capped so request fewer FQNs to keep the response small. A FORM FQN ('Catalog.X.Form.ItemForm' or 'CommonForm.Name') renders that form's STRUCTURE (items / attributes / commands). Use this for the full properties of one named object; to list objects by type use get_metadata_objects. Full parameters and examples: call get_tool_guide('get_metadata_details').

## Parameters
| Parameter | Required | Type | Description |
| --- | --- | --- | --- |
| projectName | yes | string | EDT project name (required) |
| objectFqns | yes | array | Required. FQNs as Type.Name, e.g. ['Catalog.Products', 'Document.SalesOrder']; Russian type tokens also work (e.g. 'Справочник.Products'). |
| full | — | boolean | All reflected properties (true) or only key info (false). Default: false |
| assignable | — | boolean | Instead of the details view, return the ASSIGNABLE-property schema (default false): per property its value kind, current value and ALLOWED values (enum literals). This is what modify_metadata can set; FQNs may address members (e.g. 'Catalog.Products.Attribute.Weight'). |
| language | — | string | Synonym language code, e.g. 'en'/'ru' (default: configuration default) |

## Guide
Return the detailed properties of one or more 1C metadata objects. By default you get a compact basic view; with `full: true` every reflected section (attributes, tabular sections, forms, commands, and other reflected properties) is rendered.

## When to use
- After `get_metadata_objects` (or any tool that gave you a Type.Name), to inspect a specific object's structure.
- Batch several objects in one call by passing multiple FQNs in `objectFqns`.
- Prefer the default (basic) view first; reach for `full: true` only when you need the exhaustive reflection.
- Pass `assignable: true` to get the SETTABLE-property schema instead of the details view: per property its value kind, current value, and ALLOWED values (enum literals) - exactly what modify_metadata can set. In this mode an FQN may address a member (e.g. `Catalog.Products.Attribute.Weight`), not just a top object.
- Pass a FORM FQN (`Catalog.Products.Form.ItemForm` or `CommonForm.MyForm`) to render that form's STRUCTURE - its items (the nested visual tree), attributes and commands. (For a CommonForm this renders the form structure, not the CommonForm's mdclass properties.) Form members are created/edited/removed by their own FQNs via create_metadata / modify_metadata / delete_metadata.

## Parameter details
- `projectName` (required) - EDT project name.
- `objectFqns` (required) - array of fully-qualified names in `Type.Name` form, e.g. `Catalog.Products`, `Document.SalesOrder`. Only the **Type** token may be bilingual: the English or Russian, singular or plural type is accepted (e.g. `Справочник.Products` resolves the same as `Catalog.Products`). The **Name** part is the programmatic object Name, never the synonym.
- `full` - `true` returns every reflected section, `false` (default) returns only key info. In full mode each section is capped and a `[truncated]` row marks omitted rows.
- `language` - language **code** (`en`/`ru`) used for the synonym columns. Defaults to the configuration's default language; the synonym map is keyed by code, not by the language's display name.

## Output
- Markdown, one section per resolved object, separated by `---`.
- Per-object failures (malformed FQN or object not found) do NOT fail the whole call. They are collected into a dedicated `## Errors` table at the end with an `ERROR` status row carrying the FQN and reason, so a client can tell a failed object from data.

## Examples
- Basic, one object: `{projectName: "MyProject", objectFqns: ["Catalog.Products"]}`.
- Full details, several objects: `{projectName: "MyProject", objectFqns: ["Catalog.Products", "Document.SalesOrder"], full: true}`.
- Russian type token + Russian synonyms: `{projectName: "MyProject", objectFqns: ["Справочник.Products"], language: "ru"}`.

## Notes & gotchas
- Only the type token is bilingual; the object Name must match the programmatic Name, not a translated synonym.
- `full: true` over many FQNs can be large; even capped sections add up - request fewer FQNs to keep the response small.
- An unconfigured `language` yields empty synonyms, not an error.
- A malformed FQN (no `.`) is reported as `Invalid FQN`; a well-formed but unknown one as `Object not found` - both in the `## Errors` table, never as prose in the body.

---
*Generated from the live MCP server (`get_tool_guide`) by `docs/generate_tool_docs.py`. Edit the tool's Java source, not this file.*
