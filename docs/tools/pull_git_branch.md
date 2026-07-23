# pull_git_branch

Fetch a branch from a remote and integrate it into the project's current branch (merge, or rebase when requested) - the non-UI 'Pull'. remote and branch are BOTH required and never defaulted. SSH auth is transparent (ssh-agent); HTTPS uses the optional username/token, and never opens a login dialog. A merge/rebase conflict is returned as an actionable error (with the conflicting paths), not a false success. Runs in a background Job (up to 120 s) and refreshes the project. Full parameters and examples: call get_tool_guide('pull_git_branch').

## Parameters
| Parameter | Required | Type | Description |
| --- | --- | --- | --- |
| projectName | yes | string | EDT project whose git repository to pull into (required). |
| remote | yes | string | Remote to pull from (required): a configured remote name (e.g. 'origin') or a URL. Never defaulted. |
| branch | yes | string | Remote branch name to fetch and integrate (required, e.g. 'main'). Never defaulted. |
| rebase | — | boolean | Integrate by rebasing the current branch onto the fetched commits instead of merging. Default false (merge). |
| username | — | string | Optional HTTPS username. Only used for an HTTPS remote; SSH remotes authenticate via ssh-agent and ignore it. Omit for SSH. |
| token | — | string | Optional HTTPS token or password paired with username. When both are omitted for an HTTPS remote, the pull fails fast with an actionable error instead of prompting. |

## Guide
Fetch a branch from a remote and integrate it into the project's currently checked-out branch - the non-UI sibling of EDT's own "Pull". Use `get_git_status` first to confirm the working tree is in the state you expect, and `list_git_branches` to see what is checked out.

## Parameters
- `projectName` (**required**) - the EDT project whose git repository to pull into.
- `remote` (**required, never defaulted**) - a configured remote name (e.g. `origin`) or a URL. There is deliberately no "infer the upstream" default: a pull changes your working tree, so you always name exactly where it comes from.
- `branch` (**required, never defaulted**) - the remote branch to fetch and integrate (e.g. `main`).
- `rebase` (optional, default `false`) - when `true`, rebase the current branch onto the fetched commits instead of merging.
- `username` / `token` (optional) - HTTPS credentials, see **Authentication** below.

## Authentication (non-interactive - never opens a dialog)
- **SSH remotes** (`git@host:org/repo.git`) authenticate **transparently** through EGit's process-global SSH session factory - your loaded `ssh-agent` key and `~/.ssh` config. Pass nothing; `username`/`token` are ignored for SSH.
- **HTTPS remotes** use an **explicit** credentials provider built from `username` + `token`. Supply a token/password, not your account password, for hosts that require it.
- When an HTTPS remote needs credentials but none were supplied, the pull **fails fast with an actionable error** - it never blocks on a modal login/passphrase prompt (this tool is unattended-safe). No secret is stored.

## What happens
The fetch and integration run in a **bounded background Job (up to 120 seconds)** - never on the UI thread. On completion the SAME Job refreshes the project (`DEPTH_INFINITE`) so the EDT model reflects the updated working tree, **including** when a merge/rebase left conflict markers on disk.

On a clean pull the result is:
```json
{ "success": true, "remote": "origin", "branch": "main", "rebase": false,
  "fetchedFrom": "origin", "status": "FAST_FORWARD" }
```
- `status` is the merge status (`FAST_FORWARD` / `ALREADY_UP_TO_DATE` / `MERGED` ...) for a merge pull, or the rebase status (`UP_TO_DATE` / `FAST_FORWARD` / `OK` ...) for a rebase pull.
- `fetchedFrom` is the remote name/URI JGit actually fetched from.
- A `message` field appears only when the post-pull workspace refresh failed; the pull itself still succeeded (`success` stays `true`).

## A non-clean integration is an error, never a false success
When the integration does not complete cleanly the result is `success: false` with an actionable error naming the real JGit status - it is **never** reported as a success. The message and remedy are chosen by the *actual* status, not lumped together as "conflict":
- **Conflict** (merge `CONFLICTING`, or rebase `STOPPED` / `CONFLICTS`) - the working tree now has conflict markers (a rebase is left **paused**). Resolve them (or reset / abort) before retrying. The error echoes the conflicting paths (bounded to ~20, with an `...and N more` remainder).
- **Uncommitted changes** (rebase `UNCOMMITTED_CHANGES`) - the rebase was refused because the working tree is dirty. **Commit or stash** the local changes (or pull with `rebase: false` to merge), then retry. The error lists the uncommitted paths.
- **Checkout would overwrite** (merge `CHECKOUT_CONFLICT`) - local changes would be overwritten by the incoming checkout. **Commit or stash** them, then retry. The error lists the blocking paths.
- **Failed** (`FAILED`) - the operation failed for some paths (e.g. dirty or locked files). Resolve them and retry; the error lists the failing paths.
- **Autostash conflict** (rebase `STASH_APPLY_CONFLICTS`) - the rebase completed but **re-applying** your autostashed changes conflicted. This is an error (JGit itself reports it as "successful"): resolve the conflict markers and commit. JGit saved the autostashed changes to the git stash (`refs/stash`) and does **not** drop them automatically - drop them manually (`git stash drop`) once verified, or they may be re-applied later.
- **Incomplete merge** (merge `MERGED_NOT_COMMITTED` / `MERGED_SQUASHED` / `MERGED_SQUASHED_NOT_COMMITTED` / `FAST_FORWARD_SQUASHED`) - a `merge --squash` / `--no-commit` configuration merged but left the result **staged, not committed**, so the branch tip is unchanged. JGit reports this "successful", but the tool returns an error so you never push a stale tip: commit the pending merge (with `commit_git_changes`, or a manual `git commit` - add `--allow-empty` for a squash merge - for the rare identical-tree case that stages nothing) before pushing.
- **Anything else** - the error names the status and points you to `get_git_status` to inspect the local state.

## Notes & gotchas
- **Requires a git working tree.** The project must be EGit-shared or simply live inside a `.git` clone - see `list_git_branches` for the resolution rules and the actionable error when neither applies.
- **openWorldHint = true**: this tool reaches an external remote. It never opens or authenticates against a 1C infobase.
- Network/transport failures (unknown remote, unreachable host, auth rejected) come back as a single actionable error naming the remote/branch and the likely fix - retry after correcting the remote, the credentials, or the network.
- To publish local commits the other direction, use `push_git_branch`; to record local changes first, use `commit_git_changes`.

---
*Generated from the live MCP server (`get_tool_guide`) by `docs/generate_tool_docs.py`. Do not edit this file. Edit the tool's description/schema in its Java source and its guide body in `mcp/bundles/com.ditrix.edt.mcp.server/guides/<tool>.md`.*
