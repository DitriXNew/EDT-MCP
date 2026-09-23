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

- On success the tool returns MARKDOWN with the created project name, the (normalized) import path and the state EDT left the project in; when importPath is outside the workspace the response carries an `outsideWorkspace` flag and a trust note.
- **The import alone does not give you a usable project, so the tool starts it.** EDT's CLI import API parks a blocked start latch on the new project and never releases it, so nothing in the platform would ever start the project's context: it would sit at `not_available` in `list_projects` forever (and, while that latch is in place, EDT does not auto-start any OTHER project in the workspace either). After the import returns, the tool therefore refreshes the new project from disk, asks EDT to start the project, and waits for it. Releasing the latch is NOT part of that: it would let EDT's watchdog schedule a second, competing start for the same project. The tool releases it only if that post-import step itself fails - the workspace refresh or the start request - or could not be scheduled at all, so EDT's own watchdog is left able to recover.
- **Two possible states, and the front matter says which one you got.** `state: ready` with `projectReady: true` means EDT started the project's context and registered it. `state: importing` with `projectReady: false` and `startWaitSeconds: 300` means the import finished and the files are on disk, but EDT had not finished starting the project within the 300-second wait; the work keeps running inside EDT. Poll `list_projects` until the project reports `ready` before calling model tools on it, and do NOT repeat the import - the project name is already taken.
- **`state: ready` is about the CONTEXT, not about indexing.** It means the project context is started; `list_projects` can still report the project `building` for a short while after that, until EDT has finished computing its derived data. Wait for `ready` there before relying on the model.
- **The `state: importing` body says which step is still running**, because the recovery differs: the workspace refresh of the imported files is still going (the start request has not been issued yet and will be issued when the refresh finishes); or the start request has been handed to EDT after the refresh finished and EDT is still processing it; or the start request returned and EDT is still starting the project. All three keep running in EDT after the call returns.
- **An import whose start fails outright is an error, not a success**, and it carries `mutationCommitted` - the project exists, so retry the import only after removing it with `delete_project`.
- **One import per project name at a time.** A second call for a name whose import is still running is refused up front ("An import into project `<name>` is already in progress"). This matters: the "project already exists" guard cannot see a project the first call has not created yet, so two concurrent imports both pass it and then destroy each other's work inside EDT. Wait for the first one, then poll `list_projects`.

## Gotchas

- **Project must be new.** If a workspace project with `projectName` already exists, the call is rejected early; pick a fresh name.
- **Directory, not a file.** `importPath` must point at the directory of XML files, not at a single `.xml` file.
- **Requires the CLI API plugin.** Needs the EDT plugin `com._1c.g5.v8.dt.cli.api`; if it is not installed the tool returns an error instead of importing.
- **A big configuration takes minutes to start.** The 300-second wait is not a bound on the import (that has already finished when the wait starts) - it is only how long this one call watches EDT start the project before answering `state: importing`.
- **Budget the client-side call timeout.** The import itself is unbounded on the platform side (the CLI API offers no cancellation), and the post-import start wait adds up to 300 seconds on top of it. On a large dump one call can therefore run for many minutes; a client with a shorter transport timeout will drop the answer while the import keeps going.
