Stores the **infobase connection credentials** (user / password) EDT uses to authenticate the 1C designer agent that runs the pre-launch DB update for `update_database` and `launch`. Needed when the target infobase has a **user list** (issue #194): without stored credentials the agent is started without the infobase user, fails to authenticate, and the platform pops a blocking "Configure Infobase access Settings" dialog that hangs an unattended call.

## Two consumers, two stores — read this before choosing a target (issue #359)

A launch has **two** processes that authenticate, and they read the user from **different places**:

| Process | Reads its user from | Configured by |
|---|---|---|
| the designer **agent** (pre-launch DB update, `update_database`, `launch`) | EDT's per-infobase **access settings** | every call of this tool |
| the launched 1C **client** (`run_yaxunit_tests`, `launch`, any launch) | the **launch configuration's own attributes** ("Client application user" section of the launch dialog) | **only** a call that targets by `launchConfigurationName` |

So a call targeting by `projectName` + `applicationId` configures the agent **only**. It returns `success: true` and `clientConfigured: false`, and it leaves the launched client's own settings exactly as they were — so unless somebody filled that section in by hand earlier, the client keeps popping the platform's "Infobase access" login dialog. **Target by `launchConfigurationName` whenever you want the launch itself to stop asking for a password** — that call writes both, and reports `clientConfigured: true` (the one exception is a **shared** launch configuration with a non-empty password, which is refused — see below).

**Also works for a standalone server (issue #275):** the target application does not have to be a plain file/server infobase (`IInfobaseApplication`) — a `standaloneServer` (`wst-server`) application that wraps an already-registered infobase is supported too. EDT's own launch path for such an application resolves the infobase to authenticate against by ADAPTING the application (or its module) to an `InfobaseReference` (`org.eclipse.core.runtime.Adapters`); this tool stores credentials against that SAME adapted reference, so a later `launch`/`update_database` on the server authenticates with them. An application that is neither an infobase nor an adaptable standalone server is rejected with an actionable error naming the application id.

## What these credentials are (and are not)

- They select an **EXISTING** infobase user to connect AS — they do **NOT** create infobase users. The user must already exist in the infobase (added via the configurator's Administration → Users, or BSL `ПользователиИнформационнойБазы`).
- An **empty password is valid** — demo bases typically ship a user (e.g. `Администратор` / `Admin`) with an empty password. It is written as an empty value rather than skipped, so it overwrites a stale password instead of leaving one behind.
- The **agent** half is stored in EDT's per-infobase access settings (the same store the configurator's credentials dialog writes to), keyed by the infobase — EDT's encrypted Secure Storage. They persist across restarts.
- The **client** half is stored as attributes of the launch configuration itself. ⚠️ A **local** launch configuration lives in the **workspace metadata** (`.metadata/.plugins/org.eclipse.debug.core/.launches/*.launch`), **not** in EDT's Secure Storage, so the password is written there in the clear — the same place the launch dialog puts it when a human fills that section in by hand. If that is unacceptable, target by `projectName` + `applicationId` (agent only) and set the client's user in the launch dialog yourself, or use `access='OS'`, which stores no password at all.
- 🚫 A **shared** launch configuration (launch dialog → Common → "Shared file") is not workspace metadata at all: its `.launch` file is an ordinary resource **inside the project**, and therefore normally **committed to version control**. Writing a password there would publish it to everyone who clones the repository, so this tool **refuses** that write: the call still succeeds (the agent half is stored), and it comes back with `clientConfigured: false` and a message naming the file. Make the configuration local, or use `access='OS'`, or pass an empty password — all three write the client half normally, because none of them puts a secret in the file.

## Targeting

Identify the application the same way as `update_database`:

- **`launchConfigurationName`** (preferred) — the exact runtime-client config name from `list_configurations`; the project + applicationId are derived from it. **This is the only shape that also configures the launched client** (see the table above).
- or **`projectName` + `applicationId`** — `applicationId` comes from `get_applications`. Configures the **agent only**; no launch configuration is touched, so the client's own settings stay whatever they were.

## What the client half writes

Targeting by `launchConfigurationName` sets the launch dialog's **"Client application user"** section:

- `access='INFOBASE'` (default) → the **infobase user** radio, with the given user and password;
- `access='OS'` → the **OS authentication** radio, and the user/password are **cleared** (the three radios are mutually exclusive, so a stale user must not be left behind);
- in both cases the **"use the infobase access settings"** radio is switched **off** — those settings are the agent's, and leaving the client pointed at them is exactly what made the login dialog appear.

⚠️ This **overwrites** whatever that section held. If a launch configuration is deliberately set to "use the infobase access settings" or to a different user, targeting it by name changes that.

## Parameters

- **launchConfigurationName** (optional): runtime-client config name; preferred target, and the only one that configures the launched client.
- **projectName** + **applicationId** (optional): the direct target when no launch config name is given; agent only.
- **user** (optional): the infobase user name to authenticate as. Empty stores no-user credentials (OS-authenticated or userless base, or to reset).
- **password** (optional, default empty): the user's password. Empty is valid.
- **access** (optional, `INFOBASE` | `OS`, default `INFOBASE`): `INFOBASE` = 1C user authentication (user/password); `OS` = operating-system authentication.

## Result

JSON with `success`, `clientConfigured`, `project`, `applicationId`, `applicationName`, the stored `user`, `access`, and `passwordSet` (whether a non-empty password was stored — the password itself is never returned). `applicationName` falls back to the `applicationId` when the friendly display name cannot be read back (e.g. the read-back is skipped after a timeout, or the application name is empty).

`clientConfigured` reports what **this call** did to the client half — not what a launch will do:

- **`true`** — a launch configuration was named and its client-user section was written. The client now authenticates from its own settings instead of prompting, provided what was stored is valid for this infobase (with `access='OS'` no user or password is stored at all — the OS identity is used).
- **`false`** — either no launch configuration was named (`projectName` + `applicationId` target), or writing it failed. The `message` says which, and the agent-side credentials are stored either way. Note that `false` does **not** prove the client will prompt: somebody may have filled that section in by hand earlier.

The two writes are not atomic (EDT's Secure Storage and a launch configuration are separate stores). The agent half is committed **first**, and the client half is skipped once the call has stopped waiting — so a call that answers `success: false`, including one that gave up on a slow platform before the agent half committed, has left the launch configuration untouched. The reverse residue — agent stored, client not — is always reported through `clientConfigured` + `message`. In the one instant where the call gives up *between* the two writes it answers `clientConfigured: false` with "the outcome was not known", which deliberately **under-claims**: that write may still have gone through.

## Typical workflow

```
# An infobase that requires authentication (a user 'Admin' with an empty password):
1. set_infobase_credentials  projectName="ERP"  applicationId=<id from get_applications>  user="Admin"
2. update_database           projectName="ERP"  applicationId=<same id>  confirm=true
#    -> the update agent now authenticates as Admin; no credentials dialog.
#    -> clientConfigured=false: a LAUNCH would still ask. See the next example.

# The launch itself must stop asking (the case issue #359 is about) — target by config NAME:
set_infobase_credentials  launchConfigurationName="ERP - thin client"  user="Admin"  password="secret"
#    -> clientConfigured=true; run_yaxunit_tests / launch no longer pop the login dialog.
```

## Storing credentials from the EDT GUI (when MCP is idle)

You do not have to use this tool. When the **MCP server is idle**, you can open EDT's built-in **"Configure Infobase access"** dialog by hand (the same dialog the configurator uses) and enter the user / password there. EDT stores them in its encrypted **Secure Storage** — the leak-free path — and `update_database` / `launch` pick them up exactly as if you had called this tool. This works because the auto-cancel below is **activity-scoped**: it fires only while an MCP tool is running, so a human configuring credentials between agent runs is never interrupted.

## Auto-cancel of the login dialog (unattended safety)

While an MCP tool is in flight (plus a short grace window for the asynchronous read-back that follows a tool), the server **auto-cancels** the "Configure Infobase access Settings" login dialog so an unattended call never blocks on it — the operation instead fails fast with a hint back to this tool. This auto-cancel is **on by default** and **activity-scoped** — it is NOT always-on, so an idle server leaves the GUI dialog usable (see above). To turn it off — e.g. to debug the login flow interactively — set **`EDT_MCP_SUPPRESS_AUTH_DIALOG=false`** (also `0` / `no`) on the EDT process before launch; any other value, or leaving it unset, keeps auto-cancel enabled.

## Gotchas

- **The user must exist in the infobase.** Storing credentials for a user that does not exist makes the next connect fail authentication (while a tool is running the MCP server auto-cancels the resulting dialog — see the auto-cancel note above — and the operation fails fast with a hint back to this tool). Add the user first, then set credentials.
- **`create_infobase` can store credentials too** (its `user`/`password`/`access` parameters) — handy with `mode='register'` (the existing base already has users), including `applicationKind='standaloneServer'` + `mode='register'` (issue #275). For a brand-new `mode='create'` base (file infobase or standalone server) there are no users yet, so set credentials only after adding a matching user — `create_infobase` rejects credentials up front for a newly created standalone server.
- **Wrong password / wrong user** → `update_database`/`launch` fail fast with "the infobase requires authentication — set the connection credentials with set_infobase_credentials" instead of hanging.
- **`success: true` does NOT mean the launch will stop asking** — check `clientConfigured`. Storing the agent's credentials and then hitting the client's "Infobase access" dialog on the next `run_yaxunit_tests` is issue #359; the fix is to target by `launchConfigurationName`, not to retry.
- **The client half is per launch configuration.** Several configurations against the same infobase each need their own call (or their own edit in the launch dialog) — the agent-side settings are shared, the client-side attributes are not.
- These are connection credentials, not a permission grant: the user's rights inside the infobase are unchanged.

## Which side gets configured

With launchConfigurationName the launched 1C CLIENT is configured too, so it stops asking for a password (issue #359); with `projectName` + `applicationId` only the agent is - check `clientConfigured` in the result.
