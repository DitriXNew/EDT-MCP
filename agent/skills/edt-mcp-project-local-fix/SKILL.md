---
name: edt-mcp-project-local-fix
description: Apply one bounded BSL correction through EDT-MCP with lost-update protection, targeted validation, and minimal diff. Not for broad refactors or plugin code.
---

# EDT-MCP local BSL fix

## Goal

Change one logical BSL surface while preserving current source, local
conventions, and a clear validation boundary.

## Use when

- the defect and target method are bounded;
- a small query-bearing fragment needs correction;
- the user requested implementation rather than diagnosis only.

## Do not use when

- the change is architectural, cross-module, or metadata-first;
- the target is a form-structure or DCS mutation;
- the user requested only analysis or review.

## Preflight

1. Resolve the exact project, module, and method.
2. Read the target with `read_method_source` and retain `contentHash`.
3. Read adjacent methods only when needed to preserve a local contract.
4. Search for a proven project pattern when the intended implementation is
   uncertain.
5. If the changed fragment contains a 1C query, extract the final query and
   validate it with `validate_query` in the exact project before writing.
6. Call `get_tool_guide('write_module_source')` when the write mode or current
   lost-update contract is uncertain.

## Write

1. Prefer `write_module_source` with a unique search/replace fragment.
2. Pass the expected content hash returned by the read.
3. Keep syntax checking enabled.
4. Use whole-module replacement only when the whole module is intentionally
   replaced and a current lost-update guard is supplied.
5. Do not include adjacent cleanup unless correctness requires it.

## Verification

1. Re-read the changed method.
2. Confirm the intended fragment changed exactly once.
3. When model state requires refresh, run targeted `revalidate_objects` for the
   affected object, then read current markers with `get_project_errors`.
4. Revalidate the final query after writing when query text changed.
5. Run the smallest focused test or runtime probe that proves changed behavior
   when acceptance depends on runtime state.
6. Inspect the final repository diff when Git evidence is available.

## Evidence boundary

- A successful write proves the model accepted the edit, not that the project
  is marker-free.
- Query validation proves syntax and model resolution, not returned rows,
  rights behavior, performance, or business semantics.
- Static validation is not runtime proof.

## Safety

- Do not edit `.bsl` through filesystem tools when EDT-MCP can address it.
- Do not set syntax-check bypasses merely to force a write.
- Do not update an infobase or launch a client unless that runtime action is in
  scope.
- Preserve unrelated user changes and stop on a hash mismatch.

## Stop conditions

Stop before writing when the target fragment is non-unique, the module changed
since it was read, query validation fails, project state is not ready, or the
fix depends on an unresolved design decision.
