List the metadata objects of a 1C configuration as a flat Markdown table. Each row carries the object Name, its Synonym in the chosen language, Comment, the metadata Type, and two flags - ObjectModule and ManagerModule - that show whether the object owns the corresponding module (Yes/-).

## When to use
- Discover what objects exist in a configuration before drilling into one.
- Find an object by a partial Name (`nameFilter`) or narrow to a single kind (`metadataType`).
- To inspect one object's attributes/forms/etc., follow up with `get_metadata_details` using the Name and Type from this table.

## Parameter details
- `projectName` (required) - EDT project name.
- `metadataType` - which kind to list; default `all`. Matching is case-insensitive, and it is a single string, not an array. Accepts `all` or any standard metadata type name - the FQN token in English or Russian, singular or plural (e.g. `ScheduledJob`, `Role`, `HTTPServices`, `Справочник`). Legacy English plural tokens such as `documents`, `commonModules`, and `xdtoPackages` remain accepted. An unrecognized value returns an error naming the bad value and listing every available configuration type. There is no `types` array parameter.
- `nameFilter` - case-insensitive substring matched against the object **Name only**, never the Synonym. Omit to list everything of the chosen type.
- `limit` - max rows returned; default from preferences (100), clamped to 1000. A truncation notice is appended when results are capped, while **Total** still reports the full count.
- `language` - language code for the Synonym column (e.g. `en`, `ru`). Defaults to the configuration's default language.

## Columns
- `Name` - the programmatic object name (use this with other tools).
- `Synonym` - localized caption for the chosen `language`.
- `Comment` - the object's comment, if any.
- `Type` - e.g. `Document`, `Catalog`, `InformationRegister`, `CommonModule`, `Enum`.
- `ObjectModule` - `Yes` if the object owns a body module - an object, record-set, value-manager or command module, or the plain module of a common module / HTTP / web service - else `-`.
- `ManagerModule` - `Yes` if the object has a manager module, else `-`.

## External-objects projects
A project with the external-objects nature holds no configuration: its roots are its own
external data processors and reports. This tool answers about THAT project, with its own
two-entry vocabulary - `all`, `externalDataProcessors`, `externalReports`, or the type name
itself (`ExternalDataProcessor` / `ExternalReport`, English or Russian). A configuration
category asked of such a project is refused, naming what the project does hold, rather than
answered from the base configuration it is linked to.

## Examples
- Everything: `{projectName: "MyProject"}`.
- Only documents: `{projectName: "MyProject", metadataType: "documents"}`.
- Only scheduled jobs, via the type-name token: `{projectName: "MyProject", metadataType: "ScheduledJob"}` (equivalent to `metadataType: "scheduledJobs"`).
- Only XDTO packages: `{projectName: "MyProject", metadataType: "xdtoPackages"}` (or the type name `XDTOPackage` / `ПакетXDTO`). This is how you get the `XDTOPackage.<Name>` FQN the XDTO tools need - `create_metadata` / `modify_metadata` / `delete_metadata` on a package member, and `validate_xdto_package`.
- Filter by name: `{projectName: "MyProject", nameFilter: "Order"}`.
- Russian synonyms: `{projectName: "MyProject", language: "ru"}`.

## Notes & gotchas
- `nameFilter` matches the programmatic Name, never the localized synonym; searching by a translated caption will not match.
- `Subsystem` lists top-level roots only. Nested subsystems are not directly addressable as `Subsystem.<Name>`; use `list_subsystems` for the complete tree and its paths.
- The Synonym is keyed by language **code** (`en`/`ru`), not the language's display name; an unconfigured language yields an empty synonym, not an error.
- Output is Markdown; table cells are escaped so a `|` in a comment or synonym does not break the table.
