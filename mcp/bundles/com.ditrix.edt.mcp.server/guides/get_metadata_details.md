Return the detailed properties of one or more 1C metadata objects. By default you get a compact basic view; with `full: true` every reflected section (attributes, tabular sections, forms, commands, and other reflected properties) is rendered.

## When to use
- After `get_metadata_objects` (or any tool that gave you a Type.Name), to inspect a specific object's structure.
- Batch several objects in one call by passing multiple FQNs in `objectFqns`.
- Prefer the default (basic) view first; reach for `full: true` only when you need the exhaustive reflection.
- Pass `assignable: true` to get the SETTABLE-property schema instead of the details view: per property its value kind, current value, and ALLOWED values (enum literals) - exactly what modify_metadata can set. In this mode an FQN may address a member (e.g. `Catalog.Products.Attribute.Weight`), not just a top object.
- Pass a FORM FQN (`Catalog.Products.Form.ItemForm` or `CommonForm.MyForm`) to render that form's enriched STRUCTURE: its items (the nested visual tree, with each item's visibility, bound `dataPath` and per-kind extras), an attributes table (with the `Main`/`SavedData` flags), a commands table and an Event handlers section. (For a CommonForm this renders the form structure, not the CommonForm's mdclass properties.) Form members are created/edited/removed by their own FQNs via create_metadata / modify_metadata / delete_metadata.

## Parameter details
- `projectName` (required) - EDT project name.
- `objectFqns` (required) - array of fully-qualified names in `Type.Name` form, e.g. `Catalog.Products`, `Document.SalesOrder`. Only the **Type** token may be bilingual: the English or Russian, singular or plural type is accepted (e.g. `Справочник.Products` resolves the same as `Catalog.Products`). The **Name** part is the programmatic object Name, never the synonym.
- `full` - `true` returns every reflected section, `false` (default) returns only key info. In full mode each section is capped and a `[truncated]` row marks omitted rows.
- `roleObjectOffset` - for a ROLE FQN only: 0-based object offset into the rights matrix. The default (non-`full`) view shows only the first 100 authored objects; page past them by passing the next offset (the matrix notice tells you the exact value), or use `full: true` (which renders every object, capped at 1000). Ignored in `full` mode.
- `language` - language **code** (`en`/`ru`) used for the synonym columns. Defaults to the configuration's default language; the synonym map is keyed by code, not by the language's display name.

## Output
- Markdown, one section per resolved object, separated by `---`.
- A form FQN renders the enriched form structure instead of an mdclass object section: an Items outline (each item with its visibility, bound `dataPath` and per-kind extras), an Attributes table (with the `Main`/`SavedData` columns), a Commands table and an Event handlers section.
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
