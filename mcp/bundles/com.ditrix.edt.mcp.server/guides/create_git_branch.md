Create a new local git branch - the non-UI sibling of EDT's own "New Branch" command - optionally check it out immediately, and optionally attach an EXISTING infobase to the new branch's context in the same call.

## Parameters
- `branch` is the new branch's SHORT name; it must NOT already exist (checked via `Repository.findRef` before anything else runs - a name that resolves to an existing local branch, remote-tracking branch, or tag is rejected, naming the ref it collided with).
- `startPoint` (optional) is an existing branch, tag, or commit to branch from; defaults to the current HEAD. An unresolvable value is rejected naming it.
- `checkout` (default false): after creating the branch, check it out immediately via the same bounded background Job `switch_git_branch` uses (up to 120 s).
- `applicationId` (optional, from `get_applications`): attach this EXISTING infobase to the NEW branch's context - equivalent to calling `set_branch_infobase` right after this call, but in one round trip. The base is bound to the branch's context regardless of whether `checkout` was also requested.
- `setDefault` (only meaningful with `applicationId`, default false): also make the attached application the branch context's default infobase.

## What happens on success
Once `branch` itself is created, that is FINAL - nothing after it can turn the result into a total failure. The result is:
```json
{ "success": true, "branch": "feature/x", "created": true, "checkedOut": true,
  "startPoint": "HEAD", "bound": { "infobases": ["MyBase"], "defaultInfobase": "MyBase" } }
```
- `checkedOut` is `true` only when `checkout` was requested AND the checkout completed cleanly. If checkout was requested but timed out, was interrupted, or left conflicts, `checkedOut` is `false` and `message` explains why - **the branch still exists**, it is simply not the one checked out.
- `bound` is present only when `applicationId` was given AND the binding succeeded; a resolution or association failure (application not found, no infobase reference, association-manager error) instead appends to `message` and omits `bound` - again, the branch itself is unaffected.
- `startPoint` echoes what was actually used: the given value, or the literal `"HEAD"` when omitted.

## Notes & gotchas
- **Not gated by the destructive-consent dialog.** Creating a local branch is reversible (delete it, or simply never use it) and never opens a 1C infobase connection.
- The already-exists pre-check runs BEFORE any mutation, so a call against a colliding name is a pure no-op (nothing is created, nothing is touched) - safe to retry with a different name.
- If you only need to create the branch (no checkout, no binding), leave `checkout` and `applicationId` out; the call is then a thin wrapper over `Git.branchCreate()`.
- To bind an infobase to a branch WITHOUT creating it (e.g. the branch already exists), use `set_branch_infobase` instead.
- Combine with `switch_git_branch` for the general "switch to an existing branch" case - this tool is specifically for branches that do not exist yet.
