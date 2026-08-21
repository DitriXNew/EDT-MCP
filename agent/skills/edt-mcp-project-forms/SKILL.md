---
name: edt-mcp-project-forms
description: Research and safely change managed 1C forms through EDT-MCP, including parameters, bindings, handlers, dynamic lists, and visual verification. Not for raw form XML edits.
---

# EDT-MCP managed forms

## Goal

Use the smallest inspection and verification level that can detect a defect
introduced by the requested form change.

## Use when

- researching form data, items, commands, handlers, or layout;
- creating or changing form objects and members;
- changing bindings, event handlers, or dynamic lists;
- verifying visible form structure.

## Do not use when

- only a non-form BSL method changes;
- the task is DCS output structure rather than the form presenting it;
- a filesystem edit of `.form` is proposed despite structured support.

## Level A: form-module code only

Use when no form item, attribute, command, event binding, data path, dynamic
list, or visible layout changes.

1. Read the exact method and keep its content hash.
2. Apply the bounded BSL workflow.
3. Re-read and run targeted form/object validation.
4. Add runtime evidence only when behavior changed.

Do not render the whole form merely because the method belongs to a form.

## Level B: structural or binding change

1. Read the exact form FQN with `get_metadata_details`.
2. Build only the affected data, visual, command, and event maps.
3. Read relevant handlers with `get_module_structure` and
   `read_method_source`.
4. Consult current guides for `create_metadata`, `modify_metadata`, or
   `delete_metadata` before the first structural write.
5. Apply the narrowest supported structured mutation.
6. Re-read the form and verify parent, data path, command, handler, and owner
   invariants.
7. Run targeted validation.

Form parameters are addressed as form members using a `Parameter` segment.
Current parameters expose `valueType`, `keyParameter`, and `comment`; they do
not have a title or visual position. Create the parameter first, then set its
supported properties through `modify_metadata`.

## Level C: visual, dynamic-list, or unknown form

Add a compact `get_form_layout_snapshot`. Use `get_form_screenshot` only when
appearance is part of acceptance. After a structural or visual change, call
`get_form_screenshot(refresh=true)` for acceptance verification. Treat a
render error as an error; do not accept a stale previously rendered image as
evidence of the changed form. For a dynamic list, verify:

1. owning form attribute;
2. main table or custom query;
3. selected query fields;
4. visible item data paths;
5. settings/filter handlers;
6. refresh or requery behavior.

Validate changed query text before writing and ensure every visible
`List.Field` path resolves afterward.

## External-object forms

For an external data processor/report, pass the external-object project as
`projectName` and use the qualified object/form FQN. Current metadata scope
resolution handles those forms without pretending the project has its own
configuration root.

## Verification boundary

- A structural read proves model relationships, not user interaction.
- A screenshot proves one rendered state, not event behavior.
- Runtime UI acceptance requires an authorized runtime scenario.
- Blank form rendering may reflect missing EDT renderer prerequisites; check
  `get_server_status` and the current form guide before diagnosing the form.

## Safety and stop conditions

Do not invent element IDs, rebuild a whole form for one item, assume a
same-named procedure is bound, or edit form XML directly. Stop when the current
structured writer cannot represent the requested operation, a reference would
be left dangling without authority, or required runtime/visual evidence is
unavailable.
