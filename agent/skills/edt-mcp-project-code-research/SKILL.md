---
name: edt-mcp-project-code-research
description: Investigate 1C BSL behavior, dependencies, impact, and implementation differences through EDT-MCP. Read-only by default; not for plugin development.
---

# EDT-MCP code research

## Purpose and trigger

Use this skill to produce a bounded, evidence-backed explanation of 1C project
code, dependencies, impact, or implementation differences.

## Operating rule

- Use current MCP tool help/schema and repository tool documentation as the authority for parameters, limits, side effects, returned identifiers, errors, and recovery.
- This skill supplies task routing and essential ordering only; do not invent undocumented behavior or copy tool contracts into the workflow.
- On ambiguity, an unexpected state or error, unclear target/ownership, or a user-affecting/destructive action, stop and consult the authoritative help. If the safe action remains unclear or needs permission, ask the user.
- Report only results confirmed by tool output.

## Task boundary

Keep the task read-only and tied to exact project, metadata FQN, module, method,
or symbol targets. Route a known bounded correction to
`edt-mcp-project-local-fix`; do not turn research into implementation without
authorization.

## Primary workflow

1. Resolve candidates with `get_metadata_objects`, `list_modules`, and
   `search_in_code`.
2. Narrow with `get_module_structure`, then prefer `read_method_source` over
   `read_module_source` unless module-level context is required.
3. Follow only the relationships needed using `go_to_definition`,
   `find_references`, `get_method_call_hierarchy`, `get_outgoing_structures`,
   or `get_symbol_info`.
4. Treat every single-hop or depth-1 caller result as a lower bound. When the
   conclusion requires completeness, use the current completeness-capable
   route from the authoritative guide and report any remaining gaps.
5. Treat structured-output analysis and cross-project impact as potentially
   partial; inspect the bounded source and relevant projects before claiming a
   complete contract.
6. Use `get_platform_documentation` when the conclusion depends on platform
   behavior rather than project code.

## Authority rule

Do not write code, metadata, runtime data, or Git state. Request a new task
boundary before any implementation or runtime experiment.

## Stop rule

Stop when the exact target cannot be resolved, required project evidence is
unavailable, or the conclusion needs unauthorized runtime proof.

## Completion signal

Return exact targets, the evidenced call/data flow, relevant dependencies and
exceptions, impact classification, partial-result caveats, unknowns, and the
smallest useful next validation step.
