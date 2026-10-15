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
| compatibilityMode | — | string | Optional extension compatibility-mode string matching a CompatibilityMode enum literal (e.g. 'Version8_3_24'); omitted or empty = the base configuration's compatibility mode, the one EDT's new-extension wizard always uses. Unknown values are rejected, and so is 8.3.13 or lower over a base above 8.3.13. Extension only; REJECTED for other kinds. |
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
  without modifying the base configuration. Its root is built the way EDT's own
  new-extension wizard builds it — see **Extension root** below.
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
  (e.g. `Version8_3_24`). Omit or pass empty for the base configuration's compatibility
  mode — what EDT's new-extension wizard always uses. A mode of 8.3.13 or lower over a base
  above 8.3.13 is refused before anything is created (EDT's adopter takes the root's
  default-roles state from the base's mode). A mode above the base's is accepted with a
  warning in `message`: the wizard never produces it, and whether the platform accepts such
  an extension was not verified.
- **version**: REJECTED — extension always inherits the version from the base configuration.
- **scriptVariant**: REJECTED — extension always inherits scriptVariant from the base.

### externalObjects kind

- **version** (optional): platform version string. Default: `Version.LATEST`.
- **scriptVariant** (optional, `Russian` or `English`): applied post-create via
  `IExternalObjectProjectManager.setScriptVariant`. Non-fatal on failure (see
  `scriptVariantNote` in response).
- **baseProjectName**, **prefix**, **purpose**, **compatibilityMode**, **synonym**,
  **comment**: all REJECTED for this kind.

## Extension root

The root of a new extension is the base configuration's root ADOPTED, the way EDT's own
"New configuration extension" wizard builds it — not a fresh configuration:

- it carries the extension block with EDT's property states (`Checked` / `Extended`), and
  none of the properties only a configuration has;
- it keeps mapping adopted objects to the base by IDs
  (`keepMappingToExtendedConfigurationObjectsByIDs`), which is what makes
  `adopt_metadata_object` link each adopted object to its base object by ID — on a
  platform version above 8.3.14 only: at 8.3.14 or lower EDT maps adopted objects by name
  whatever the flag;
- it adopts the base's default language and inherits the base's script variant and
  compatibility mode (unless `compatibilityMode` is given);
- the two command interface files the wizard creates, `CommandInterface.cmi` and
  `MainSectionCommandInterface.cmi`, are created too (unless the create exceeds the 120 s wait
  window — see Gotchas), and looked for on disk within the same check as the root file:
  `message` names a file that was not seen on disk, and a save of them EDT did not accept
  when a file was not seen or no check could run (they do not change `state`).

Not replicated: EDT's wizard also creates a default role (named from the prefix in EDT's UI
language, e.g. `Ext1_DefaultRole`; only over a base whose compatibility mode is above
8.3.13 — the wizard's extension mode is always the base's) that gives users rights to the
extension's new objects. This tool does not create it, and no MCP path
can add a role to the extension root's `defaultRoles` yet — add it in EDT. The service
modules the v8codestyle plugin's templates write are not created either;
`write_module_source` creates a module when you write one.

As with any extension, EDT connects the new extension to the infobases of its base project;
`get_applications` lists them for the extension (`inheritedFromProject`).

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
- `name` — the Configuration name (configuration and extension only; not reported by a slow
  extension create).
- `baseProject` — the base configuration project (extension only).
- `prefix` / `purpose` — extension-only attributes applied (not reported by a slow
  extension create: see Gotchas).
- `compatibilityMode` — the extension's effective compatibility mode as its enum name, e.g.
  `Version8_5_1` (extension only): the base configuration's unless one was requested (not
  reported by a slow extension create).
- `scriptVariant` — script variant applied or inherited (not reported by a slow extension
  create).
- `version` — platform version used for project creation.
- `state` — `ready` when EDT reported the project's context started. For an extension,
  `ready` also means its root file was seen on disk with the extension block, the mapping
  flag and the name, name prefix, purpose and extension compatibility mode this call set (a
  purpose of `Patch`, EDT's default, which EDT does not write, may be absent). No earlier
  project's `Configuration.mdo` is left there to confirm it: `create_project` refuses a
  project whose directory already exists on disk (see Gotchas); for a
  configuration or externalObjects project nothing on disk is checked, and EDT may still
  be saving the new project's files for a few seconds. `created` when that was not
  confirmed (for an extension, `message` says what was not); `rootConfirmed` /
  `rootUnconfirmed` records whether a requested root was observed during a slow create (a
  slow extension create always answers `rootUnconfirmed`: nothing looks at its root).
  Both slow seeded-root states use `action=verificationRequired`, not `created`, because
  the create job has not finished. `ready` does not mean EDT has finished computing the
  project's derived data: `list_projects` may still report it `building` for a while.
- `message` — for an extension: how its root was built, what was not confirmed (its root
  file, its command interface files), and the wizard's default role this tool does not create.
- `codestyle` — `{applied: bool, note: string, autoSortNote: string}` reporting the
  v8codestyle preference write result.
- `synonymNote` — present only when the synonym (configuration/extension) could not be
  applied (no resolvable language code).
- `scriptVariantNote` — present only when the requested `scriptVariant` could not be
  applied for `externalObjects` (post-create `setScriptVariant` failed or was skipped).

## Gotchas

- **Project name must be new.** The call is rejected if a workspace project with the
  computed name already exists. Use a unique `name` or pass an explicit `projectName`.
  It is also rejected, for every kind, when the project's directory
  `<workspace>/<project>` already exists on disk without a workspace project of that name
  (as `delete_project` without `deleteContent` leaves it): EDT would create the new project
  over the files there, and delete them with it if that create failed. Nothing is created:
  remove that directory, or, if a workspace project keeps its files there, delete it with
  `delete_project deleteContent=true`; then retry.
- **Extension: base must be a configuration, not an extension.** Pass the BASE
  configuration's project name. Passing an extension project name returns an error.
- **After creation, check `state=ready`** before calling `adopt_metadata_object` or
  other project-dependent tools. If `state=created` is returned, retry the dependent call
  after a few seconds (for an extension, `message` says what was not confirmed). Do not
  restart EDT or run `clean_project` on a new project in its first seconds: EDT saves its
  files asynchronously, and only an extension's `ready` confirms its root file is on disk.
- **A slow extension create writes nothing more.** If an extension's create exceeds the
  120 s wait window while its project already exists, EDT's create may still be running: the
  call asks it to stop, makes no further write to the project and answers
  `action=verificationRequired`, `state=rootUnconfirmed`, without `name`, `prefix`, `purpose`,
  `scriptVariant` or `compatibilityMode` — nothing checked whether EDT attached the adopted
  root. Its `message` also says that the command interface files were not created: no MCP
  tool attaches them to an existing extension. Do not remove or recreate the project while
  that create may still be running — a delete or a second create would race it: first make
  sure it has ended (restarting EDT ends it; until then EDT's Progress view lists it as
  `create_project: <project>`), then check `list_projects` before using the project. To
  have the command interface files, remove the project with `delete_project` and
  `deleteContent=true` (without it the project's directory stays on disk and
  `create_project` refuses it) and run `create_project` again.
- **autoSortTopObjects is not yet applied.** See `codestyle.autoSortNote` in the response.
- **Client-side timeouts.** A create normally answers within seconds, but on a busy
  workspace the create operation alone may take up to its 120 s limit. If your MCP client
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
