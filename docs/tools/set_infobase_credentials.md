# set_infobase_credentials

Store infobase connection credentials (user/password) so update_database and debug_launch can authenticate the update agent on an infobase that has a user list (issue #194). Selects an EXISTING infobase user (does not create users); an empty password is valid (demo bases). Target by launchConfigurationName (preferred) or projectName + applicationId (from get_applications). Full parameters and examples: call get_tool_guide('set_infobase_credentials').

## Parameters
| Parameter | Required | Type | Description |
| --- | --- | --- | --- |
| launchConfigurationName | — | string | Exact runtime-client config name from list_configurations (preferred target). |
| projectName | — | string | EDT project name; required if launchConfigurationName is omitted. |
| applicationId | — | string | Application ID from get_applications; required if launchConfigurationName is omitted. |
| user | — | string | Infobase user name to authenticate as (an EXISTING user). Optional: empty stores no-user credentials (OS-authenticated or userless base / reset). |
| password | — | string | Infobase user password. Optional; default empty (demo bases use an empty password). |
| access | — | string (one of: INFOBASE, OS) | Authentication kind: 'INFOBASE' (default, 1C user auth) or 'OS' (OS authentication). |

## Guide
Stores the **infobase connection credentials** (user / password) EDT uses to authenticate the 1C designer agent that runs the pre-launch DB update for `update_database` and `debug_launch`. Needed when the target infobase has a **user list** (issue #194): without stored credentials the agent is started without the infobase user, fails to authenticate, and the platform pops a blocking "Configure Infobase access Settings" dialog that hangs an unattended call.

## What these credentials are (and are not)

- They select an **EXISTING** infobase user to connect AS — they do **NOT** create infobase users. The user must already exist in the infobase (added via the configurator's Administration → Users, or BSL `ПользователиИнформационнойБазы`).
- An **empty password is valid** — demo bases typically ship a user (e.g. `Администратор` / `Admin`) with an empty password.
- Stored in EDT's per-infobase access settings (the same store the configurator's credentials dialog writes to), keyed by the infobase. They persist across restarts.

## Targeting

Identify the application the same way as `update_database`:

- **`launchConfigurationName`** (preferred) — the exact runtime-client config name from `list_configurations`; the project + applicationId are derived from it.
- or **`projectName` + `applicationId`** — `applicationId` comes from `get_applications`.

## Parameters

- **launchConfigurationName** (optional): runtime-client config name; preferred target.
- **projectName** + **applicationId** (optional): the direct target when no launch config name is given.
- **user** (optional): the infobase user name to authenticate as. Empty stores no-user credentials (OS-authenticated or userless base, or to reset).
- **password** (optional, default empty): the user's password. Empty is valid.
- **access** (optional, `INFOBASE` | `OS`, default `INFOBASE`): `INFOBASE` = 1C user authentication (user/password); `OS` = operating-system authentication.

## Result

JSON with `success`, `project`, `applicationId`, `applicationName`, the stored `user`, `access`, and `passwordSet` (whether a non-empty password was stored — the password itself is never returned).

## Typical workflow

```
# An infobase that requires authentication (a user 'Admin' with an empty password):
1. set_infobase_credentials  projectName="ERP"  applicationId=<id from get_applications>  user="Admin"
2. update_database           projectName="ERP"  applicationId=<same id>  confirm=true
#    -> the update agent now authenticates as Admin; no credentials dialog.

# Non-empty password, targeting by launch config:
set_infobase_credentials  launchConfigurationName="ERP - thin client"  user="Admin"  password="secret"
```

## Gotchas

- **The user must exist in the infobase.** Storing credentials for a user that does not exist makes the next connect fail authentication (the MCP server auto-cancels the resulting dialog and the operation fails fast with a hint back to this tool). Add the user first, then set credentials.
- **`create_infobase` can store credentials too** (its `user`/`password`/`access` parameters) — handy with `mode='register'` (the existing base already has users). For a brand-new `mode='create'` base there are no users yet, so set credentials only after adding a matching user.
- **Wrong password / wrong user** → `update_database`/`debug_launch` fail fast with "the infobase requires authentication — set the connection credentials with set_infobase_credentials" instead of hanging.
- These are connection credentials, not a permission grant: the user's rights inside the infobase are unchanged.

---
*Generated from the live MCP server (`get_tool_guide`) by `docs/generate_tool_docs.py`. Do not edit this file. Edit the tool's description/schema in its Java source and its guide body in `mcp/bundles/com.ditrix.edt.mcp.server/guides/<tool>.md`.*
