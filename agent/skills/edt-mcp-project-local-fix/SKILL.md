---
name: edt-mcp-project-local-fix
description: Apply one bounded BSL correction through EDT-MCP with lost-update protection, targeted validation, and minimal diff. Not for broad refactors or plugin code.
---

# EDT-MCP local BSL fix

## Purpose and trigger

Use this skill when the defect and exact BSL method or small query-bearing
fragment are known and the user requested a bounded correction.

## Operating rule

Read and apply [the common operating rules](../COMMON.md) before this workflow.

## Task boundary

Change one logical BSL surface in the exact project/module/method. Do not expand
into architecture, metadata/form/DCS structure, unrelated cleanup, or plugin
code.

## Primary workflow

1. Resolve and read the exact method with `read_method_source`; retain the
   returned `contentHash` for lost-update protection.
2. Validate a changed 1C query with `validate_query` before writing when
   applicable.
3. Consult `get_tool_guide` for the current write contract, then apply the
   smallest guarded `write_module_source` edit. Mode `replace` overwrites the
   whole module; use it only for an intentional full-module replacement, not a
   method-sized edit.
4. Re-read the method, revalidate any final query, and run targeted
   `revalidate_objects` plus `get_project_errors` after every code write.
5. Inspect the minimal repository diff and add only the focused test or runtime
   probe required by acceptance.

## Authority rule

The request authorizes only the bounded source correction. It does not
authorize infobase updates, client launches, runtime data changes, broader
refactors, or unrelated Git changes.

## Stop rule

Stop before writing on target ambiguity, non-unique replacement, stale
lost-update evidence, failed query validation, unready project state, or an
unresolved design decision.

## Completion signal

Return the exact changed method, minimal diff, successful readback and targeted
validation, focused runtime/test evidence when required, and remaining static
versus runtime gaps.
