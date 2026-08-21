---
name: edt-mcp-project-session
description: Establish the current EDT-MCP server surface, exact 1C project, and safe route before business-project work. Not for developing the EDT-MCP plugin.
---

# EDT-MCP project session

## Purpose and trigger

Use this skill to establish the exact EDT-MCP route, project, and required
capability before another business-project workflow begins.

## Operating rule

- Use current MCP tool help/schema and repository tool documentation as the authority for parameters, limits, side effects, returned identifiers, errors, and recovery.
- This skill supplies task routing and essential ordering only; do not invent undocumented behavior or copy tool contracts into the workflow.
- On ambiguity, an unexpected state or error, unclear target/ownership, or a user-affecting/destructive action, stop and consult the authoritative help. If the safe action remains unclear or needs permission, ask the user.
- Report only results confirmed by tool output.

## Task boundary

Discover only facts needed for the current 1C project task. Do not use this
skill for EDT-MCP plugin development or repeat preflight whose evidence remains
current.

## Primary workflow

1. Reuse valid session evidence; otherwise call `list_projects` to resolve the
   exact project and kind.
2. When proxy routing matters, call `router_status`; use `get_server_status`
   only when installed surface or EDT state affects the task.
3. If a required capability is hidden, inspect `list_toolsets` and use
   `enable_toolset` only as current help permits. Follow the client's supported
   catalog-refresh or reconnect route; do not assume either behavior.
4. Call `get_tool_guide` for the selected unfamiliar, destructive, or cascading
   operation rather than preloading unrelated guides.
5. Hand off the exact project and visible capability to one primary task skill.

## Authority rule

Discovery does not authorize project mutation, destructive operations, runtime
effects, optional Git/Workmate use, or substitution of a different project.

## Stop rule

Stop when project ownership or route is ambiguous, the required capability
remains unavailable, or the next operation lacks a proven target or authority.

## Completion signal

Record the selected project, project kind when relevant, routed server surface,
required visible capability, chosen task skill, and any unresolved ambiguity.
