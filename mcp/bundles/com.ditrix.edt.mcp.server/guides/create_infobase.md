Creates a new FILE infobase (1C:Enterprise database) OR registers an existing one, and binds it to a configuration project so it appears in `get_applications` as an application of type `com.e1c.g5.dt.applications.type.infobase`. This mirrors the two choices of the EDT "new infobase" dialog: make a new database, or use a database that already exists on disk.

## Modes

- **`mode='create'` (default)** — makes a brand-new file infobase at `infobaseFile`. This launches the 1C platform (`1cv8 CREATEINFOBASE`), so a **registered 1C:Enterprise platform runtime** must be installed in EDT (Window > Preferences > 1C:Enterprise > Installed Installations). The tool probes for a platform first and fails FAST with an actionable error if none is registered.
- **`mode='register'`** — adds an EXISTING file infobase already present at `infobaseFile` (the directory must contain a `1Cv8.1CD`). No platform launch happens, so this works even without a configured runtime.

FILE infobases only. SERVER and WEB infobases are out of scope for v1 and are rejected with a clear "not yet supported" error.

## What it does

1. Resolves and validates the project and the `mode`.
2. For `create`: probes for an available 1C platform runtime (fails fast when absent) and creates the target directory if it does not exist. For `register`: verifies the directory already contains a file infobase.
3. Runs `IInfobaseCreationOperation.perform(...)` in a **background Eclipse Job** (up to 120 s) — `createNew=true` for `create` (shells out to `1cv8`), `createNew=false` for `register` (registration only). Never on the UI thread.
4. Associates the infobase with the project via `IInfobaseAssociationManager.associate(...)`. After this step `get_applications` returns the application.
5. Returns the resulting application id so you can chain directly into `update_database`.

## Parameter details

- **projectName** (required): the EDT configuration project to bind the infobase to. Must exist and be open (use `list_projects` to verify).
- **mode** (optional, `create` | `register`, default `create`): create a new database, or register an existing one.
- **infobaseFile** (required): absolute path to the infobase **directory**. For `create` it is created if absent and the `1Cv8.1CD` files are written into it; for `register` it must already contain a file infobase. Example: `C:\infobases\MyApp`.
- **infobaseName** (optional): display name for the infobase in the EDT Infobases view. If omitted, a name is auto-generated.
- **platform** (optional, `create` only): 1C platform version mask (e.g. `8.3.25`). If omitted, EDT resolves the best available installed version automatically.
- **setDefault** (boolean, default false): set the infobase as the default application for the project afterwards.

## Result

JSON with `action` (`'created'` for `create`, `'registered'` for `register`), `project`, `infobaseFile`, `infobaseName`, `applications` (same shape as `get_applications`), `applicationId` (for chaining into `update_database`), and a `message`.

## Typical workflow

```
# Create a brand-new infobase:
1. create_infobase  projectName="MyProject"  infobaseFile="C:\infobases\MyApp"
2. update_database  projectName="MyProject"  applicationId=<id from step 1>  confirm=true

# Or register an existing one:
1. create_infobase  projectName="MyProject"  mode="register"  infobaseFile="C:\infobases\Existing"
```

## Gotchas

- **Platform required for `create`**: if no 1C platform runtime is registered, `mode='create'` returns an actionable error. Use `mode='register'` for an existing infobase (no platform needed) or register a platform in EDT preferences.
- **`register` needs an existing infobase**: the path must contain a `1Cv8.1CD`; otherwise the tool errors and points you to `mode='create'`.
- **FILE only**: passing a server/web connection string as `infobaseFile` is not supported — use the dedicated server creation tooling for that.
- **Timeout**: the background Job waits up to 120 seconds. The tool reports an honest timeout, not a fake success.
- **Cleanup**: use `delete_infobase` to remove an infobase from the project and the EDT infobases list.
- **State after creation**: a newly created infobase is empty — `get_applications` reports `FULL_UPDATE_REQUIRED` or similar. Call `update_database` to push the configuration into it.
