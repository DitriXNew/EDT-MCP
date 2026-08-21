---
name: edt-mcp-project-translation
description: Inspect and run EDT translation workflows through EDT-MCP while preserving declared languages, dictionaries, storage, and programmatic metadata names.
---

# EDT-MCP translation workflow

## Goal

Apply translation operations only to the intended project, languages, and
existing storage topology without translating programmatic identifiers.

## Use when

- inspecting EDT translation project configuration;
- generating translation strings;
- running configured translation through EDT-MCP;
- verifying localized metadata after translation.

## Do not use when

- the request is ordinary editing of one localized synonym;
- the project has no configured translation storage;
- machine translation or dictionary changes are outside scope.

## Preflight

1. Resolve the exact project.
2. Call `get_configuration_properties` and record `defaultLanguage` plus the
   declared configuration `languages`.
3. Treat every target language as explicit caller or user input, distinguish
   language codes from display names, and verify it is declared.
4. Use `get_translation_project_info` only to discover translation storages
   and providers and to select the configured route.
5. Confirm whether generating strings will write translation storage.
6. Consult `get_tool_guide` for `generate_translation_strings` or
   `translate_configuration` when parameters or side effects are uncertain.

## Workflow

1. Use `generate_translation_strings` only when requested and only for the
   intended project/storage.
2. Reuse existing dictionaries and storage bindings; do not create or redirect
   them by assumption.
3. Run `translate_configuration` only for the selected configured route.
4. Re-read affected metadata with `get_metadata_details` using exact language
   codes when localized values matter.
5. Run targeted validation when translated metadata changed.
6. Inspect the repository diff when translation storage is file-backed.

## Language boundary

Keep programmatic metadata Names, FQNs, BSL identifiers, query fields, and real
1C tokens unchanged. Translate only localized human-facing values covered by
the configured translation workflow.

Do not invent undeclared language codes. A localized write under a declared but
unused language can be technically valid while still being unintended; surface
that state for review.

## Verification

Report source/target languages, storage and dictionary identity, generated or
translated counts, affected objects, validation results, untranslated/stale
locales reported by current tools, and any manual review still required.

## Safety and stop conditions

Stop when project language/storage configuration is incomplete, the requested
language is undeclared, translation would overwrite an unreviewed dictionary,
or programmatic identifiers would need to change. Do not treat successful
generation as proof of translation quality.
