Wraps the EDT "New Extension" project-creation wizard. Creates a brand-new extension
project in the workspace, bound to a base configuration, ready for metadata adoption.

## When to use

- You need a configuration extension project to override metadata objects (use
  `adopt_metadata_object` after creation) or add BSL interceptors.
- The `name` must not already exist as a workspace project (the tool rejects duplicates).
- The base project must be an open, V8 configuration project (not an extension).

## Parameter details

- **name** (required): Configuration object name for the new extension. Also used as the
  default EDT project name when `projectName` is omitted. Must be a valid 1C identifier.
- **baseProjectName** (required): name of the base configuration project the extension will
  extend. Must be an existing, open workspace project with V8ConfigurationNature. Use
  `list_projects` to see what is available.
- **projectName** (optional): the EDT workspace project name. Defaults to
  `<baseProjectName>.<name>` when omitted (matching the wizard behavior).
- **prefix** (optional): NamePrefix for the extension (e.g. `MyExt_`). The 1C platform uses
  this to namespace adopted object members. Defaults to empty string.
- **synonym** (optional): human-readable synonym stored in the Configuration. Defaults to
  `name` when omitted.
- **comment** (optional): free-text comment set on the Configuration.
- **purpose** (optional, default `Customization`): extension purpose.
  - `Customization` — user-specific customization (the most common choice).
  - `AddOn` — adds new functionality without modifying existing objects.
  - `Patch` — a targeted hotfix.
- **compatibilityMode** (optional): a `CompatibilityMode` enum literal for the extension's
  compatibility setting (e.g. `Version8_3_10`). Omit or pass empty for the factory default.
  An unrecognised value is rejected with an error listing the correct format.
- **standardChecks** (optional, default `true`): enable 1C:Standards BSL checks. Only applied
  when `com.e1c.v8codestyle` is installed; otherwise ignored (see `codestyle.note` in the
  response).
- **commonChecks** (optional, default `true`): enable common project-level BSL checks. Same
  condition as `standardChecks`.
- **autoSortTopObjects** (optional): accepted for API stability but **not yet applied** in this
  release — the exact enable-key in the autosort preferences could not be confirmed without a
  live wizard-created project. The response carries `codestyle.autoSortNote` to explain this.

## ScriptVariant

The extension inherits `scriptVariant` from the base configuration automatically — English
base → English extension, Russian base → Russian extension. There is no override parameter;
use `modify_metadata` on the extension's Configuration after creation if you need to change it.

## Examples

Minimal — creates `MyConfig.MyExt` extending `MyConfig`:

```json
{"name": "MyExt", "baseProjectName": "MyConfig"}
```

Full — explicit project name, prefix, purpose:

```json
{
  "name": "MyExt",
  "baseProjectName": "MyConfig",
  "projectName": "MyConfig.MyExtension",
  "prefix": "Ext1_",
  "synonym": "My Extension",
  "comment": "Customization extension for client A",
  "purpose": "Customization",
  "standardChecks": true,
  "commonChecks": true
}
```

## Workflow

1. `create_extension_project` — create the extension and bind it to the base.
2. `adopt_metadata_object` — adopt the objects/members you want to override into the extension.
3. `create_metadata` / `modify_metadata` on the adopted objects — extend the behavior.
4. `delete_project` — remove the extension project when it is no longer needed (use
   `confirm=true, deleteContent=true`).

## Response fields

On success the response includes:
- `extensionProject` — the workspace project name (the round-trip key for sibling tools).
- `name` — the Configuration name set on the extension.
- `baseProject` — the base configuration project.
- `prefix` / `purpose` / `scriptVariant` / `version` — attributes applied.
- `state` — `ready` when the lifecycle STARTED event was received (project is fully indexed);
  `created` when the timeout elapsed before STARTED (may need a short wait before calling
  `adopt_metadata_object`).
- `codestyle` — `{applied: bool, note: string, autoSortNote: string}` reporting the
  v8codestyle preference write result.

## Gotchas

- **Extension name must be new.** The call is rejected if a workspace project with the
  computed name already exists. Use a unique `name` or pass an explicit `projectName`.
- **Base project must be a configuration, not an extension.** Pass the BASE
  configuration's project name. Passing an extension project name returns an error.
- **After creation, wait for `state=ready`** before calling `adopt_metadata_object`; the new
  project must complete lifecycle indexing. If `state=created` is returned, retry after a
  few seconds or call `revalidate_objects` to trigger indexing.
- **autoSortTopObjects is not yet applied.** See `codestyle.autoSortNote` in the response.
