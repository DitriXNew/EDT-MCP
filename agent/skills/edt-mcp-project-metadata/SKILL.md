---
name: edt-mcp-project-metadata
description: Inspect and safely change 1C metadata, including roles, rights, and RLS, through current EDT-MCP structured operations. Not for raw XML edits or plugin development.
---

# EDT-MCP metadata workflow

## Purpose and trigger

Use this skill to inspect or change exact 1C metadata objects, members, roles,
rights, RLS, or extension composition through structured EDT-MCP operations.

## Operating rule

Read and apply [the common operating rules](../COMMON.md) before this workflow.

## Task boundary

Resolve the exact project and metadata FQN. Use `edt-mcp-project-forms` for
managed-form structure and `edt-mcp-project-query-dcs` for DCS content; never
edit metadata XML to bypass the structured surface.

## Primary workflow

1. Resolve metadata by programmatic `Name`, never by localized synonym. Only
   the FQN type token is bilingual (for example `Catalog`/`Справочник`). Read
   the target with `get_metadata_details`, including the current writable
   surface when needed.
2. Use `find_references` only for targets supported by its current help; use the
   relevant mutation preview and bounded consumer checks for other targets.
3. Consult the current guide, preview destructive or cascading effects, then
   use the smallest `create_metadata`, `modify_metadata`,
   `rename_metadata_object`, `delete_metadata`, or `adopt_metadata_object`
   operation.
4. For roles and RLS, follow the current guide's ordering and effective-rights
   model, re-read the complete affected state, and treat partial application as
   possible until readback proves otherwise.
5. For extension work, identify the base and exact extension project. Call
   `adopt_metadata_object` with the base as `projectName` and an explicit
   `extensionProjectName` when more than one extension is possible. Adoption
   covers the metadata object; route BSL override/interception work to
   `edt-mcp-project-local-fix` and validate the extension afterward.
6. Re-read the target, validate it, and re-check relevant references. Use an
   authorized runtime test when effective access or RLS behavior is acceptance.

## Authority rule

Rename, delete, cascade, force, adoption, rights/RLS changes, and rollback need
authority for their exact targets and consequences. A preview is not approval.

## Stop rule

Stop on ambiguous FQN, unsupported mutation, incomplete destructive preview,
missing authority, failed persistence/readback, or required runtime proof that
cannot be performed safely.

## Completion signal

Return the exact metadata target, confirmed structured diff and persistence,
targeted validation/reference evidence, effective-rights evidence when
applicable, and explicit runtime or completeness gaps.
