---
name: edt-mcp-project-maintenance
description: "Perform bounded EDT project maintenance through current EDT-MCP: diagnostics, revalidation, clean/resync, applications, database update, import/export, and Git when enabled."
---

# EDT-MCP project maintenance

## Goal

Restore or update one exact EDT project with the narrowest operation that
matches the diagnosed state, while exposing disk/model/runtime side effects.

## Use when

- validation is stale or a quick fix is requested;
- model and disk need an explicit clean or resync;
- applications, launch configurations, or infobase bindings are managed;
- a database update or import/export is explicitly required;
- project Git tools are intentionally enabled.

## Do not use when

- a local BSL or metadata write is sufficient;
- a full clean is proposed without diagnosis;
- destructive project/infobase deletion lacks explicit authority.

## Diagnose first

Use `get_project_errors`, `get_problem_summary`, and targeted
`revalidate_objects` before broad maintenance. Use `apply_quick_fix` only for
the exact current marker and re-read validation afterward.

## Clean and resync

`clean_project` refreshes disk into the model and triggers broad validation; it
is not a harmless save. Pass the exact project unless the entire workspace is
explicitly in scope, and account for unsaved editor/model state.

Consult `get_tool_guide('resync_to_disk')` before use. It exports only the
addressed project's model to disk: missing objects by default, or every object
with `fullExport`. With `revalidate=true`, it schedules a clean build for that
same project only. If a base configuration and extensions also need cleaning
or revalidation, resolve every project and target each one explicitly with the
appropriate tool.

## Applications and infobases

Use `get_applications` and `list_configurations` to establish identity. Current
tools can create/register file infobases and standalone-server applications,
store credentials, and create launch configurations. These operations alter
EDT application state and sometimes filesystem/runtime state; use exact
targets and current guides.

## Database update

1. Resolve the application and inspect its update state.
2. Review `get_tool_guide('update_database')`.
3. Preview, then confirm the same update intent.
4. Choose external-infobase-change policy deliberately.
5. Do not terminate clients or reassign standalone-server ports without
   authority.
6. For standalone servers, prefer coordinated `debug_launch` or YAXUnit launch
   with update enabled; a bare update starts the server in run mode.
7. Verify final update state and later asynchronous failure surfaces.

## Background jobs

When an operation returns `jobId`, poll it with `get_job_status`. Do not reissue
the start call to discover a known job's state. Use `cancel_job` only under its
current confirmation and cancellation-capability contract.

## Git and destructive operations

Choose the Git isolation mode before changing branches.

### Mode A: free current working tree

Use the current working tree only when the exact project tree is clean, free
from another task, and no parallel work is required.

1. Inspect repository status and diff, then call `list_git_branches` and verify
   the current branch and project-policy start point.
2. For a missing task branch, call `create_git_branch` with an explicit verified
   `startPoint` and `checkout=true`.
3. Verify `success=true`, `created=true`, `checkedOut=true`, and re-read
   `list_git_branches` to confirm the new branch is current.
4. For an existing local branch, use `switch_git_branch`, not branch creation.
5. Use `set_branch_infobase` only to bind or unbind an existing infobase when a
   branch-specific binding is actually required.

### Mode B: parallel or foreign work

This mode is mandatory when another task owns the project, unrelated tracked
changes exist, or parallel work is requested. Do not switch the active project
branch, and do not stash, reset, commit, discard, or move foreign changes. Use
a separate linked Git worktree through the route allowed by project policy.

The current EDT-MCP branch tools operate on an existing project working tree
and may not create linked worktrees. Missing `git worktree` support is an MCP
capability limitation, not an architectural prohibition. Use the
project-approved Git route for the missing operation. If that route lacks the
required authority, stop with one concrete question; do not demand that the
owner commit unrelated work.

Before any EDT-MCP mutation, import or open the linked checkout as a distinct
EDT workspace project through a distinct server route and give it a unique
`projectName`. Re-run project discovery and verify that this project resolves
to the intended linked-worktree path. Never reuse a `projectName` belonging to
the active or another foreign checkout. A technical project that merely links
`src` from the worktree is not sufficient unless the checkout itself is
resolved and shared correctly. If a distinct route to the intended worktree
cannot be established, stop and ask a concrete question instead of mutating
the active project.

For an EDT/EGit-shared project, prefer the typed Git tools. The general MCP
`git` tool is project-routed and cannot be used to open an unregistered or
unshared linked worktree. If the current MCP surface does not support a
required repository operation, use system Git only with explicit authorization
and only inside the exact linked worktree, when project policy permits it.
Perform all 1C artifact work through the distinct EDT project route.

When the general `git` tool is deliberately enabled, follow its current guide
and the project's branch/publish policy. Import/export, project deletion,
infobase deletion, and deletion of database files require exact targets,
preview where supported, rollback/backup information, and explicit authority.

## Verification and stop conditions

Report before/after model, disk, validation, application, and runtime state.
Stop on ambiguous direction, unsaved state that may be discarded, unknown
application identity, an unreviewed destructive preview, or a background job
whose final state is not known.
