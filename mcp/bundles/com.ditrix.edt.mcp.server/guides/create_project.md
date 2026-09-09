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
  may be seeded with its root `ExternalDataProcessor` / `ExternalReport` in the same call,
  after which its members are ready for authoring via `create_metadata`. Omit the root when
  creating an empty project for a later `.epf` / `.erf` import.

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
- **externalObject** (optional): root object to seed, addressed as
  `ExternalDataProcessor.<Name>` or `ExternalReport.<Name>`. The TYPE token is resolved by
  the shared bilingual metadata resolver, so its registered Russian spellings work too, and
  is never normalized. `<Name>` is the programmatic 1C identifier, not a synonym. A bare Name
  is rejected rather than guessed. Omit this parameter to preserve an empty project for the
  import workflow.
- **normalizeYo** (optional, default `true`): normalize `ё`->`е` / `Ё`->`Е` in the seeded
  root NAME before identifier validation. `ё` in a Name is flagged by the 1C standard
  `mdo-ru-name-unallowed-letter`, so the default stores a compliant Name. Set `false` to keep
  `ё` exactly as supplied. The result names rewritten fields under `normalized`; this never
  changes the externalObject TYPE token.
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

External data processor project with its root object:

```json
{
  "projectKind": "externalObjects",
  "name": "MyExternal",
  "externalObject": "ExternalDataProcessor.MyProcessor"
}
```

To deliberately preserve `ё` in the root Name:

```json
{
  "projectKind": "externalObjects",
  "name": "MyExternal",
  "externalObject": "ExternalReport.Всё",
  "normalizeYo": false
}
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

## Workflow (external object)

1. `create_project` with `projectKind=externalObjects` and an `externalObject` FQN — create
   the project and its root in one operation. Omit `externalObject` only when a later import
   will supply the root.
2. `create_metadata` — add attributes, tabular sections, forms, templates, and form content
   below that root FQN.
3. `write_module_source` — fill the root and form modules.
4. `build_external_objects` — produce the `.epf` / `.erf` artifact.

## Response fields

On success the response includes:
- `action` — `created` after a completed create (and on the unchanged empty-project slow
  path); `verificationRequired` when a seeded create exceeded the wait window.
- `project` — the workspace project name (the round-trip key for sibling tools).
- `projectKind` — kind of project created.
- `name` — the Configuration name (configuration and extension only).
- `baseProject` — the base configuration project (extension only).
- `prefix` / `purpose` — extension-only attributes applied.
- `scriptVariant` — script variant applied or inherited.
- `version` — platform version used for project creation.
- `externalObject` — canonical FQN of the seeded root, including the stored (possibly
  normalized) Name, when a root was requested.
- `externalObjectConfirmed` — on the slow seeded-root path, whether that FQN was actually
  observed through the same metadata scope used by `get_metadata_objects`.
- `normalized` — fields rewritten by the default `ё`->`е` normalization; `name` here means
  the seeded root Name.
- `state` — `ready` when the lifecycle STARTED event was received (project is fully
  indexed); `created` when the timeout elapsed before STARTED and creation is otherwise
  confirmed; `rootConfirmed` / `rootUnconfirmed` records whether a requested root was
  observed during a slow create. Both slow seeded-root states use
  `action=verificationRequired`, not `created`, because the create job has not finished.
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
  If `state=created` is returned, retry the dependent call after a few seconds or call
  `revalidate_objects`.
- **A slow seeded root is verified, not recreated.** If creation exceeds the wait window,
  the tool makes a best-effort read through the metadata scope. An unresolvable root may
  simply mean indexing is still running. The response always reports
  `action=verificationRequired`, the requested canonical `externalObject`, and the observed
  result as `state=rootConfirmed` / `state=rootUnconfirmed` plus
  `externalObjectConfirmed=true` / `false`. Once indexing finishes, verify it with
  `get_metadata_objects`. Do not repeat `create_project`: the existing project container will
  make the duplicate-project guard refuse the retry, whether or not EDT eventually attaches
  the root.
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
- **Omitting externalObject is intentional.** It creates the same empty external-objects
  project as before, ready for an import that supplies its root object.
