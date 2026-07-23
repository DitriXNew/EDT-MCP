# create_project

Create a NEW 1C project in the EDT workspace. projectKind selects the kind: 'configuration' (standalone), 'extension' (bound to a base configuration), or 'externalObjects' (external data processors/reports). The name must not already exist as a project. standardChecks/commonChecks are applied only when com.e1c.v8codestyle is installed. Full parameters and examples: call get_tool_guide('create_project').

## Parameters
| Parameter | Required | Type | Description |
| --- | --- | --- | --- |
| projectKind | yes | string (one of: configuration, extension, externalObjects) | Kind of project to create (required): 'configuration' = standalone 1C configuration; 'extension' = configuration extension bound to a base project; 'externalObjects' = external data processors/reports project. |
| name | yes | string | Name of the new Configuration object (required). For configuration/extension: the programmatic Configuration name. For externalObjects: used as the default project name (no Configuration object exists). Must be a valid 1C identifier: starts with a letter or underscore, then letters, digits and underscores only (Cyrillic allowed). Also used as the default EDT project name if projectName is not supplied. |
| projectName | — | string | EDT workspace project name to create. Default: extension -> '<baseProjectName>.<name>'; configuration/externalObjects -> 'name'. |
| version | — | string | Platform version string, e.g. '8.3.27' (configuration and externalObjects only; for extension: REJECTED — version is always inherited from the base configuration). Default: Version.LATEST when omitted. |
| baseProjectName | — | string | Name of the BASE configuration EDT project (required for extension; REJECTED for configuration and externalObjects). Must be an existing, open V8 configuration project. Use list_projects to find it. |
| prefix | — | string | NamePrefix for the extension (extension only; REJECTED for other kinds). Default: empty string. The wizard generates a value like 'Ext1_'; pass an explicit value or omit for empty. |
| purpose | — | string (one of: Customization, AddOn, Patch) | Extension purpose (extension only; REJECTED for other kinds). Default: Customization. Customization = user adaptation; AddOn = add-on functionality; Patch = hotfix. |
| compatibilityMode | — | string | Optional extension compatibility-mode string matching a CompatibilityMode enum literal (e.g. 'Version8_3_10'); empty = factory default. Unknown values are rejected. Extension only; REJECTED for other kinds. |
| synonym | — | string | Human-readable synonym for the Configuration (configuration and extension only; REJECTED for externalObjects). Defaults to the 'name' value if omitted. |
| comment | — | string | Optional free-text comment set on the Configuration (configuration and extension only; REJECTED for externalObjects). |
| scriptVariant | — | string (one of: Russian, English) | Script variant (Russian or English). configuration: sets the Configuration scriptVariant and default Language code (default Russian). externalObjects: applied post-create via setScriptVariant (non-fatal on failure). extension: REJECTED — scriptVariant is always inherited from the base configuration. |
| standardChecks | — | boolean | Enable 1C:Standards BSL checks for the new project (default true). Applied to all kinds only when com.e1c.v8codestyle is installed; ignored otherwise. |
| commonChecks | — | boolean | Enable common (project-level) BSL checks for the new project (default true). Applied to all kinds only when com.e1c.v8codestyle is installed; ignored otherwise. |
| autoSortTopObjects | — | boolean | Reserved for future use: auto-sort top-level metadata objects. Accepted but not yet applied in this release (see codestyle.autoSortNote in the response). |

## Guide
Creates a new 1C project in the EDT workspace. The `projectKind` parameter selects which
kind of project to create: a standalone configuration, a configuration extension bound to
a base project, or an external data processors/reports project.

## When to use

- **configuration** — create a brand-new standalone 1C configuration project. The project
  will contain an empty Configuration object with a default Language and be available
  immediately for metadata authoring via `create_metadata`.
- **extension** — create a configuration extension project bound to a base configuration.
  Use this to override metadata objects (`adopt_metadata_object`) or add BSL interceptors
  without modifying the base configuration.
- **externalObjects** — create an external data processors/reports project. The project
  will be empty and ready for adding external data processors or external reports via
  `create_metadata`.

The `name` must not already exist as a workspace project (the tool rejects duplicates).

## Parameter details

### Shared parameters (all kinds)

- **projectKind** (required): `configuration`, `extension`, or `externalObjects`.
- **name** (required): the Configuration object name (for configuration/extension) or the
  default project name (for externalObjects). Must be a valid 1C identifier.
- **projectName** (optional): the EDT workspace project name. Defaults to:
  - extension: `<baseProjectName>.<name>`
  - configuration/externalObjects: `<name>`
- **standardChecks** (optional, default `true`): enable 1C:Standards BSL checks. Only
  applied when `com.e1c.v8codestyle` is installed; otherwise ignored.
- **commonChecks** (optional, default `true`): enable common project-level BSL checks.
  Same condition as `standardChecks`.
- **autoSortTopObjects** (optional): accepted for API stability but **not yet applied** in
  this release. The response carries `codestyle.autoSortNote` to explain this.

### configuration kind

- **version** (optional): platform version string, e.g. `'8.3.27'`. Default: `Version.LATEST`.
- **synonym** (optional): human-readable synonym for the Configuration.
- **comment** (optional): free-text comment on the Configuration.
- **scriptVariant** (optional, `Russian` or `English`, default `Russian`): sets the
  Configuration scriptVariant and the default Language code (`ru`/`en`).

