# get_translation_project_info — how to test

**Purpose.** Diagnostic, read-only. Returns LanguageTool metadata for a project: the translation **storage IDs** declared on the project (e.g. `edit:default`, `dictionary:common-camelcase`, `dictionary:common`, `context:model`, `context:interface`) and the available **translation provider IDs** (Google, Microsoft, Yandex, history, etc.). Source: `GetTranslationProjectInfoTool.java`. It wraps `com.e1c.langtool.v8.dt.cli.api.IProjectInformationApi` via reflection (so the bundle has no build-time dependency on LanguageTool), calling `getProjectStorages(IDtProject)` and `getTranslationProvidersIds()`. Use it before `generate_translation_strings` / `translate_configuration` to see whether a dictionary storage is attached and which providers are wired up.

**Preconditions.**
- Live EDT (non-elevated copy), MCP on `:8765`, workspace `D:\WS\EDT`, project `TestConfiguration` open.
- **LanguageTool must be installed in EDT.** The API is resolved via an OSGi `ServiceTracker` on `com.e1c.langtool.v8.dt.cli.api.IProjectInformationApi` (`Activator.getProjectInformationApi()` returns null when the service is absent). If LanguageTool is not installed, the tool returns a structured error (see Result / Gotchas) — this is the current state of the test harness.
- Read-only. Does **not** mutate the model or disk. No revert needed. The hint "Live-call is safe" holds.
- The only parameter is `projectName` (required). There is no language argument; the result is provider/storage IDs, not translated text.

**Call (real, 2026-06-02).**
```
get_translation_project_info(projectName="TestConfiguration")
```

**Result.** `ResponseType.MARKDOWN`. On success the body is YAML frontmatter (`tool`, `project`, `storagesCount`, `providersCount`) followed by two Markdown sections, `## Storages` and `## Translation providers`, each a bullet list (or the literal `(none)` when empty). Empty storages means no dictionary storage is attached yet.

**Representative SUCCESS shape** (labelled — not produced in this harness because LanguageTool is not installed; reconstructed from the source `execute()` builder, lines 135-166):
```
---
tool: get_translation_project_info
project: TestConfiguration
storagesCount: 5
providersCount: 4
---
## Storages

- edit:default
- dictionary:common-camelcase
- dictionary:common
- context:model
- context:interface

## Translation providers

- google
- microsoft
- yandex
- history
```
With no dictionary storage attached, the same success path emits `storagesCount: 0` and a `## Storages` section whose body is the literal `(none)`.

**Actual live result in THIS harness (real, 2026-06-02).** LanguageTool is not installed, so the live call returned a structured error. The MCP text channel showed only a bare `Error` (the flaky channel — see Gotchas); the real payload is in the EDT log `D:\WS\EDT\.metadata\.log` as `structuredContent`:
```
{"jsonrpc":"2.0","id":325,"result":{
  "content":[{"type":"text","text":"Error"}],
  "structuredContent":{
    "success":false,
    "error":"LanguageTool IProjectInformationApi is not available. Install LanguageTool in EDT."
  }
}}
```
This matches the source error literal at `GetTranslationProjectInfoTool.java:119-121`. To actually exercise the success path you must install LanguageTool in the EDT copy first; until then this tool legitimately reports the missing-API error.

**Gotchas.**
- **Requires LanguageTool.** The single most common "failure" is `LanguageTool IProjectInformationApi is not available. Install LanguageTool in EDT.` — that is a missing-component condition, NOT a tool bug. Do not "fix" the code; install LanguageTool (this is the current harness state).
- **Empty storages ≠ error.** If LanguageTool IS installed but no dictionary storage is attached, the call **succeeds** with `storagesCount: 0` and `## Storages` → `(none)`. Per the tool description, attaching a storage is a **manual EDT step with no MCP tool**: create a plain Eclipse project (File -> New -> Project -> General -> Project), then attach it to the configuration via the configuration project's properties (Translation settings). Do **not** use the "1C:Enterprise -> Dependent translation project" wizard — the storage must be a plain Eclipse project, and the configuration itself can also act as its own storage.
- **Error contract.** Genuine failures arrive as `{success:false, error:"…"}` with `isError:true` (the protocol diverts the JSON error payload). The tool maps its branches to `ToolResult.error(...)`:
  - `projectName is required` (missing/empty `projectName`)
  - `Project not found or closed: <name>` (resolved via `ProjectContext.of(...)`, `!ctx.isOpen()` — a specific diagnostic, on purpose, instead of the generic "Not an EDT project")
  - the `ProjectStateChecker.checkReadyOrError(...)` message (project not ready / still indexing)
  - `Not an EDT project: <name>` (resolves as an IProject but has no `IDtProject`)
  - `LanguageTool IProjectInformationApi is not available. Install LanguageTool in EDT.` (service absent — seen live here)
  - `Get info failed: <cause>` (reflection `InvocationTargetException` — the wrapped LanguageTool call threw)
  - `LanguageTool API mismatch: <msg>` (`NoSuchMethodException`/`IllegalAccessException` — the reflected method names `getProjectStorages`/`getTranslationProvidersIds` don't match the installed LanguageTool version)
  - bare `e.getMessage()` for any other unexpected exception.
  All errors are also written to the EDT log via `Activator.logError(...)`.
- **Flaky output channel.** The MCP `content[].text` dropped to a bare `Error` here while the real error lived in `structuredContent`. Do NOT retry-spam. Re-verify by reading `D:\WS\EDT\.metadata\.log` (it logs both `MCP request body` and the full `MCP response` with structuredContent) — that is how the real `LanguageTool IProjectInformationApi is not available` message was recovered.
- **Bilingual.** Not applicable to the IDs themselves — storage and provider IDs (`edit:default`, `dictionary:common`, `google`, …) are programmatic identifiers from LanguageTool, not translated synonyms. There is no language parameter and no synonym resolution in this tool, so the ru/en synonym-language-code rules don't bite here. (Bilingual concerns belong to the sibling tools `generate_translation_strings` / `translate_configuration`, which actually produce/consume translated strings.)
- **No JVM flag, no infobase exclusivity, no cascade.** This is a pure read-only diagnostic — it does not open editors, does not need the form-render JVM flag, and does not lock the infobase. Safe to call any time the project is open and ready.
