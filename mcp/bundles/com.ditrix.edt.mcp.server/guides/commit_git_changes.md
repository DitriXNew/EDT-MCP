Commit a project's ON-DISK git changes: stage the requested content, then record a commit and return its SHA. This is the write half of the git dev-loop (`get_git_status` reads the working tree, `commit_git_changes` records it, `push_git_branch` publishes it).

## Parameters
- `projectName` (required) is the EDT project whose git repository to commit into. The repository is located exactly as `get_git_status`/`switch_git_branch` do (the EGit team-provider mapping, else `.git`-dir discovery from the project's location).
- `message` (required) is the commit message; it must not be blank. Cyrillic (or any UTF-8) is preserved verbatim.
- `all` (default false): stage every TRACKED file that is modified or deleted before committing - the `git commit -a` behaviour for tracked files. New/untracked files are NOT swept in by `all`; list them in `paths` if you want them.
- `paths` (optional): explicit repo-relative paths to stage before committing. Each path is staged like `git add <path>` - new, modified, and deleted content alike. Combine `paths` with `all`, or use it instead. If NEITHER `all` nor `paths` is given, only the content already staged in the index is committed.

## What happens on success
```json
{ "success": true, "commitId": "3f9a1c2e…(40 hex)", "branch": "feature/x", "stagedFiles": 4 }
```
- `commitId` is the full 40-hex SHA-1 of the new commit (`RevCommit.getName()`).
- `branch` is the branch the commit landed on (the commit SHA instead, when HEAD is detached).
- `stagedFiles` is the number of index entries the commit recorded.

## Notes & gotchas
- **ON-DISK content only.** The commit sees what is written to disk, exactly as `git` does. A metadata or BSL edit still living in EDT's in-memory model is INVISIBLE to the commit. **Save (or call `resync_to_disk`) FIRST**, then commit - otherwise you will commit a stale or empty tree. Call `get_git_status` to confirm the working tree holds what you expect before committing.
- **"Nothing to commit" is a real error, never a fake success.** If, after staging, the index is identical to HEAD, the tool refuses with an actionable error and records NOTHING - it never creates an empty commit. Pass `all=true` or list `paths[]` to stage something first.
- **Committer identity is required.** If the repository has no `user.name`/`user.email` configured (locally or globally), the tool refuses with an error naming exactly which key to set, rather than letting git synthesise an anonymous machine identity. Set them with `git config user.name "<name>"` / `git config user.email "<email>"` (add `--global` to set once for all repositories).
- **No authentication, no network, no background job.** Committing is a purely local, on-disk operation that runs inline - unlike `push_git_branch`/`pull_git_branch`. It changes nothing that EDT reads back, so there is no workspace refresh.
- Next step: `push_git_branch` to publish the commit to a remote (it requires an explicit remote and branch - there is no autonomous push).
