---
name: edt-mcp-project-session
description: Establish the current EDT-MCP server surface, exact 1C project, and safe route before business-project work. Not for developing the EDT-MCP plugin.
---

# EDT-MCP project session

## Goal

Establish only the current facts needed for the task. Do not dump the complete
tool catalog or repeat bootstrap calls while the evidence remains current.

## Use when

- the target EDT project is unknown or ambiguous;
- more than one EDT instance may be connected through a proxy;
- a required tool is not visible;
- the installed server version or enabled surface matters to a failure;
- another project skill needs a reliable preflight.

## Do not use when

- the project and tool surface were already established in this session;
- the task is about implementing or testing the EDT-MCP plugin itself;
- the missing fact can be resolved by a narrower task skill.

## Minimal preflight

1. Reuse valid current-session evidence.
2. Call `list_projects` when the exact EDT project is not known.
3. If proxy router tools are exposed and routing matters, inspect
   `router_status` once.
4. Call `get_server_status` only when version, EDT state, progressive
   disclosure, or renderer flags matter.
5. If a capability is hidden, inspect `list_toolsets`. Use `enable_toolset`
   only when the current client supports dynamic MCP tool-catalog refresh,
   then refresh its catalog. A server-side visibility change does not make a
   newly revealed tool callable in clients that materialize schemas only at
   session start. For those clients, configure server-side visibility before
   a new handshake, then reconnect or restart the client/session; if that is
   not possible, report the limitation instead of depending on the tool.
6. Use `get_tool_guide` for an unfamiliar operation or before the first
   destructive or cascading mutation in the task.

## Identity model

Keep these identifiers separate:

- EDT project name;
- metadata FQN;
- base configuration versus configuration extension;
- external-object project versus its `ExternalDataProcessor` or
  `ExternalReport` object;
- application or infobase identifier;
- EDT launch configuration name.

An external-object project has no ordinary configuration root. Current
metadata tools resolve its top objects through the project's metadata scope;
address those objects by their qualified FQNs and pass the external project as
`projectName`.

## Routing rules

- Pass the exact `projectName` on project-scoped calls.
- If duplicate project names are reported across EDT instances, stop until the
  route is unambiguous.
- Do not assume a fixed server port, tool count, toolset membership, or client
  namespace.
- Do not require Progressive Disclosure; the complete tool surface may be
  visible from session start.
- A hidden tool is not necessarily unsupported, but it may remain unavailable
  for the current session when the client catalog cannot refresh dynamically.
  An administratively disabled tool cannot be enabled by repeated discovery
  calls.
- Optional tools such as Git or Workmate are not prerequisites for ordinary
  project work.

## Economy

- Read one method before one module.
- Read one metadata object before a subsystem or configuration.
- Read a compact form layout before a full layout or screenshot.
- Do not bulk-call `get_tool_guide`.

## Verification

Before handing control to another task skill, record the selected project, its
kind when relevant, the visible required capability, and any routing ambiguity.

## Stop conditions

Stop and report the exact missing fact when project ownership is ambiguous, the
required tool remains absent or disabled after proper discovery, a destructive
operation lacks authority, or the write target cannot be proven.
