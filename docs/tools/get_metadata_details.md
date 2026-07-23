# get_metadata_details

Get detailed properties of one or more 1C metadata objects (basic info by default, or every reflected section with 'full: true'). Use it after get_metadata_objects to inspect a known object's attributes/forms/commands; in full mode each section is capped so request fewer FQNs to keep the response small. A FORM FQN ('Catalog.X.Form.ItemForm' or 'CommonForm.Name') renders that form's STRUCTURE (items / attributes / commands). A ROLE FQN ('Role.FullAccess') renders that role's ACCESS RIGHTS - the object->right matrix, RLS restrictions, RLS templates and the role properties ('full: true' shows every object, otherwise only the non-default rows, the first 100 by default - page past them with 'roleObjectOffset' or use 'full: true'). Use this for the full properties of one named object; to list objects by type use get_metadata_objects. Full parameters and examples: call get_tool_guide('get_metadata_details').

## Parameters
| Parameter | Required | Type | Description |
| --- | --- | --- | --- |
| projectName | yes | string | EDT project name (required) |
| objectFqns | yes | array | Required. FQNs as Type.Name, e.g. ['Catalog.Products', 'Document.SalesOrder']; Russian type tokens also work (e.g. 'Справочник.Products'). |
| full | — | boolean | All reflected properties (true) or only key info (false). Default: false |
| roleObjectOffset | — | integer | For a ROLE FQN only: 0-based object offset into the rights matrix, for paging past the first 100 authored objects in the default (non-full) view (default: 0; ignored when 'full: true', which renders every object up to 1000). Use it (or 'full: true') to read a role that authors more than 100 objects. |
| assignable | — | boolean | Instead of the details view, return the ASSIGNABLE-property schema (default false): per property its value kind, current value and ALLOWED values (enum literals). This is what modify_metadata can set; FQNs may address members (e.g. 'Catalog.Products.Attribute.Weight'). |
| language | — | string | Synonym language code, e.g. 'en'/'ru' (default: configuration default) |

## Guide
Return the detailed properties of one or more 1C metadata objects. By default you get a compact basic view; with `full: true` every reflected section (attributes, tabular sections, forms, commands, and other reflected properties) is rendered.

## When to use
- After `get_metadata_objects` (or any tool that gave you a Type.Name), to inspect a specific object's structure.
- Batch several objects in one call by passing multiple FQNs in `objectFqns`.
- Prefer the default (basic) view first; reach for `full: true` only when you need the exhaustive reflection.
- Pass `assignable: true` to get the SETTABLE-property schema instead of the details view: per property its value kind, current value, and ALLOWED values (enum literals) - exactly what modify_metadata can set. In this mode an FQN may address a member (e.g. `Catalog.Products.Attribute.Weight`), not just a top object.
- Pass a FORM FQN (`Catalog.Products.Form.ItemForm` or `CommonForm.MyForm`) to render that form's enriched STRUCTURE: its items (the nested visual tree, with each item's visibility, bound `dataPath` and per-kind extras), an attributes table (with the `Main`/`SavedData` flags), a commands table and an Event handlers section. (For a CommonForm this renders the form structure, not the CommonForm's mdclass properties.) Form members are created/edited/removed by their own FQNs via create_metadata / modify_metadata / delete_metadata.
- Pass a TEMPLATE FQN (`CommonTemplate.Name` or `Report.X.Template.Name` / `DataProcessor.X.Template.Name`) whose content is a Data Composition Schema (СКД / a `.dcs` resource) to render the schema's STRUCTURE instead of the owner's basic info: data sources; data sets (name, kind - query/object/union - the FULL query text in a fenced code block for a query data set, and a fields table with data path/source field/title/role); calculated fields and total fields (data path/expression/title or groups); parameters (name, title, value type, value, use); and the DEFAULT settings variant's selection/filter/order (as nested bullet outlines - a filter's AND/OR/NOT groups nest, and a disabled item is flagged `[not used]`). Every section is skipped when empty. A template whose content is NOT a Data Composition Schema (e.g. a SpreadsheetDocument print form) renders its basic info as usual - this only activates for a genuine СКД template. This is a READ-ONLY view; there is no tool yet to author the default settings variant (data sources/data sets/parameters are authored via `modify_metadata`'s `dcs` payload).

## Parameter details
- `projectName` (required) - EDT project name.
- `objectFqns` (required) - array of fully-qualified names in `Type.Name` form, e.g. `Catalog.Products`, `Document.SalesOrder`. Only the **Type** token may be bilingual: the English or Russian, singular or plural type is accepted (e.g. `Справочник.Products` resolves the same as `Catalog.Products`). The **Name** part is the programmatic object Name, never the synonym.
- `full` - `true` returns every reflected section, `false` (default) returns only key info. In full mode each section is capped and a `[truncated]` row marks omitted rows.
- `roleObjectOffset` - for a ROLE FQN only: 0-based object offset into the rights matrix. The default (non-`full`) view shows only the first 100 authored objects; page past them by passing the next offset (the matrix notice tells you the exact value), or use `full: true` (which renders every object, capped at 1000). Ignored in `full` mode.
- `language` - language **code** (`en`/`ru`) used for the synonym columns. Defaults to the configuration's default language; the synonym map is keyed by code, not by the language's display name.

## Output
- Markdown, one section per resolved object, separated by `---`.
- A form FQN renders the enriched form structure instead of an mdclass object section: an Items outline (each item with its visibility, bound `dataPath` and per-kind extras), an Attributes table (with the `Main`/`SavedData` columns), a Commands table and an Event handlers section.
- A template FQN whose content is a Data Composition Schema renders the schema's structure (see above) instead of an mdclass object section, headed `# Data Composition Schema: <fqn>`.
- Per-object failures (malformed FQN or object not found) do NOT fail the whole call. They are collected into a dedicated `## Errors` table at the end with an `ERROR` status row carrying the FQN and reason, so a client can tell a failed object from data.

## Examples
- Basic, one object: `{projectName: "MyProject", objectFqns: ["Catalog.Products"]}`.
- Full details, several objects: `{projectName: "MyProject", objectFqns: ["Catalog.Products", "Document.SalesOrder"], full: true}`.
- Russian type token + Russian synonyms: `{projectName: "MyProject", objectFqns: ["Справочник.Products"], language: "ru"}`.
- A report's Data Composition Schema template: `{projectName: "MyProject", objectFqns: ["Report.Sales.Template.ОсновнаяСхемаКомпоновкиДанных"]}`.

## Notes & gotchas
- Only the type token is bilingual; the object Name must match the programmatic Name, not a translated synonym.
- `full: true` over many FQNs can be large; even capped sections add up - request fewer FQNs to keep the response small.
- An unconfigured `language` yields empty synonyms, not an error.
- A malformed FQN (no `.`) is reported as `Invalid FQN`; a well-formed but unknown one as `Object not found` - both in the `## Errors` table, never as prose in the body.
- The DCS template render requires the object's own template FQN (`Report.X.Template.Name`, `CommonTemplate.Name`); the owning Report's FQN alone (`Report.X`) still shows only the report's basic info (it does not enumerate the schema's content).

---
*Generated from the live MCP server (`get_tool_guide`) by `docs/generate_tool_docs.py`. Do not edit this file. Edit the tool's description/schema in its Java source and its guide body in `mcp/bundles/com.ditrix.edt.mcp.server/guides/<tool>.md`.*
