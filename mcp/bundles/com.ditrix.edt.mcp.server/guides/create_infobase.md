Creates a new FILE infobase (1C:Enterprise database) OR registers an existing one, and binds it to a configuration project so it appears in `get_applications` as an application of type `com.e1c.g5.dt.applications.type.infobase`. This mirrors the two choices of the EDT "new infobase" dialog: make a new database, or use a database that already exists on disk.

## Modes

- **`mode='create'` (default)** — makes a brand-new file infobase at `infobaseFile`. This launches the 1C platform (`1cv8 CREATEINFOBASE`), so a **registered 1C:Enterprise platform runtime** must be installed in EDT (Window > Preferences > 1C:Enterprise > Installed Installations). The tool probes for a platform first and fails FAST with an actionable error if none is registered.
- **`mode='register'`** — adds an EXISTING file infobase already present at `infobaseFile` (the directory must contain a `1Cv8.1CD`). No platform launch happens, so this works even without a configured runtime.

FILE infobases only. SERVER and WEB infobases are out of scope for v1 and are rejected with a clear "not yet supported" error.

## Application kind: infobase vs standalone server

`applicationKind` selects what is created:

- **`applicationKind='infobase'` (default)** — a plain file infobase via the 1C configurator, exactly as described above (this is the original behaviour; omitting `applicationKind` is byte-identical to before). Surfaces as `com.e1c.g5.dt.applications.type.infobase`.
- **`applicationKind='standaloneServer'`** — an **autonomous (standalone) server** that creates *and serves* a new file infobase, binds it to the project, and exposes a **web URL for HTTP testing**. It goes through the EDT WST standalone-server layer (shelling out to `ibcmd`, not `1cv8`) and surfaces as a `com.e1c.g5.dt.applications.type.wst-server` application (its id is `"ServerApplication.<name>"`, not a UUID). It runs in a background Job (up to 120 s); `get_applications` works unchanged on the resulting application. **To load the configuration into a standalone server, prefer the coordinated launch flow** — `debug_launch` or `run_yaxunit_tests` with `updateBeforeLaunch=true`, which updates the server application as part of a managed launch. A **bare `update_database` on a standalone-server application starts it in RUN mode** (see the `update_database` guide), so avoid it for server apps.

No port/publication parameters are accepted: for a file-backed standalone server EDT **auto-allocates** the web port (a requested port is ignored) and the publication base is fixed. The **actual** port and the web URL are reported back in the result (`port`, `webUrl`).

### Wrapping an EXISTING infobase with a standalone server (`mode='register'`)

`applicationKind='standaloneServer'` supports **both** modes:

- **`mode='create'` (default)** — creates and serves a brand-new file infobase (described above).
- **`mode='register'`** — wraps an **EXISTING** file infobase (a `1Cv8.1CD` must already be present at `infobaseFile`) with a standalone server: it performs the **same** WST server registration and web-URL exposure but **does not materialize a database** (your existing data is left untouched). The `1Cv8.1CD` requirement is validated up front, so a wrong path fails fast with an actionable error and no server is registered. A registered 1C standalone-server runtime (platform >= 8.3.23) is still required — the server needs it to run.

On success the result's `action` is **`registered`** (not `created`), and the `message` states the server was registered over the existing infobase.

```
# Register a standalone server over an EXISTING file infobase (data already on disk):
1. create_infobase  projectName="MyProject"  infobaseFile="C:\infobases\ExistingApp"  applicationKind="standaloneServer"  mode="register"
#    -> action="registered"; webUrl/port point at the running server; the existing 1Cv8.1CD is reused as-is.
```

### Connection credentials for a standalone server (`mode='register'` only, issue #275)

The `user`/`password`/`access` connection-credential parameters (see "Parameter details" below) are now **accepted** for `applicationKind='standaloneServer'` **with `mode='register'`** — the wrapped infobase already exists and already has users, so storing credentials is meaningful. They are still **rejected** for `applicationKind='standaloneServer'` with `mode='create'` (a brand-new server has no infobase reference to store them against yet — omit them or add the credentials afterwards with `set_infobase_credentials` once the base has users).

