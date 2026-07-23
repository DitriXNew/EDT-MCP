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
