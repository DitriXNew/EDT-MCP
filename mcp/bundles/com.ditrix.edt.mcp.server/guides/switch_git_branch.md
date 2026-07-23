Switch a project's git repository to another branch via a headless EGit checkout - the non-UI sibling of EDT's own "Switch to" command. Use `list_git_branches` first to see what exists and confirm the exact name.

## Parameters
- `branch` accepts either a short local name (`feature/x`) or a full ref (`refs/heads/feature/x`). A **remote-tracking** ref (e.g. resolving to `refs/remotes/origin/feature/x`) is rejected: create a local branch tracking it first (EDT's Team menu, or a git client), then switch to that local branch. This tool never auto-creates a tracking branch.

## Pre-checks (before any mutation)
Each has its own actionable error, checked in this order:
1. **The branch must resolve** (`Repository.findRef`) - an unknown name is rejected, naming both the bad value and the currently checked-out branch.
2. **Must not already be on it** - switching to the current branch is rejected rather than silently no-op-succeeding.
3. **The working tree must have no uncommitted changes** (`git status`: staged or unstaged modifications, additions, removals). **Untracked files alone do NOT block** a switch - only tracked-file changes do, matching plain `git checkout` semantics.

## What happens on success
The checkout runs in a bounded background Job (up to 120 seconds) - never on the UI thread. On success the result is:
```json
{ "success": true, "previousBranch": "main", "branch": "feature/x",
  "bindings": { "infobases": ["MyBase"], "defaultInfobase": "MyBase" } }
```
`bindings` is a **best-effort** read-back of the CURRENT application/infobase association after the switch - proof that the 1C application context followed the checkout automatically (EDT derives it live from the checked-out branch). It is omitted when no binding manager/association is available; a missing `bindings` key does not mean the switch failed.

## Checkout failure modes
- **CONFLICTS** - your uncommitted local changes would be overwritten by the target branch; the working tree is left on the **ORIGINAL** branch (JGit's checkout semantics), and the error lists the conflicting paths (bounded to ~20).
- **NONDELETED** - the checkout logically succeeded but some files could not be removed (typically locked or otherwise in-use); the error lists them (bounded to ~20).
- Any other non-OK status surfaces a generic error naming the status and reminding you the tree may still be on the original branch.

## Notes & gotchas
- **Not gated by the destructive-consent dialog.** A branch switch is reversible (switch back with another call), and the clean-working-tree pre-check above is the actual safety net - not a human prompt.
- **Never opens or authenticates against a 1C infobase.** Only the association manager's already-resolved bindings are read; no infobase connection is made by this tool.
- The project must be inside a git working tree (EGit-shared, or simply a project whose location sits inside a `.git` clone) - see `list_git_branches` for the resolution rules and its own actionable error when neither applies.
- A remote-only repository (no locally-tracked branches at all) has nothing this tool can switch to until a local branch exists.
