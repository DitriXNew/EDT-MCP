# create_git_repository

Bootstrap a git repository for an EDT project. Without 'url': git init at an EXISTING open project's location and connect it to EGit (rejected if the project is already inside a git working tree). With 'url' + 'targetPath': clone the remote on a bounded background Job, then import and connect the project. SSH auth is transparent (ssh-agent / ~/.ssh); HTTPS uses optional username/token and never opens a login dialog. Full parameters and examples: call get_tool_guide('create_git_repository').

## Parameters
| Parameter | Required | Type | Description |
| --- | --- | --- | --- |
| projectName | yes | string | For init: the EXISTING open EDT project to initialize a repository at (required). For clone: the name to import the cloned project under when the cloned tree has no own .project (required). |
| url | — | string | Remote repository URL to clone (SSH or HTTPS). When given, the tool runs in CLONE mode; when omitted, in INIT mode. |
| targetPath | — | string | Clone mode only (required there): an absolute local directory to clone into. It must not already exist as a non-empty directory. |
| remoteName | — | string | Clone mode only: the git remote name to register. Optional; defaults to 'origin'. |
| initialBranch | — | string | Init mode only: the initial branch name for the new repository (e.g. 'main'). Optional; defaults to git's configured default. |
| username | — | string | Optional HTTPS username for the clone. Omit for SSH (handled transparently by the ssh-agent) or for anonymous/public HTTPS. |
| token | — | string | Optional HTTPS token/password for the clone, paired with username. With neither given, an HTTPS remote that requires credentials fails fast (no interactive prompt). |

## Guide
Bootstrap a git repository for an EDT project - the non-UI sibling of EDT's "Share Project" and "Clone" commands, and the entry point of the git dev-loop family (`commit_git_changes`, `push_git_branch`, `pull_git_branch`, `get_git_status`, plus the branch tools `list_git_branches` / `create_git_branch` / `switch_git_branch`).

There are two modes, chosen by whether you pass a remote `url`:

## Init mode (no `url`)
Runs `git init` at an EXISTING open project's own filesystem location, then connects the project to the EGit team provider so every other git tool resolves it.

- `projectName` (required) is the open EDT project to initialize a repository at.
- `initialBranch` (optional) sets the initial branch name (e.g. `main`); omit for git's configured default. An invalid ref name is rejected naming it.
- **Rejected up front** when the project is already inside a git working tree (a walk-up `findGitDir` match) - initializing a nested repository there would be a mistake. The error names the discovered `.git` directory; use `get_git_status` / `list_git_branches` to inspect the existing repository instead.

## Clone mode (`url` given)
Clones the remote into `targetPath` on a bounded background Job, then imports the cloned project and connects it to EGit.

- `url` (required for clone) is the remote URL, SSH or HTTPS.
- `targetPath` (required for clone) is an absolute local directory to clone into; it must not already exist as a non-empty directory.
- `remoteName` (optional) is the git remote name to register; defaults to `origin`.
- If the cloned tree contains its own `.project`, that project (under its own name) is imported; otherwise a project named `projectName` is created at the clone location.
- **Name collision:** if a *different* workspace project already owns that name (at another location), the clone is left on disk but **not** imported (`imported: false`, with a note in `message`) - the tool never adopts the unrelated project. Import it manually via Team -> Import.

## Authentication (unattended-safe)
- **SSH** is transparent: EGit's process-global SSH session factory uses your `ssh-agent` and `~/.ssh` - pass no credentials.
- **HTTPS** uses the optional `username` + `token` params. With neither supplied, an HTTPS remote that requires credentials **fails fast with an actionable error** - no interactive login/passphrase dialog is ever opened.

## What happens on success
The init/clone step is the irreversible one; once the repository exists on disk the call is a success:
```json
{ "success": true, "mode": "init", "project": "MyProject",
  "repositoryPath": "C:/ws/MyProject/.git", "shared": true }
```
- `shared` is `true` only when the EGit connect succeeded. If it failed, `shared` is `false` and `message` explains why - **the repository still exists and is usable** (`get_git_status` / `commit_git_changes` work; even without the share, the repository is still found via filesystem discovery). You can share it manually via Team -> Share Project.
- **Clone mode adds `imported`** (a boolean). `imported: true` means the cloned tree was brought in as a workspace project - the `project` field then names it, and `get_git_status` / `commit_git_changes` can target it (subject to `shared`). `imported: false` (a name collision or an import failure) means the repository is on disk but **no** project was imported: there is **no** `project` field and you must NOT reuse the requested name with another git tool (it would point at the unrelated colliding project). The `message` says what to do.
- In clone mode the result also echoes `remoteUrl`; a non-fatal import or workspace-refresh problem is a note in `message`, never a total failure.

## Notes & gotchas
- **Not gated by the destructive-consent dialog.** Creating a repository is additive and never opens a 1C infobase connection.
- The clone network op is time-bounded (120 s) and runs off the UI thread; a timeout is an actionable error, not a hang.
- This tool bootstraps the repository only. To record changes use `commit_git_changes`, to publish use `push_git_branch`, and to fetch-and-merge use `pull_git_branch`.
- Commit/status/push/pull operate on the ON-DISK content; save or `resync_to_disk` the EDT model before committing so your model edits are captured.

---
*Generated from the live MCP server (`get_tool_guide`) by `docs/generate_tool_docs.py`. Do not edit this file. Edit the tool's description/schema in its Java source and its guide body in `mcp/bundles/com.ditrix.edt.mcp.server/guides/<tool>.md`.*
