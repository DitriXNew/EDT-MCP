---
name: edt-mcp-project-metadata
description: Inspect and safely change 1C metadata, including roles, rights, and RLS, through current EDT-MCP structured operations. Not for raw XML edits or plugin development.
---

# EDT-MCP metadata workflow

## Goal

Use exact FQNs and current structured contracts for metadata reads and writes,
with explicit handling for cascading, destructive, extension, and role-rights
operations.

## Use when

- creating, inspecting, modifying, adopting, renaming, or deleting metadata;
- changing role rights, RLS restrictions/templates, or role-wide flags;
- discovering assignable metadata properties;
- checking references before a structural change.

## Do not use when

- only BSL source changes;
- the primary target is managed-form structure or DCS content;
- raw metadata XML editing is proposed despite a supported structured route.

## Inspect first

1. Resolve exact `projectName` and FQN.
2. Read with `get_metadata_details`; request assignable properties when the
   write surface is uncertain.
3. Use `find_references` before a change that can cascade or leave dangling
   references.
4. For forms, switch to `edt-mcp-project-forms`; for reports/DCS, pair with
   `edt-mcp-project-query-dcs`.
5. Call the relevant `get_tool_guide` before the first uncommon, destructive,
   or cascading mutation.

## Operations

- Create: `create_metadata`.
- Modify: `modify_metadata`.
- Rename: `rename_metadata_object`.
- Delete: `delete_metadata`.
- Adopt into an extension: `adopt_metadata_object`.

Do not guess writable property names, enum literals, or payload shapes. Use the
assignable view and current validation errors.

## Rename and delete

Treat preview and confirmation as separate phases. Review the exact affected
targets and references before confirmation. Do not add a new destructive flag
only on the confirm call. A forced delete that may leave dangling references
requires explicit authority.

## Roles and RLS

1. Read the Role FQN with `get_metadata_details`; page the rights matrix only
   when needed.
2. Apply the smallest `rights`, `templates`, or `roleProperties` payload through
   `modify_metadata`.
3. Re-read the role because submitted entries can be pruned when they equal the
   current role default.
4. Treat a partially refused role payload as potentially partially applied;
   re-read before retrying.
5. Runtime-test under the relevant user and role when access behavior is the
   acceptance criterion. Static role structure is not RLS runtime proof.

## Extensions and external-object projects

- Confirm base and extension projects before adoption; adopt only required
  content and re-read the extension composition.
- In an external-object project, address supported objects and members as
  `ExternalDataProcessor.<Name>` or `ExternalReport.<Name>` under that project's
  metadata scope. Do not substitute the linked base configuration project.

## Verification

Re-read the target, confirm persistence fields in the result, run targeted
validation, and re-check references after cascades. A model commit or accepted
save task does not alone prove disk persistence or runtime behavior.

## Stop conditions

Stop when the FQN is ambiguous, the current tool guide does not support the
requested mutation, persistence cannot be verified, destructive authority is
missing, or runtime access behavior is required but no authorized runtime test
is available.
