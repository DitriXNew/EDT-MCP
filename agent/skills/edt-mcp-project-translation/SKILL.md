---
name: edt-mcp-project-translation
description: Inspect and run EDT translation workflows through EDT-MCP while preserving declared languages, dictionaries, storage, and programmatic metadata names.
---

# EDT-MCP translation workflow

## Purpose and trigger

Use this skill to inspect configured translation resources, generate strings,
run translation, and verify localized metadata for an exact project.

## Operating rule

- Use current MCP tool help/schema and repository tool documentation as the authority for parameters, limits, side effects, returned identifiers, errors, and recovery.
- This skill supplies task routing and essential ordering only; do not invent undocumented behavior or copy tool contracts into the workflow.
- On ambiguity, an unexpected state or error, unclear target/ownership, or a user-affecting/destructive action, stop and consult the authoritative help. If the safe action remains unclear or needs permission, ask the user.
- Report only results confirmed by tool output.

## Task boundary

Keep the exact project, declared language codes, existing storage/provider
topology, and localized human-facing fields in scope. Never translate
programmatic metadata names, FQNs, BSL identifiers, query fields, or 1C tokens.

## Primary workflow

1. Resolve project identity/kind with `list_projects`, declared languages with
   `get_configuration_properties`, and configured translation routes with
   `get_translation_project_info`.
2. Confirm the intended languages, project support, storage/provider scope, and
   write or external-service authority using current help.
3. Call `generate_translation_strings` or `translate_configuration` only for
   the confirmed route and requested operation.
4. Re-read representative localized metadata with `get_metadata_details`,
   inspect the repository diff when applicable, and run targeted validation.
5. Report only identities, counts, status, and affected samples that current
   tool output actually confirms; successful execution is not proof of
   translation quality or complete coverage.

## Authority rule

Storage/dictionary writes, overwrite, external provider transmission, and any
route affecting multiple storages or projects require authority for that exact
scope.

## Stop rule

Stop on unsupported project kind, undeclared language, ambiguous or incomplete
storage topology, unreviewed overwrite/transmission, or a need to change
programmatic identifiers.

## Completion signal

Return the exact project/languages/route, confirmed operation result, verified
localized samples and diff/validation evidence, and explicit quality or
completeness gaps.
