# infobase_extensions

List installed infobase extensions and install, update or uninstall .cfe files. DESTRUCTIVE: call once WITHOUT confirm to preview a write, then with confirm=true and expectedHash from the preview's contentHash; writes require user consent; update verification requires a changed expectedVersion. Full parameters and examples: call get_tool_guide('infobase_extensions').

## Parameters
| Parameter | Required | Type | Description |
| --- | --- | --- | --- |
| projectName | yes | string | Configuration project owning the application. |
| applicationId | yes | string | Exact target ID from get_applications; never inferred. |
| action | — | string (one of: list, install, update, uninstall) | Default list. |
| extensionName | — | string | Required for writes: exact programmatic extension name. |
| cfePath | — | string | Required for install/update: absolute path on the EDT host. |
| safeMode | — | boolean | Install defaults true; update preserves the installed value. |
| protectionFromDangerousActions | — | boolean | Install defaults true; update preserves the installed value. |
| expectedVersion | — | string | Optional exact read-back version; update verification requires a value different from the installed version. |
| confirm | — | boolean | Default false previews; true requests consent and executes the write. |
| expectedHash | — | string | Required with confirm=true: contentHash from the preview; changed inputs require a fresh preview. |
| timeoutSeconds | — | integer | Operation deadline, 10..600; default 120. A timeout does not imply rollback. |

## Guide
Manage compiled configuration extensions directly in an infobase, without importing an extension
project into the workspace. Resolve `applicationId` with `get_applications` first. This tool uses
EDT's installed platform resolution, stored infobase access settings and Designer connection pool.
It requires a platform supporting Designer extension operations. It never targets the base
configuration and never terminates sessions automatically.
For standalone-server applications, an unreadable session inventory or a non-Designer session
blocks writes. A Designer session is reported only by kind; it is not assumed to belong to EDT.

## Parameter details

- `projectName` and `applicationId` are required, including for reads. The application is never inferred.
- `action` is `list` (default), `install`, `update` or `uninstall`.
- Writes require `extensionName`: the exact programmatic name, not a synonym or a guessed file stem.
- Install/update require `cfePath`: an existing, non-empty `.cfe` on the EDT host, addressed by absolute path.
- Install defaults `safeMode` and `protectionFromDangerousActions` to true. Update preserves each omitted
  flag and the installed active state. Explicit false is supported only through the same consent gate.
- `expectedVersion` optionally requires an exact version in the post-operation read-back. Supply it
  for versioned deployment acceptance. Updates additionally require a transition from the installed
  version to this value. If the other checks match but that evidence is missing, they return
  `success:false`, `verification:"notVerified"` even if the operation returned normally. A read-back
  mismatch remains `mismatched`. Neither a version nor an inventory hash proves binary identity.
- `confirm` defaults false. Request the preview first, back up the infobase, then repeat with true
  and `expectedHash` set to the preview's `contentHash`. The shared `ContentHash` staleness check binds
  the project/application, action, exact name,
  effective flags, expected version, source path, complete inventory and SHA-256 of the staged `.cfe`.
  Changed inputs require a fresh preview. A stale hash is rejected before
  consent or mutation; per-session consent allowances do not bypass this check.
  Windowed consent also applies; `confirm=true` does not bypass it. The headless host always allows.
- `timeoutSeconds` defaults 120 (10..600). Application lookup has its own 30-second bound.

## Calls

```json
{"projectName":"MyProject","applicationId":"<id from get_applications>","action":"list"}
```

```json
{"projectName":"MyProject","applicationId":"<id from get_applications>","action":"install","extensionName":"EDTMCP_Runtime","cfePath":"D:/extensions/EDTMCP_Runtime.cfe","expectedVersion":"1.0.0"}
```

Repeat the install with `confirm:true` and `expectedHash:"<contentHash from preview>"` after reviewing
the preview. For an existing extension use `action:"update"` and the new file/version; install
refuses to overwrite an installed name. Update
refuses to create an absent name. Uninstall also requires a preview and consent:

```json
{"projectName":"MyProject","applicationId":"<id from get_applications>","action":"uninstall","extensionName":"EDTMCP_Runtime"}
```

## Verification and failures

List returns `extensions` and `count`; every record contains the actual name, version, active state,
safeMode and protectionFromDangerousActions. Empty is a measured inventory, never a connection failure.

Writes reread the inventory after consent and after the Designer operation. `verification:"verified"`
means presence/absence, requested runtime flags, preserved active state, unchanged other extensions,
and `expectedVersion` if supplied all matched. An update additionally requires a transition from the
previous version to `expectedVersion`; without that evidence it is `notVerified`. `actualState` carries
the observed extension after install/update; `extensionInstalled:false` proves absence after uninstall.
This does not prove binary
file identity or runtime applicability in a newly opened application session.

Mismatch is an error, even if the Designer returned normally. A platform exception or timeout never
becomes success. `mutationOutcomeUnknown:true` means the infobase may already have changed; cancellation
does not roll back a load, restructuring or deletion. The worker retains the shared application lock
until it exits. Inspect `action:"list"` and the EDT Error Log before retrying. Fix credentials with
`set_infobase_credentials`; inspect busy standalone-server sessions with `infobase_sessions` and obtain
separate approval before terminating them.

Uninstall can permanently delete extension data. Recovery requires a backup; reinstalling a `.cfe`
does not restore deleted data. File-name inference, standalone flag-only edits and automatic
session termination are outside this initial contract.

Preview and confirmed install/update calls fingerprint a worker-owned temporary copy; confirmation loads
only a copy whose digest matches the preview snapshot. The copy is captured before consent, so changing
the source file while the consent dialog is open cannot substitute the bytes sent to Designer.
The copy remains owned by the worker until the operation finishes and is then removed.
The staged SHA-256 is rechecked after consent and admission checks, immediately before loading.
A local writer can still change bytes between that check and Designer opening the file.

---
*Generated from the live MCP server (`get_tool_guide`) by `docs/generate_tool_docs.py`. Do not edit this file. Edit the tool's description/schema in its Java source and its guide body in `mcp/bundles/com.ditrix.edt.mcp.server/guides/<tool>.md`.*
