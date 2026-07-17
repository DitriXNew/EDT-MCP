List a project's git branches (local and remote-tracking), flag the current one (and detached HEAD), and show the 1C application/infobase binding each branch **context** carries, when EDT tracks one.

## When to use
- Before `switch_git_branch`, to see which branches exist and confirm the exact name to pass.
- To check whether the currently checked-out branch matches what you expect, or whether HEAD is detached.
- To see which infobase(s) a branch (or the project's default context) is bound to before switching, so you know what will change.

## How the repository is located
1. The EGit team-provider mapping for the project (`RepositoryMapping.getMapping`) - the normal case for a project inside a git working tree that has been **shared** with Git in EDT.
2. A filesystem fallback: walks up from the project's location looking for a `.git` directory. This works even when the project was never explicitly EGit-shared, but the **Application Bindings** section then has nothing to read (binding is wired by the team-provider "share", not by the repository alone) and reports "bindings unavailable".

If neither resolves, the tool returns an actionable error naming the project and suggesting `Team -> Share Project`.

## What you get
A Markdown report:
- **Current** - the checked-out branch's short name, or `(detached HEAD at <commit>)` when HEAD does not point at a branch tip.
- A branch table: `Branch | Type | Current` - `Type` is `local` (`refs/heads/...`), `remote` (`refs/remotes/...`), or `other` (e.g. a tag ref, rare in this list); `Current` is `Yes` on the checked-out branch's row (never set when detached).
- An **Application Bindings** table: `Branch Context | Infobases | Default` - one row per context `IInfobaseAssociationManager` tracks for the project. `(default)` means the context with no branch name (the project-level binding used when no branch-specific override exists). `Infobases` lists the bound infobase names (or `(none)`); `Default` names the context's default infobase when set.

## Notes & gotchas
- Read-only: never touches the BM model, never mutates the repository or the workspace.
- The **Application Bindings** section degrades gracefully to a `*bindings unavailable: <why>*` note (service absent, project not shared, or a lookup failure) instead of failing the whole call - the branch list above it is always trustworthy on its own.
- The binding is **derived live** from whatever branch is currently checked out (`Repository.getFullBranch()`), so after `switch_git_branch` succeeds the bindings you see here (or in that tool's own read-back) reflect the NEW branch automatically - no separate "re-bind" step is needed.
- `get_applications` is the sibling tool for the currently-active application/infobase list independent of git; this tool's bindings section is about which infobase(s) are **associated with each branch context**, not which one is currently connected.
