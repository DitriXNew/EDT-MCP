List the metadata objects of a 1C configuration as a flat Markdown table. Each row carries the object Name, its Synonym in the chosen language, Comment, the metadata Type, and two flags - ObjectModule and ManagerModule - that show whether the object owns the corresponding module (Yes/-).

## When to use
- Discover what objects exist in a configuration before drilling into one.
- Find an object by a partial Name (`nameFilter`) or narrow to a single kind (`metadataType`).
- To inspect one object's attributes/forms/etc., follow up with `get_metadata_details` using the Name and Type from this table.

## Parameter details
- `projectName` (required) - EDT project name.
- `metadataType` - which kind to list; default `all`. Matching is case-insensitive. Supported values: `all`, `documents`, `catalogs`, `informationRegisters`, `accumulationRegisters`, `commonModules`, `enums`, `constants`, `reports`, `dataProcessors`, `exchangePlans`, `businessProcesses`, `tasks`, `commonAttributes`, `eventSubscriptions`, `scheduledJobs`. An unknown value returns an error listing the supported types.
- `nameFilter` - case-insensitive substring matched against the object **Name only**, never the Synonym. Omit to list everything of the chosen type.
- `limit` - max rows returned; default from preferences (100), clamped to 1000. A truncation notice is appended when results are capped, while **Total** still reports the full count.
- `language` - language code for the Synonym column (e.g. `en`, `ru`). Defaults to the configuration's default language.

## Columns
- `Name` - the programmatic object name (use this with other tools).
- `Synonym` - localized caption for the chosen `language`.
- `Comment` - the object's comment, if any.
- `Type` - e.g. `Document`, `Catalog`, `InformationRegister`, `CommonModule`, `Enum`.
- `ObjectModule` - `Yes` if the object has an object/record-set/value-manager module, else `-`.
- `ManagerModule` - `Yes` if the object has a manager module, else `-`.

## Examples
- Everything: `{projectName: "MyProject"}`.
- Only documents: `{projectName: "MyProject", metadataType: "documents"}`.
- Filter by name: `{projectName: "MyProject", nameFilter: "Order"}`.
- Russian synonyms: `{projectName: "MyProject", language: "ru"}`.

## Notes & gotchas
- `nameFilter` matches the programmatic Name, never the localized synonym; searching by a translated caption will not match.
- The Synonym is keyed by language **code** (`en`/`ru`), not the language's display name; an unconfigured language yields an empty synonym, not an error.
- Output is Markdown; table cells are escaped so a `|` in a comment or synonym does not break the table.
