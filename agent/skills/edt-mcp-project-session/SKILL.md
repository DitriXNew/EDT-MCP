---
name: edt-mcp-project-session
description: Establish the current EDT-MCP server surface, exact 1C project, and safe route before business-project work. Not for developing the EDT-MCP plugin.
---

# EDT-MCP project session

## Purpose and trigger

Use this skill to establish the exact EDT-MCP route, project, and required
capability before another business-project workflow begins.

## Operating rule

Read and apply [the common operating rules](../COMMON.md) before this workflow.

## Task boundary

Discover only facts needed for the current 1C project task. Do not use this
skill for EDT-MCP plugin development or repeat preflight whose evidence remains
current.

## Primary workflow

1. Reuse valid session evidence. On an unfamiliar workspace, use the bounded
   onboarding route: `get_edt_version` -> `list_projects` ->
   `get_configuration_properties` -> `get_problem_summary`, then
   `list_subsystems` only when project navigation needs it. Resolve the exact
   project and kind before continuing.
2. When proxy routing matters, call `router_status`; use `get_server_status`
   only when installed surface or EDT state affects the task.
3. If a required capability is hidden, inspect `list_toolsets` and use
   `enable_toolset` only as current help permits. Follow the client's supported
   catalog-refresh or reconnect route; do not assume either behavior.
4. Call `get_tool_guide` for the selected unfamiliar, destructive, or cascading
   operation rather than preloading unrelated guides.
5. Prefer counts, filters, narrow fields, and paginated reads before full
   payloads. Preserve cursors and truncation evidence; fetch another page only
   when the task's conclusion requires it.
6. Resolve metadata by programmatic `Name`, never by localized synonym. Only
   the FQN type token is bilingual (for example `Catalog`/`Справочник`).
7. Hand off the exact project and visible capability to one primary task skill.

## Authority rule

Discovery does not authorize project mutation, destructive operations, runtime
effects, optional Git/Workmate use, or substitution of a different project.

## Stop rule

Stop when project ownership or route is ambiguous, the required capability
remains unavailable, or the next operation lacks a proven target or authority.

## Completion signal

Record the selected project, project kind when relevant, routed server surface,
required visible capability, chosen task skill, and any unresolved ambiguity.
