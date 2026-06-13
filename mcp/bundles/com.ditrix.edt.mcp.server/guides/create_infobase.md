Creates a new FILE infobase (1C:Enterprise database) on disk and binds it to a configuration project so it appears in `get_applications` as an application of type `com.e1c.g5.dt.applications.type.infobase`.

## Prerequisites

- A **registered 1C:Enterprise platform runtime** must be installed in EDT (Window > Preferences > 1C:Enterprise > Installed Installations). The tool probes for a platform at startup and fails FAST with an actionable error if none is registered — headless CI without a 1C platform will see this error, which proves the tool is wired correctly without needing a platform.
- FILE infobases only. SERVER and WEB infobases are out of scope for v1 and are rejected with a clear "not yet supported" error.

## What it does

1. Resolves and validates the project.
2. Probes for an available 1C platform runtime (fails fast when absent).
3. Creates the infobase directory if it does not exist.
4. Runs `IInfobaseCreationOperation.perform(...)` in a **background Eclipse Job** (up to 120 s). This shells out to `1cv8 CREATEINFOBASE` — the same mechanism EDT's wizard uses; it is NOT run on the UI thread.
5. Associates the infobase with the project via `IInfobaseAssociationManager.associate(...)`. After this step `get_applications` returns the new application.
6. Returns the resulting application id so you can chain directly into `update_database`.

## Parameter details

- **projectName** (required): the EDT configuration project to bind the infobase to. Must exist and be open (use `list_projects` to verify).
- **infobaseFile** (required): absolute path to the **directory** where the infobase files (`1Cv8.1CD` etc.) will be created. The directory will be created if it does not exist. Must be writable. Example: `C:\infobases\MyApp`.
- **infobaseName** (optional): display name for the new infobase in the EDT Infobases view. If omitted, a name is auto-generated.
- **platform** (optional): 1C platform version mask (e.g. `8.3.25`). If omitted, EDT resolves the best available installed version automatically.
- **setDefault** (boolean, default false): set the new infobase as the default application for the project after creation.

## Result

JSON with `action='created'`, `project`, `infobaseFile`, `infobaseName`, `applications` (same shape as `get_applications`), `applicationId` of the new infobase (for chaining into `update_database`), and a `message`.

## Typical workflow

```
1. create_infobase  projectName="MyProject"  infobaseFile="C:\infobases\MyApp"
2. update_database  projectName="MyProject"  applicationId=<id from step 1>  confirm=true
3. debug_launch     projectName="MyProject"  applicationId=<id from step 1>
```

## Gotchas

- **Platform required**: if no 1C platform runtime is registered, the tool returns an actionable error ("No 1C platform runtime is registered…"). Register one in EDT preferences and retry.
- **FILE only**: passing a server/web connection string as `infobaseFile` is not supported — use the dedicated server creation tooling for that.
- **Timeout**: the background Job waits up to 120 seconds. If the 1cv8 process is slow (e.g. loading a large `.cf` template), it may time out. Increase disk/CPU performance or retry; the tool reports an honest timeout, not a fake success.
- **Cleanup**: use `delete_infobase` to remove a created infobase from the project and the EDT infobases list.
- **State after creation**: the new infobase is empty — `get_applications` will report an update state of `FULL_UPDATE_REQUIRED` or similar. Call `update_database` to push the configuration into it.