### extension kind

- **baseProjectName** (required): name of the base configuration project the extension
  extends. Must be an existing, open workspace project with V8ConfigurationNature.
- **prefix** (optional): NamePrefix for the extension (e.g. `MyExt_`). Defaults to empty.
- **synonym** (optional): human-readable synonym. Defaults to `name`.
- **comment** (optional): free-text comment.
- **purpose** (optional, default `Customization`): `Customization`, `AddOn`, or `Patch`.
- **compatibilityMode** (optional): a `CompatibilityMode` enum literal
  (e.g. `Version8_3_10`). Omit or pass empty for the factory default.
- **version**: REJECTED — extension always inherits the version from the base configuration.
- **scriptVariant**: REJECTED — extension always inherits scriptVariant from the base.

### externalObjects kind

- **version** (optional): platform version string. Default: `Version.LATEST`.
- **scriptVariant** (optional, `Russian` or `English`): applied post-create via
  `IExternalObjectProjectManager.setScriptVariant`. Non-fatal on failure (see
  `scriptVariantNote` in response).
- **baseProjectName**, **prefix**, **purpose**, **compatibilityMode**, **synonym**,
  **comment**: all REJECTED for this kind.

## Examples

Minimal configuration project:

```json
{"projectKind": "configuration", "name": "MyConfig"}
```

Configuration with version and scriptVariant:

```json
{
  "projectKind": "configuration",
  "name": "MyConfig",
  "version": "8.3.27",
  "scriptVariant": "Russian",
  "synonym": "My Configuration"
}
```

Extension project:

```json
{"projectKind": "extension", "name": "MyExt", "baseProjectName": "MyConfig"}
```

Extension with explicit project name and prefix:

```json
{
  "projectKind": "extension",
  "name": "MyExt",
  "baseProjectName": "MyConfig",
  "projectName": "MyConfig.MyExtension",
  "prefix": "Ext1_",
  "purpose": "Customization",
  "standardChecks": true,
  "commonChecks": true
}
```

External objects project:

```json
{"projectKind": "externalObjects", "name": "MyExternal"}
```

External objects with version:

```json
{"projectKind": "externalObjects", "name": "MyExternal", "version": "8.3.27", "scriptVariant": "Russian"}
```

## Workflow (extension)

1. `create_project` (kind=extension) — create the extension and bind it to the base.
2. `adopt_metadata_object` — adopt the objects/members you want to override.
3. `create_metadata` / `modify_metadata` on the adopted objects — extend the behavior.
4. `delete_project` — remove the extension project when it is no longer needed.

## Workflow (configuration)

1. `create_project` (kind=configuration) — create the empty configuration.
2. `create_metadata` — add metadata objects (Catalog, Document, CommonModule, etc.).
3. `write_module_source` — fill module BSL code.
4. `update_database` — start the first infobase from the configuration.

## Response fields

On success the response includes:
- `project` — the workspace project name (the round-trip key for sibling tools).
- `projectKind` — kind of project created.
- `name` — the Configuration name (configuration and extension only).
- `baseProject` — the base configuration project (extension only).
- `prefix` / `purpose` — extension-only attributes applied.
- `scriptVariant` — script variant applied or inherited.
- `version` — platform version used for project creation.
- `state` — `ready` when the lifecycle STARTED event was received (project is fully
  indexed); `created` when the timeout elapsed before STARTED.
- `codestyle` — `{applied: bool, note: string, autoSortNote: string}` reporting the
  v8codestyle preference write result.
- `synonymNote` — present only when the synonym (configuration/extension) could not be
  applied (no resolvable language code).
- `scriptVariantNote` — present only when the requested `scriptVariant` could not be
  applied for `externalObjects` (post-create `setScriptVariant` failed or was skipped).

## Gotchas

- **Project name must be new.** The call is rejected if a workspace project with the
  computed name already exists. Use a unique `name` or pass an explicit `projectName`.
- **Extension: base must be a configuration, not an extension.** Pass the BASE
  configuration's project name. Passing an extension project name returns an error.
- **After creation, wait for `state=ready`** before calling `adopt_metadata_object` or
  other project-dependent tools; the new project must complete lifecycle indexing.
  If `state=created` is returned, retry after a few seconds or call `revalidate_objects`.
- **autoSortTopObjects is not yet applied.** See `codestyle.autoSortNote` in the response.
- **Client-side timeouts.** Creation can take a couple of minutes on a busy workspace (the
  tool waits for the create operation and then for lifecycle STARTED). If your MCP client
  times out first, the creation usually still completes server-side — re-check with
  `list_projects` before retrying, or the retry will hit the duplicate-name guard.
- **language is mandatory for configuration projects.** The tool always creates a default
  Language (Russian/`ru` or English/`en` per `scriptVariant`). This matches the EDT wizard
  behavior; passing `null` would produce a project with validation errors.
- **externalObjects scriptVariant is post-create and non-fatal.** If `setScriptVariant`
  fails after lifecycle wait, the project is still created. Check `scriptVariantNote` in
  the response for details.

---
*Generated from the live MCP server (`get_tool_guide`) by `docs/generate_tool_docs.py`. Do not edit this file. Edit the tool's description/schema in its Java source and its guide body in `mcp/bundles/com.ditrix.edt.mcp.server/guides/<tool>.md`.*
