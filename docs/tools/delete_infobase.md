# delete_infobase

Remove a FILE infobase association from a configuration project OR delete a standalone (autonomous) server application. Destructive: guarded by a confirm-preview - call without confirm to preview what would be removed (no change), then confirm=true to delete. For a file infobase: dissociates it and (deleteRegistration, default true) deregisters it from the EDT infobases list. For a standalone server (applicationKind=standaloneServer): stops it and removes the WST server with its config folder and served database. The inverse of create_infobase. Full parameters and examples: call get_tool_guide('delete_infobase').

## Parameters
| Parameter | Required | Type | Description |
| --- | --- | --- | --- |
| projectName | yes | string | EDT configuration project the infobase is bound to (required). |
| applicationId | — | string | Application ID from get_applications. Either applicationId or infobaseName is required. |
| infobaseName | — | string | Display name of the infobase to remove. Either applicationId or infobaseName is required. |
| deleteRegistration | — | boolean | true = also deregister the infobase from the global EDT infobases list (equivalent to 'Delete' in the Infobases view); default true. |
| confirm | — | boolean | true = perform the removal; default false = preview only (no change). |

## Guide
Removes a FILE infobase association from a configuration project, OR deletes a standalone (autonomous) server application. The inverse of `create_infobase` (both `applicationKind=infobase` and `applicationKind=standaloneServer`) and the cleanup step for a create-infobase round-trip. The tool auto-detects the application kind from the resolved application — no extra parameter is needed.

## Think twice — destructive (confirm-preview)

**File infobase** (`applicationKind=infobase`): dissociating removes it from `get_applications` for the project (other projects that share the infobase are unaffected). With `deleteRegistration=true` (the default) it is also removed from the EDT Infobases view; the infobase **files on disk are NOT deleted** — delete the directory manually to reclaim space.

**Standalone server** (`applicationKind=standaloneServer`): this mirrors EDT's "Delete server" action — it **stops the server and removes the WST server registration plus its server config folder** (the `Серверы/…` runtime/session data). The **served infobase database files at the `infobaseFile` path are NOT deleted** (same as the file-infobase path) — delete that directory manually to reclaim space. With `deleteRegistration=true` (the default) the orphaned entry in the standalone-server `infobases.yaml` registry is also cleaned (best-effort; it otherwise self-heals on the next EDT restart). Runs in a background Job (up to 120 s), unattended-safe (no modal).

The tool is guarded by a two-phase workflow (mirroring `delete_project`):
1. **Preview** (`confirm` omitted / false, the default): resolves the application and returns `action='preview'`, `confirmationRequired=true`, the target identifiers, `applicationKind` (for a server), and `deleteRegistration` — WITHOUT changing anything.
2. **Delete** (`confirm=true`): performs the removal; returns `action='deleted'`.

## Parameter details

- **projectName** (required): the EDT configuration project the infobase is bound to.
- **applicationId** (string): application ID from `get_applications`. Either `applicationId` or `infobaseName` is required.
- **infobaseName** (string): display name of the infobase. Used when `applicationId` is not known. Either `applicationId` or `infobaseName` is required.
- **deleteRegistration** (boolean, default true): for a file infobase — also deregister from the global EDT Infobases list; for a standalone server — also clean the orphaned `infobases.yaml` registry entry. false = skip that registry cleanup.
- **confirm** (boolean, default false): false previews; true performs the removal.

## Result

JSON with `action` ('preview'/'deleted'), `confirmationRequired` (preview only), `applicationKind` (standalone-server removals), `project`, `applicationId`, `infobaseName`, `deleteRegistration`, and a `message`.

## Typical usage (round-trip cleanup)

```
# 1. Preview what would be removed.
delete_infobase  projectName="MyProject"  applicationId="<id>"

# 2. Confirm removal (dissociates + deregisters from EDT list).
delete_infobase  projectName="MyProject"  applicationId="<id>"  confirm=true
```

## Gotchas

- **Database files are NOT deleted**: this tool never deletes the infobase database files from disk. For `applicationKind=infobase` the file infobase stays on disk; for a `standaloneServer` the server config folder (`Серверы/…` runtime data) IS removed, but the **served database at `infobaseFile` stays** — delete those directories manually to reclaim space.
- **applicationId vs infobaseName**: prefer `applicationId` (from `get_applications`) for precision; `infobaseName` matches by display name and may be ambiguous if two applications share a name.
- **Supported application types**: file infobases (`com.e1c.g5.dt.applications.type.infobase`) and standalone servers (`com.e1c.g5.dt.applications.type.wst-server`). Other types are rejected.
- **Standalone-server registry orphan**: EDT's own server deletion leaves a stale entry in `infobases.yaml`; with `deleteRegistration=true` this tool cleans it (best-effort). It is harmless if not cleaned — it self-heals on the next EDT restart.
- **Standalone-server timeout**: server deletion runs in a background Job with a 120 s budget. If it exceeds that, the tool returns an error stating the platform call **may still be completing in the background** — re-run `get_applications` to check the current state before retrying (it does not claim nothing changed).
- **Other projects unaffected**: dissociating a file infobase from one project does not affect other projects that reference the same infobase.

---
*Generated from the live MCP server (`get_tool_guide`) by `docs/generate_tool_docs.py`. Do not edit this file. Edit the tool's description/schema in its Java source and its guide body in `mcp/bundles/com.ditrix.edt.mcp.server/guides/<tool>.md`.*
