# delete_infobase

Remove a FILE infobase association from a configuration project OR delete a standalone (autonomous) server application. Destructive: guarded by a confirm-preview - call without confirm to preview what would be removed (no change), then confirm=true to delete. For a file infobase: dissociates it and (deleteRegistration, default true) deregisters it from the EDT infobases list. For a standalone server (applicationKind=standaloneServer): stops it and removes the WST server and its server config folder. By default the infobase DATABASE FILES on disk are KEPT (both kinds); pass deleteDatabaseFiles=true to also delete the database directory. The inverse of create_infobase. Full parameters and examples: call get_tool_guide('delete_infobase').

## Parameters
| Parameter | Required | Type | Description |
| --- | --- | --- | --- |
| projectName | yes | string | EDT configuration project the infobase is bound to (required). |
| applicationId | — | string | Application ID from get_applications. Either applicationId or infobaseName is required. |
| infobaseName | — | string | Display name of the infobase to remove. Either applicationId or infobaseName is required. |
| deleteRegistration | — | boolean | true = also deregister the infobase from the global EDT infobases list (equivalent to 'Delete' in the Infobases view); default true. |
| deleteDatabaseFiles | — | boolean | true = ALSO delete the infobase database files/directory from disk (the 1Cv8.1CD directory) — IRREVERSIBLE. Default false = keep the database files on disk (the registration is removed but the data stays). Works for both a file infobase and a standalone server (deletes the served database directory). |
| confirm | — | boolean | true = perform the removal; default false = preview only (no change). |

## Guide
Removes a FILE infobase association from a configuration project, OR deletes a standalone (autonomous) server application. The inverse of `create_infobase` (both `applicationKind=infobase` and `applicationKind=standaloneServer`) and the cleanup step for a create-infobase round-trip. The tool auto-detects the application kind from the resolved application — no extra parameter is needed.

## Think twice — destructive (confirm-preview)

**File infobase** (`applicationKind=infobase`): dissociating removes it from `get_applications` for the project (other projects that share the infobase are unaffected). With `deleteRegistration=true` (the default) it is also removed from the EDT Infobases view. The infobase **database files on disk are KEPT by default** — pass `deleteDatabaseFiles=true` to also delete the directory (the `1Cv8.1CD` folder), or remove it manually.

**Standalone server** (`applicationKind=standaloneServer`): this mirrors EDT's "Delete server" action — it **stops the server and removes the WST server registration plus its server config folder** (the `Серверы/…` runtime/session data). The **served database files at the `infobaseFile` path are KEPT by default** (`deleteServer` removes only the server config folder, never the served database) — pass `deleteDatabaseFiles=true` to also delete that directory. With `deleteRegistration=true` (the default) the orphaned entry in the standalone-server `infobases.yaml` registry is also cleaned (best-effort; it otherwise self-heals on the next EDT restart). Runs in a background Job (up to 120 s), unattended-safe (no modal).

The tool is guarded by a two-phase workflow (mirroring `delete_project`):
1. **Preview** (`confirm` omitted / false, the default): resolves the application and returns `action='preview'`, `confirmationRequired=true`, the target identifiers, `applicationKind` (for a server), and `deleteRegistration` — WITHOUT changing anything.
2. **Delete** (`confirm=true`): performs the removal; returns `action='deleted'`.

## Parameter details

- **projectName** (required): the EDT configuration project the infobase is bound to.
- **applicationId** (string): application ID from `get_applications`. Either `applicationId` or `infobaseName` is required.
- **infobaseName** (string): display name of the infobase. Used when `applicationId` is not known. Either `applicationId` or `infobaseName` is required.
- **deleteRegistration** (boolean, default true): for a file infobase — also deregister from the global EDT Infobases list; for a standalone server — also clean the orphaned `infobases.yaml` registry entry. false = skip that registry cleanup.
- **deleteDatabaseFiles** (boolean, default false): **true = also delete the infobase database files/directory from disk** (the `1Cv8.1CD` directory) — IRREVERSIBLE. Default false = keep the data on disk (only the registration is removed). Works for both kinds; for a standalone server it deletes the served-database directory (`database.path` = the `infobaseFile`). Guarded for safety on three counts: (1) it only deletes a directory that is actually a 1C infobase (contains a `1Cv8.1CD`) and never a filesystem root — otherwise it skips and reports `databaseFilesDeleted=false`; (2) **shared-infobase guard** — if any OTHER workspace project still references the same on-disk database, the files are KEPT (deleting them would break those projects) and `databaseFilesDeleted=false`; (3) if the directory is locked the delete is best-effort and likewise reports `databaseFilesDeleted=false` with a note to remove it manually. The preview (`confirm` omitted) already reflects whether the files would be deleted or kept-because-shared.
- **confirm** (boolean, default false): false previews; true performs the removal.

## Result

JSON with `action` ('preview'/'deleted'), `confirmationRequired` (preview only), `applicationKind` (standalone-server removals), `project`, `applicationId`, `infobaseName`, `deleteRegistration`, `databaseFilesDeleted` (whether the on-disk database was actually removed), and a `message`.

## Typical usage (round-trip cleanup)

```
# 1. Preview what would be removed.
delete_infobase  projectName="MyProject"  applicationId="<id>"

# 2. Confirm removal (dissociates + deregisters from EDT list).
delete_infobase  projectName="MyProject"  applicationId="<id>"  confirm=true
```

## Gotchas

- **Database files: kept by default, opt in with `deleteDatabaseFiles=true`**: by default this tool removes only registrations — the infobase database files on disk stay (for a `standaloneServer` the server config folder `Серверы/…` is removed, but the served database at `infobaseFile` stays). Pass `deleteDatabaseFiles=true` to ALSO delete the database directory (irreversible); the result's `databaseFilesDeleted` tells you whether it actually went (a locked directory leaves it for manual cleanup).
- **applicationId vs infobaseName**: prefer `applicationId` (from `get_applications`) for precision; `infobaseName` matches by display name and may be ambiguous if two applications share a name.
- **Supported application types**: file infobases (`com.e1c.g5.dt.applications.type.infobase`) and standalone servers (`com.e1c.g5.dt.applications.type.wst-server`). Other types are rejected.
- **Standalone-server registry orphan**: EDT's own server deletion leaves a stale entry in `infobases.yaml`; with `deleteRegistration=true` this tool cleans it (best-effort). It is harmless if not cleaned — it self-heals on the next EDT restart.
- **Standalone-server timeout**: server deletion runs in a background Job with a 120 s budget. If it exceeds that, the tool returns an error stating the platform call **may still be completing in the background** — re-run `get_applications` to check the current state before retrying (it does not claim nothing changed).
- **Other projects unaffected**: dissociating a file infobase from one project does not affect other projects that reference the same infobase. This extends to `deleteDatabaseFiles=true`: if another workspace project still uses the same on-disk database, the files are KEPT (the shared-infobase guard), so a delete never breaks a co-owner.

---
*Generated from the live MCP server (`get_tool_guide`) by `docs/generate_tool_docs.py`. Do not edit this file. Edit the tool's description/schema in its Java source and its guide body in `mcp/bundles/com.ditrix.edt.mcp.server/guides/<tool>.md`.*
