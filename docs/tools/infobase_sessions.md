# infobase_sessions

List or terminate sessions on a running standalone-server infobase. DESTRUCTIVE for terminate: pass confirm=true; bulk termination skips Designer, while its exact full UUID can target it. Full parameters and examples: call get_tool_guide('infobase_sessions').

## Parameters
| Parameter | Required | Type | Description |
| --- | --- | --- | --- |
| projectName | yes | string | EDT project whose standalone-server application owns the sessions (required). |
| applicationId | — | string | Application ID from get_applications; defaults to the project's default application. |
| action | — | string (one of: list, terminate) | list (default) reads sessions; terminate ends the selected session(s). |
| sessionId | — | string | For terminate, the full session UUID or numeric session-id returned by list; a Designer session requires its exact full UUID. |
| all | — | boolean | For terminate, true selects every non-agent session; Designer is always excluded. |
| confirm | — | boolean | Required true for terminate; list never changes sessions. |
| message | — | string | Optional text shown to a terminated user through ibcmd --error-message. |

## Guide
Lists or terminates live sessions reported by `ibcmd` for a running EDT standalone-server application.

## Scope and reachability

This tool works only with applications whose EDT type is `wst-server` (standalone server). It uses the runtime and live process owned by EDT; it does not inspect file-infobase or client/server applications.

`action='list'` has three meaningful outcomes:

- `reachable=true` with one or more `sessions` — the list was read;
- `reachable=true` with `sessions=[]` — the list was read and proves there are no sessions;
- `reachable=false` with `unreachableReason` and no `sessions` field — the application is the wrong type, the standalone server is not running, the runtime is unknown, `ibcmd` is missing, or the command failed.

Never interpret `reachable=false` as an empty session list. Fix the named reason or decide explicitly whether the caller can proceed without proof.

Each session reports `sessionId` (full UUID), `sessionNumber` (numeric `session-id`), raw `applicationKind` (`app-id`), `userName`, `host`, `startedAt`, `lastActiveAt`, and `isEdtAgent`.

There is no OS process id: `ibcmd` leaves the `process` and `connection` fields empty for standalone-server sessions, so none is reported. Terminating the session ends the client process it belongs to, which is what a process id would have been used for.

## The ambiguous Designer session

An `app-id: Designer` session can be EDT's configurator/update agent OR a human Configurator; EDT exposes no discriminator, so the tool cannot tell them apart. The compatibility field `isEdtAgent=true` therefore means the raw application kind is `Designer`, not that EDT ownership was proved.

Treating every Designer session as a blocker would refuse every normal EDT update. For that reason `update_database` does not block on it and `all=true` always skips it. You can terminate one only as an explicit per-id act using its exact full session UUID. If it is EDT's agent, EDT re-creates it on its next connect; an update running at the moment you terminate it can fail.

## Parameters

- `projectName` (required) — the EDT configuration project.
- `applicationId` — an ID from `get_applications`; omitted means the project's default application.
- `action` — `list` (default) or `terminate`.
- `sessionId` — for `terminate`, either the full UUID or numeric session number returned by `list`. A Designer session requires its exact full UUID.
- `all` — for `terminate`, `true` selects every non-agent session. Use exactly one of `sessionId` or `all=true`.
- `confirm` — must be `true` for `terminate`.
- `message` — optional text passed to `ibcmd --error-message` and shown to the terminated user.

## Examples

List before updating:

```text
infobase_sessions(action='list', projectName='MyProject', applicationId='ServerApplication.MyServer')
```

Terminate one non-agent session by its numeric ID:

```text
infobase_sessions(action='terminate', projectName='MyProject', applicationId='ServerApplication.MyServer', sessionId='42', confirm=true, message='Database maintenance is starting')
```

Clear all non-agent sessions before retrying `update_database`:

```text
infobase_sessions(action='terminate', projectName='MyProject', applicationId='ServerApplication.MyServer', all=true, confirm=true)
```

Termination is verified, not assumed. `ibcmd` exits 0 even for a session UUID that no longer exists, so the tool re-reads the session list afterwards and reports only sessions observed gone. `verification` says which happened:

- `verified` — every targeted session is absent from the re-read list;
- `mismatched` — a session is still present after a terminate that reported success; this is an error, and those sessions still block an update;
- `not_verifiable` — the list could not be re-read, with `attemptedCount` and `verificationReason` naming what was attempted and why it could not be checked. `sessions` and `terminatedCount` are omitted because none was observed gone; list again before treating the infobase as clear.

If a multi-session termination stops partway, the error carries `mutationCommitted=true`, `terminatedCount`, and the already terminated session records.

---
*Generated from the live MCP server (`get_tool_guide`) by `docs/generate_tool_docs.py`. Do not edit this file. Edit the tool's description/schema in its Java source and its guide body in `mcp/bundles/com.ditrix.edt.mcp.server/guides/<tool>.md`.*
