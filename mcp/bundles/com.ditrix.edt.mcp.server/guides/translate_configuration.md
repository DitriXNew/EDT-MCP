Runs EDT's **Translation → Translate configuration** action on a project: it reads the dictionaries bound to the project and regenerates the translated artifacts for the target languages. This is the EDT LanguageTool "SynchronizeProject" operation, driven from MCP.

## When to use
- You have translation dictionaries set up and want to (re)generate the translated configuration content for one or more languages.
- After updating dictionaries, to push the new translations into the configuration.

## Parameter details
- `projectName` (required) - the project you'd right-click in EDT, typically the **source** configuration project (e.g. the `ru` project). The bound dictionary storages are resolved automatically.
- `targetLanguages` (required) - array of target language codes to synchronize, e.g. `["en"]`.

## What you get
Markdown with a YAML front-matter summary (tool, project, targetLanguages, status) confirming completion. It waits for derived data before returning.

## Notes & gotchas
- **Requires LanguageTool installed in EDT** (Help → Install New Software; not bundled with the base EDT). Without it you get a clear "not available" error - that's environment, not a bug.
- Dictionaries come from the storages bound to the project - external dictionary-storage projects (with the dependent-project nature) or in-configuration storages. If nothing is bound, there is nothing to translate.
- This generates the per-language strings inside the configuration. To inspect translation setup/coverage use `get_translation_project_info`; to extract translatable strings use `generate_translation_strings`.
- A project still building is refused with a clear message; a missing/closed project returns an error naming the value.
