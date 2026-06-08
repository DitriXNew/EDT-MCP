Wraps the EDT "Import → Configuration from XML Files" action. It creates a brand-new EDT project in the workspace from a directory of XML source files. This is the inverse of `export_configuration_to_xml`.

## When to use

- You have a configuration on disk as an XML dump (e.g. produced by `export_configuration_to_xml`, a Designer dump, or a VCS checkout) and you want it as a live EDT project.
- Use this only to create a NEW project. To refresh or re-import into an existing project, this tool will reject the call (see Gotchas).

## Parameter details

- **importPath** (required): filesystem path to the DIRECTORY containing the XML files. The path is normalized to an absolute path before use. It must exist and must be a directory (a missing path or a file is rejected up front with a deterministic error). A path outside the EDT workspace root is allowed but FLAGGED (logged as a warning and noted in the response) — import from an external location is trusted-caller-only (see README Security & trust model).
- **projectName** (required): name of the new EDT project to create. Must NOT already exist in the workspace, otherwise the call is rejected.
- **projectNature** (optional): EDT project nature ID, e.g. `com._1c.g5.v8.dt.core.V8ConfigurationNature`. Pass an empty string (or omit) to let EDT auto-detect the nature from the source.
- **xmlVersion** (optional): XML format version, e.g. `8.3.20`. Pass an empty string (or omit) to let EDT auto-detect.

## Examples

Minimal (auto-detect nature and version):

```json
{"importPath": "D:/dumps/MyConfig", "projectName": "MyConfig"}
```

Explicit nature and XML version:

```json
{"importPath": "D:/dumps/MyConfig", "projectName": "MyConfig", "projectNature": "com._1c.g5.v8.dt.core.V8ConfigurationNature", "xmlVersion": "8.3.20"}
```

## Notes

- On success the tool returns MARKDOWN with the created project name and the (normalized) import path; when importPath is outside the workspace the response carries an `outsideWorkspace` flag and a trust note.
- After import the tool forces a close/open/refresh of the new project, because the underlying CLI API imports with refresh disabled and the project would otherwise stay un-scanned (DtProject not ready) until something triggers EDT's project lifecycle.

## Gotchas

- **Project must be new.** If a workspace project with `projectName` already exists, the call is rejected early; pick a fresh name.
- **Directory, not a file.** `importPath` must point at the directory of XML files, not at a single `.xml` file.
- **Requires the CLI API plugin.** Needs the EDT plugin `com._1c.g5.v8.dt.cli.api`; if it is not installed the tool returns an error instead of importing.
