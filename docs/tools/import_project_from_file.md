# import_project_from_file

Create a NEW EDT project from a 1C binary file: .cf (configuration), .cfe (extension; baseProjectName required) or .epf/.erf (external data processor/report). Needs an installed 1C:Enterprise platform. Runs in background: an unfinished call returns a jobId - poll get_job_status, do not call again. Parameters and examples: get_tool_guide('import_project_from_file').

## Parameters
| Parameter | Required | Type | Description |
| --- | --- | --- | --- |
| filePath | yes | string | Absolute path of the .cf, .cfe, .epf or .erf file. |
| projectName | yes | string | Name of the NEW EDT project to create (must not already exist). |
| baseProjectName | — | string | Configuration project the result depends on: REQUIRED for .cfe, optional for .epf/.erf, refused for .cf. |
| platformVersion | — | string | Installed platform version or mask used for the conversion, e.g. '8.3.24'; default: the best match. |
| waitSeconds | — | integer | Seconds this call may wait before returning the job; defaults to 30, accepts 0 to 45. |

## Guide
Creates a brand-new EDT project from a 1C binary file. EDT cannot read these files itself, so the tool first converts the file to XML with the thick client of an installed 1C:Enterprise platform, then imports the XML exactly as `import_configuration_from_xml` does and asks EDT to start the new project.

| File | Holds | New project | `baseProjectName` |
| --- | --- | --- | --- |
| `.cf` | a configuration | configuration project | refused |
| `.cfe` | a configuration extension | extension project linked to the base | **required** |
| `.epf` | an external data processor | external-objects project | optional (links it to the base) |
| `.erf` | an external report | external-objects project | optional (links it to the base) |

## When to use

- A supplier ships a `.cf`/`.cfe`, or someone hands you an `.epf`/`.erf`, and you want it as an EDT project to read, check or edit.
- Only to create a NEW project. Nothing is compared, merged or updated in an existing project.
- For a directory of XML files (a Designer dump, `export_configuration_to_xml` output) use `import_configuration_from_xml` instead; it needs no platform.

## Parameter details

- **filePath** (required): absolute path of the file on the machine EDT runs on. The kind is taken from the extension (case-insensitive) and then checked against what the platform actually dumped: a `.cf` that holds an extension, or a `.cfe` that holds a configuration, is refused before any project is created. So is an `.epf`/`.erf` named `Configuration`: its dump's root file would be `Configuration.xml`, which EDT imports as a configuration; import a copy under another name.
- **projectName** (required): name of the new project. It must be a legal Eclipse project name, no workspace project may have it, and no folder of that name may exist in the workspace directory.
- **baseProjectName**: an open CONFIGURATION project (not an extension, not an external-objects project). The extension or external objects are linked to it and get its runtime version; the platform used for the conversion is picked to fit it.
- **platformVersion**: a version (`8.3.24.1691`) or mask (`8.3.24`) of an installed platform to convert with. Omitted, EDT picks the best installed platform (one compatible with the base project when there is one).
- **waitSeconds**: how long this call waits for the job, 0 to 45, default 30.

## Examples

A configuration:

```json
{"filePath": "D:/supplier/Trade.cf", "projectName": "Trade"}
```

An extension of an existing project:

```json
{"filePath": "D:/supplier/Fixes.cfe", "projectName": "Trade.Fixes", "baseProjectName": "Trade"}
```

An external data processor, standalone or linked:

```json
{"filePath": "D:/tools/Loader.epf", "projectName": "Loader"}
{"filePath": "D:/tools/Loader.epf", "projectName": "Loader", "baseProjectName": "Trade"}
```

## The job

The work runs as a background job, because converting a large configuration takes minutes:

1. **Convert.** A `.cf` is loaded into a throw-away file infobase and dumped to XML; a `.cfe` is loaded as an extension into an empty throw-away infobase and dumped; an `.epf`/`.erf` is dumped directly. Each step is one 1cv8 designer process. The conversion gets 2 hours in total; a step still running then is stopped and its 1cv8 process killed.
2. **Check** that the dump holds what the extension promised.
3. **Create** the project from the XML. Up to here nothing exists in the workspace, and a failure or a `cancel_job` leaves nothing behind. From here the job cannot be cancelled.
4. **Start** the project the same way `import_configuration_from_xml` does, waiting up to 300 seconds.

The scratch infobase and XML are deleted when the job ends.

If the job is not finished within `waitSeconds`, the call answers **Pending** with a `jobId`. Poll `get_job_status` with that id (`waitSeconds` up to 45 there too) until it is `done` or `failed`; do not call this tool again for the same project meanwhile - a second call for a name whose import is running is refused.

## The answer

A finished job answers with a table: the project, `State` (`ready`, or `starting` when EDT had not finished starting it within the wait), the project kind EDT gave it, the configuration name, the base project it is linked to, the external objects it holds, the source file (plus an `Outside workspace` row when it lies outside the EDT workspace) and the platform version used. Kind, base and objects are read back from EDT, not repeated from the request; while the project is still starting they are not readable yet and the table says so.

`ready` is about the project context; `list_projects` can still report `building` for a short while. Poll it until `ready` before calling model tools.

## Failures

- A refusal of the arguments (missing file, unsupported extension, taken name, wrong base) comes back at once, before any job starts.
- **No installed platform** (or none matching `platformVersion`) is also refused at once. Install the 1C:Enterprise platform and register it in EDT (Window -> Preferences -> 1C:Enterprise -> Installed Installations); without one, dump the file to XML in Designer and use `import_configuration_from_xml`.
- A conversion failure (a damaged file, a file of another kind, a platform too old for the file) fails the job with the designer's own log text. No project was created.
- An import failure deletes nothing: whatever EDT created is left in place, because no check can prove a project of that name is this import's own rather than another operation's. The error says what the workspace holds afterwards - a project or a workspace folder to check before deleting it (`delete_project`, `deleteContent=true`) or removing it, or nothing ("No project was created") - and carries `mutationOutcomeUnknown` when something is there. A leftover project may still hold EDT's import start latch, and while it does EDT starts no project on its own until that project is deleted, closed or started (or EDT restarts). The tool does not release it: the latch belongs to the project, not to one import, so releasing it could let EDT start another importer's project before that import is done. The error names the project and what clears the latch.
- The `mutationCommitted` / `mutationOutcomeUnknown` markers come with the answer of the call that saw the job end. A failure read through `get_job_status` carries the same message, which says in words whether a project exists.
- A start failure keeps the project (the import succeeded) and carries `mutationCommitted`; follow the recovery the message gives instead of importing again.

## Gotchas

- **Needs the 1C:Enterprise platform with the thick client** on the EDT machine. EDT alone cannot read `.cf`/`.cfe`/`.epf`/`.erf`.
- **A `.cf`/`.cfe` conversion creates its temporary infobase under `java.io.tmpdir`**, and EDT's infobase command splits that path at spaces. A path holding two spaces in a row (on Windows) or any space (elsewhere) is refused at once; start EDT with `-Djava.io.tmpdir=<a path without spaces>` after `-vmargs` in `1cedt.ini`.
- **The platform must be able to open the file.** A file saved by a newer platform than any installed one fails the conversion; pick an installed version with `platformVersion`, or install a newer platform.

---
*Generated from the live MCP server (`get_tool_guide`) by `docs/generate_tool_docs.py`. Do not edit this file. Edit the tool's description/schema in its Java source and its guide body in `mcp/bundles/com.ditrix.edt.mcp.server/guides/<tool>.md`.*
