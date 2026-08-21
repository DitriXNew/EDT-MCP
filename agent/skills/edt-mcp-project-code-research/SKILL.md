---
name: edt-mcp-project-code-research
description: Investigate 1C BSL behavior, dependencies, impact, and implementation differences through EDT-MCP. Read-only by default; not for plugin development.
---

# EDT-MCP code research

## Goal

Produce a bounded, evidence-backed map of a 1C mechanism without loading the
whole project or turning research into implementation.

## Use when

- locating an entry point or explaining current behavior;
- tracing callers, definitions, structured outputs, or exception routes;
- preparing a change or impact analysis;
- comparing current and reference implementations;
- assembling a compact context bundle for one feature.

## Do not use when

- the exact target is known and the request is one bounded correction; use
  `edt-mcp-project-local-fix`;
- the task is solely a form layout or DCS investigation;
- the user already requested implementation and no research uncertainty
  remains.

## Workflow

1. Normalize the business term into exact project, metadata FQN, module, and
   method names.
2. Locate candidates with `get_metadata_objects`, `list_modules`, and
   `search_in_code`.
3. Inspect `get_module_structure` before reading bodies.
4. Prefer `read_method_source`; use `read_module_source` only for module-level
   initialization, variables, directives, or ordering.
5. Preserve a returned `contentHash` if research may lead to a later write.
6. Resolve only the relationships needed for the conclusion:
   - `go_to_definition`;
   - `find_references`;
   - `get_method_call_hierarchy` with bounded depth;
   - `get_outgoing_structures` for structured contracts;
   - `get_symbol_info` when the symbol identity is unclear.
7. Use `get_platform_documentation` when the conclusion depends on a platform
   API rather than project code.
8. Inspect exception, logging, fallback, transaction, and client/server routes
   explicitly when they affect behavior.
9. For a comparison, keep the reference side read-only and compare contracts,
   not formatting.
10. Stop with findings and unknowns unless implementation was requested.

## Impact analysis

For a proposed change, define the exact signature, FQN, form member, query
field, or property first. Classify each impact as proven static, likely,
runtime-only, or unknown. Static traversal can miss dynamic calls, strings,
event subscriptions, functional options, runtime-selected forms, and external
integrations.

## Evidence classification

- **Platform**: confirmed platform or EDT contract.
- **SSL/BSP**: confirmed call into a public library mechanism.
- **Configuration-specific**: tied to project metadata or private code.
- **Reusable pattern**: portable without private state.
- **Uncertain**: evidence is insufficient.

Do not classify from naming alone.

## Verification

Return exact targets, entry points, main call/data flow, client/server context,
dependencies, exceptions, likely change seams, existing tests, runtime gaps,
and the smallest useful validation plan.

## Safety and stop conditions

Do not modify code in a research-only task. Stop when the target cannot be
resolved, a required project is unavailable, or the conclusion would require
runtime evidence that the current scope does not authorize.
