---
name: edt-mcp-project-query-dcs
description: Locate, inspect, validate, and safely change 1C queries or Data Composition Schemas through EDT-MCP. Separates supported schema writes from runtime data proof.
---

# EDT-MCP query and DCS workflow

## Purpose and trigger

Use this skill to locate, inspect, validate, or change a 1C query or supported
Data Composition Schema surface.

## Operating rule

Read and apply [the common operating rules](../COMMON.md) before this workflow.

## Task boundary

Resolve the exact project, owning method or Report FQN, dataset, and business
grain. Route form-only work elsewhere and never edit `.dcs` directly to bypass
an unsupported structured operation.

## Primary workflow

1. Locate the owner with `search_in_code`, `get_module_structure`,
   `read_method_source`, or `get_metadata_details`.
2. Read the complete owning method or dataset and validate the complete query
   with `validate_query` in the exact project.
3. Preserve business grain and cardinality. Hand an embedded BSL query mutation
   to `edt-mcp-project-local-fix` and require its guarded lost-update workflow;
   use `modify_metadata` for a supported DCS mutation.
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
