---
name: edt-mcp-project-query-dcs
description: Locate, inspect, validate, and safely change 1C queries or Data Composition Schemas through EDT-MCP. Separates supported schema writes from runtime data proof.
---

# EDT-MCP query and DCS workflow

## Purpose and trigger

Use this skill to locate, inspect, validate, or change a 1C query or supported
Data Composition Schema surface.

## Operating rule

- Use current MCP tool help/schema and repository tool documentation as the authority for parameters, limits, side effects, returned identifiers, errors, and recovery.
- This skill supplies task routing and essential ordering only; do not invent undocumented behavior or copy tool contracts into the workflow.
- On ambiguity, an unexpected state or error, unclear target/ownership, or a user-affecting/destructive action, stop and consult the authoritative help. If the safe action remains unclear or needs permission, ask the user.
- Report only results confirmed by tool output.

## Task boundary

Resolve the exact project, owning method or Report FQN, dataset, and business
grain. Route form-only work elsewhere and never edit `.dcs` directly to bypass
an unsupported structured operation.

## Primary workflow

1. Locate the owner with `search_in_code`, `get_module_structure`,
   `read_method_source`, or `get_metadata_details`.
2. Read the complete owning method or dataset and validate the complete query
   with `validate_query` in the exact project.
3. Preserve business grain and cardinality, then apply the smallest supported
   source route or `modify_metadata` DCS mutation after consulting current help.
4. Re-read the owner, validate the final query, and inspect targeted
   `get_project_errors` when markers matter.
5. Run an authorized report/runtime check only when rows, totals, RLS,
   parameters, performance, or presentation are part of acceptance.

## Authority rule

Do not broaden source/DCS changes, bypass access restrictions, execute against
runtime data, or alter report settings beyond the authorized target.

## Stop rule

Stop on ambiguous ownership, unresolved model validation, unsupported DCS
write capability, unsafe cardinality, or missing runtime target/read authority.

## Completion signal

Return the exact owner and dataset, confirmed source/schema diff, successful
readback and static validation, business-grain reasoning, and explicit gaps.
Static validation never proves returned rows, totals, RLS, performance, or UI.
