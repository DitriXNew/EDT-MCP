---
name: edt-mcp-project-forms
description: Research and safely change managed 1C forms through EDT-MCP, including parameters, bindings, handlers, dynamic lists, and visual verification. Not for raw form XML edits.
---

# EDT-MCP managed forms

## Purpose and trigger

Use this skill to inspect or change a managed form's structure, data,
commands, handlers, dynamic lists, or visible layout.

## Operating rule

- Use current MCP tool help/schema and repository tool documentation as the authority for parameters, limits, side effects, returned identifiers, errors, and recovery.
- This skill supplies task routing and essential ordering only; do not invent undocumented behavior or copy tool contracts into the workflow.
- On ambiguity, an unexpected state or error, unclear target/ownership, or a user-affecting/destructive action, stop and consult the authoritative help. If the safe action remains unclear or needs permission, ask the user.
- Report only results confirmed by tool output.

## Task boundary

Work on the exact project and form FQN through structured EDT-MCP operations.
Route code-only fixes to `edt-mcp-project-local-fix` and DCS changes to
`edt-mcp-project-query-dcs`; never edit form XML directly.

## Primary workflow

1. Read the form with `get_metadata_details` and inspect only relevant handlers
   with `get_module_structure` and `read_method_source`.
2. Consult the current guide, then use the narrowest supported
   `create_metadata`, `modify_metadata`, or `delete_metadata` operation.
3. Re-read the form and verify the requested ownership, binding, data-path,
   command, and handler relationships; validate changed query text with
   `validate_query` when applicable.
4. Use `get_form_layout_snapshot` when layout structure matters and
   `get_form_screenshot` only when rendered appearance is acceptance evidence.
5. Run targeted validation and an authorized runtime UI scenario only when
   interaction behavior must be proven.

## Authority rule

Do not broaden a form mutation, leave dangling bindings, activate UI, launch a
client, or change runtime data without the authority required for that effect.

## Stop rule

Stop when the form target is ambiguous, the structured writer cannot represent
the requested change, required references cannot be preserved, or necessary
visual/runtime evidence is unavailable.

## Completion signal

Return the exact form target, confirmed structural/source diff, targeted
validation, requested layout or runtime evidence, and explicit gaps. A model
read or screenshot proves only the state it actually reports, not user
interaction.
