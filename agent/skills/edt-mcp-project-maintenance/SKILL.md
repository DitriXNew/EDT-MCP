---
name: edt-mcp-project-maintenance
description: "Perform bounded EDT project maintenance through current EDT-MCP: diagnostics, revalidation, clean/resync, applications, database update, import/export, and Git when enabled."
---

# EDT-MCP project maintenance

## Purpose and trigger

Use this skill for bounded project diagnostics, revalidation, clean/resync,
application maintenance, database update, configuration import/export, or Git
operations when enabled.

## Operating rule

- Use current MCP tool help/schema and repository tool documentation as the authority for parameters, limits, side effects, returned identifiers, errors, and recovery.
- This skill supplies task routing and essential ordering only; do not invent undocumented behavior or copy tool contracts into the workflow.
- On ambiguity, an unexpected state or error, unclear target/ownership, or a user-affecting/destructive action, stop and consult the authoritative help. If the safe action remains unclear or needs permission, ask the user.
- Report only results confirmed by tool output.

## Task boundary

Target one exact EDT project, application, repository tree, or caller-approved
import/export location. Route business code and metadata changes to their task
skills; do not broaden maintenance to the workspace or unrelated projects.

## Primary workflow

1. Diagnose with `get_project_errors`, `get_problem_summary`, and targeted
   `revalidate_objects`; use `apply_quick_fix`, `clean_project`, or
   `resync_to_disk` only when current evidence and help select that operation.
   Apply a quick fix only for an authorized exact marker and variant, then
   inspect its diff. Before `clean_project`, prove the exact project has no
   unsaved in-memory model edits; preserve any through the current supported,
   authorized save/resync route, or stop if safety cannot be proved. Always pass
   the exact project; omission rebuilds every EDT project.
2. Resolve application identity with `get_applications` and
   `list_configurations`. For `update_database`, review the current preview and
   side effects, obtain authority, execute once, and verify the final state.
3. Poll a returned job only with `get_job_status`; use `cancel_job` only under
   its current authorization and confirmation contract.
4. For import, confirm a caller-approved XML source plus a new project target,
   call `import_configuration_from_xml`, then verify the returned project exists
   and becomes usable. For export, confirm the exact project and approved output
   directory, call `export_configuration_to_xml`, then verify the reported
   destination and expected export result.
5. Before using the current working tree, inspect actual repository status and
   diff through the project-approved Git route and prove it clean, free, and not
   needed for parallel work. Use `list_git_branches` only for branch/binding
   identity, then use `create_git_branch` or `switch_git_branch` as appropriate.
   A task branch must be checked out explicitly; verify the returned
   `checkedOut`/current branch before any mutation because branch creation alone
   leaves the working tree on its previous branch by default.
6. When the active project has foreign changes or parallel work is required,
   do not switch it. Use a separate Git worktree through the project-approved
   Git route and resolve that checkout as a distinct EDT project before MCP
   mutation. Lack of MCP worktree support is a capability limitation, not a
   prohibition; use an authorized route or stop with a concrete question.

## Authority rule

Database/project/file deletion, update/restructure, import/export overwrite,
credential storage, launch termination, branch switching, publication, and any
operation affecting foreign work require explicit authority for the exact
target and effect.

## Stop rule

Stop on ambiguous direction or identity, unsaved/foreign state at risk,
unreviewed destructive effects, unsupported worktree routing, or a background
job whose final state is unknown.

## Completion signal

Report exact targets, before/after project/application/Git state, diagnostics,
job finality, verified import/export destination or project, and any operation
intentionally not performed.
