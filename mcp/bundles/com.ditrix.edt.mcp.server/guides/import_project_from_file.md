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

- **filePath** (required): absolute path of the file on the machine EDT runs on. The kind is taken from the extension (case-insensitive) and then checked against what the platform actually dumped: a `.cf` that holds an extension, or a `.cfe` that holds a configuration, is refused before any project is created.
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
- An import failure removes the half-created project again and says so. If that removal fails, the error carries `mutationCommitted` and names what is left - a project to delete with `delete_project` (`deleteContent=true`), or a workspace folder to remove - before importing again.
- A start failure keeps the project (the import succeeded) and carries `mutationCommitted`; follow the recovery the message gives instead of importing again.

## Gotchas

- **Needs the 1C:Enterprise platform with the thick client** on the EDT machine. EDT alone cannot read `.cf`/`.cfe`/`.epf`/`.erf`.
- **The platform must be able to open the file.** A file saved by a newer platform than any installed one fails the conversion; pick an installed version with `platformVersion`, or install a newer platform.
