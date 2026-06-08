Generates translation strings (`.lstr` / `.trans` / `.dict`) for a 1C configuration. It scans the configuration's translatable features and writes the resulting keys into the storages declared on the project. Each storage routes either to an external dictionary storage project (a plain Eclipse project with the dependentProjectNature) or to the configuration itself, depending on `.settings/translation_storages.yml`. This is the programmatic equivalent of the EDT UI action **Translation -> Generate translation strings**.

## When to use

- You added or changed translatable content (synonyms, interface text, model strings) and need to (re)collect the keys for one or more target languages.
- Run it on the **configuration project** (V8ConfigurationNature) only. Do NOT run it on a dictionary storage project (the plain Eclipse project where the `.lstr`/`.trans`/`.dict` files physically live) or on an extension: the tool rejects those with a clear error.
- Requires **LanguageTool** installed in EDT (Help -> Install New Software; it is not bundled with the EDT base distribution on either 2025.x or 2026.1). If it is missing the tool returns an actionable error telling you to install it.

## Parameter details

- **projectName** (string, required) - the configuration project name. Must have V8ConfigurationNature. Passing a dictionary storage project or an extension is rejected.
- **targetLanguages** (string[], required) - language codes to generate strings for, e.g. `["en"]` or `["en","de"]`. Must be non-empty.
- **storageId** (string, optional) - the storage to write generated keys into. Defaults to `edit:default`. Call `get_translation_project_info` to list the storages configured for the project.
- **collectInterface** (boolean, optional, default true) - collect interface keys (the `.lstr` strings).
- **collectModel** (boolean, optional, default true) - collect model keys (the `.trans` strings).
- **collectModelType** (string, optional, default `ANY`) - how model strings are collected; see the Modes section below.
- **fillUpType** (string, optional, default `NOT_FILLUP`) - how new keys are pre-filled; see the Modes section below.
- **providerId** (string, optional) - translation provider ID, used **only** when `fillUpType=FROM_PROVIDER`. Required in that case; call `get_translation_project_info` to list available providers (e.g. `com.e1c.langtool.history.externalTranslationProvider`).

## Modes

**collectModelType** (applies when `collectModel` is true):
- `ANY` - collect all model strings (default).
- `NONE` - collect no model strings.
- `COMPUTED_ONLY` - only computed strings.
- `UNKNOWN_ONLY` - only strings whose language is unknown.
- `TAGS_ONLY` - only tagged strings.

**fillUpType** (how new keys get an initial value):
- `NOT_FILLUP` - leave the target value empty (default).
- `FROM_SOURCE_LANGUAGE` - copy the source-language text into the target.
- `FROM_PROVIDER` - pre-translate via a provider; **requires** `providerId`.

## Examples

Minimal - collect English interface + model keys with defaults:

```json
{ "projectName": "MyConfig", "targetLanguages": ["en"] }
```

Interface-only into a specific storage:

```json
{ "projectName": "MyConfig", "targetLanguages": ["en"], "storageId": "edit:default", "collectModel": false }
```

Pre-fill from a provider:

```json
{ "projectName": "MyConfig", "targetLanguages": ["en"], "fillUpType": "FROM_PROVIDER", "providerId": "com.e1c.langtool.history.externalTranslationProvider" }
```

## Notes

- After generation the tool waits for EDT to rebuild derived data, then returns a Markdown summary (project, languages, storage, collect flags, model type, fill-up type, status).
- Language codes are 1C language **codes** (e.g. `en`, `ru`), not display names.

## Gotchas

- `providerId` is required when (and only when) `fillUpType=FROM_PROVIDER`; for the other fill-up modes any `providerId` is ignored.
- Wrong project type is the most common mistake: this runs on the configuration project, never on the dictionary storage project where the files live.
- If the project is still indexing, the tool may report that EDT has not resolved an IDtProject yet - retry after indexing completes.
- If LanguageTool is not installed the call fails with a clear "install LanguageTool" message.
