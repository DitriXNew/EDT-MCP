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

Consult `get_tool_guide('resync_to_disk')` before use. Its modes can write
model state to disk or clean related projects; choose the direction from
evidence rather than habit.

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

EDT-MCP Git tools may be disabled. When enabled, inspect status and diff first
and follow the project's branch policy. Import/export, project deletion,
infobase deletion, and deletion of database files require exact targets,
preview where supported, rollback/backup information, and explicit authority.

## Verification and stop conditions

Report before/after model, disk, validation, application, and runtime state.
Stop on ambiguous direction, unsaved state that may be discarded, unknown
application identity, an unreviewed destructive preview, or a background job
whose final state is not known.