Credentials for a registered standalone server are stored against the **application EDT's own launch path resolves** (`ServerApplicationBehaviourDelegate` adapts the `wst-server` application/module to an `InfobaseReference` via `org.eclipse.core.runtime.Adapters`), so they authenticate the SAME update agent a later `debug_launch`/`update_database` starts.

```
# Register a standalone server over an EXISTING infobase AND store credentials for its 'Admin' user:
1. create_infobase  projectName="MyProject"  infobaseFile="C:\infobases\ExistingApp"  applicationKind="standaloneServer"  mode="register"  user="Admin"  password="secret"
#    -> action="registered"; the message confirms the credentials were stored (or, non-fatally, a WARNING if storage failed).
```

### Prerequisites and result

- Requires a **registered 1C standalone-server runtime** — a 1C:Enterprise platform **>= 8.3.23** with the standalone server (`ibsrv`/`ibcmd`), registered in EDT. The tool probes for it **before** the background Job and fails FAST with an actionable error if absent (no hang).
- On success the result adds **`applicationKind='standaloneServer'`** and, **best-effort**, **`webUrl`** (the infobase web URL — use it for HTTP testing) and **`port`** (the ACTUAL auto-allocated web port, parsed from `webUrl`), alongside the usual `applications`, `applicationId`, and `message` fields. If EDT cannot resolve the web URL, `webUrl`/`port` are omitted — the server is still created and bound.

```
# Create an autonomous (standalone) server with a web URL:
1. create_infobase  projectName="MyProject"  infobaseFile="C:\infobases\MyAppSrv"  applicationKind="standaloneServer"
#    -> the result's webUrl is the HTTP endpoint to test against; port is the ACTUAL auto-allocated web port.
2. # Load the configuration via the coordinated launch flow (NOT a bare update_database, which would
#    start the server in RUN mode):
   debug_launch  projectName="MyProject"  applicationId=<id from step 1>  updateBeforeLaunch=true
```

## What it does

1. Resolves and validates the project and the `mode`.
2. For `create`: probes for an available 1C platform runtime (fails fast when absent) and creates the target directory if it does not exist. For `register`: verifies the directory already contains a file infobase.
3. For `create`: runs `IInfobaseCreationOperation.perform(...)` in a **background Eclipse Job** (up to 120 s) — it shells out to `1cv8`, never on the UI thread. For `register`: adds the reference directly via `IInfobaseManager.add(...)` (no platform launch, no Job).
4. Associates the infobase with the project via `IInfobaseAssociationManager.associate(...)`. After this step `get_applications` returns the application.
5. Returns the resulting application id so you can chain directly into `update_database`.

## Parameter details

- **projectName** (required): the EDT configuration project to bind the infobase to. Must exist and be open (use `list_projects` to verify).
- **mode** (optional, `create` | `register`, default `create`): create a new database, or register an existing one.
- **infobaseFile** (required): absolute path to the infobase **directory**. For `create` it is created if absent and the `1Cv8.1CD` files are written into it; for `register` it must already contain a file infobase. Example: `C:\infobases\MyApp`.
- **infobaseName** (optional): display name for the infobase in the EDT Infobases view. If omitted, a name is auto-generated.
- **platform** (optional, `create` only): 1C platform version mask (e.g. `8.3.25`). If omitted, EDT resolves the best available installed version automatically.
- **setDefault** (boolean, default false): set the infobase as the default application for the project afterwards.
- **user** / **password** / **access** (optional, #194; standalone-server support #275): store **infobase connection credentials** so a later `update_database` / `debug_launch` can authenticate the update agent against a base with a user list. `access` is `INFOBASE` (default, 1C user auth) or `OS`. Accepted for `applicationKind='infobase'` (any mode), and for `applicationKind='standaloneServer'` with `mode='register'` — **rejected** for `applicationKind='standaloneServer'` with `mode='create'` (a brand-new server has no infobase reference to store them against yet). Most useful with `mode='register'` (the existing base already has users); for a `mode='create'` file infobase there are no users yet, so the credentials authenticate only once a matching user is added. Use `set_infobase_credentials` to change them later.
- **applicationKind** (optional, `infobase` | `standaloneServer`, default `infobase`): see "Application kind" above. The standalone-server path takes no port/publication input — EDT auto-allocates the web port and reports it back as `port`/`webUrl`.

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
