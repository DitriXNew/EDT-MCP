# translate_configuration — how to test

**Purpose.** Runs EDT's *Translation → Translate configuration* context-menu
action on a configuration project. Under the hood it invokes the LanguageTool
public CLI API `com.e1c.langtool.v8.dt.cli.api.ISynchronizeProjectApi`
(`synchronizeProject(IDtProject, List<String> languages)`, called via reflection)
— this is the *SynchronizeProject* operation: it reads the dictionaries from the
storages bound to the project (external dictionary-storage projects carrying the
`dependentProjectNature`, or in-configuration storages) and **regenerates the
translated artifacts** for the requested target languages, then waits for derived
data. **MUTATES the configuration on disk** (it writes translated
synonyms/strings into the model) and depends on an externally-installed
LanguageTool plugin (and, depending on how the dictionaries are configured, an
external translation provider). Source:
`mcp/bundles/com.ditrix.edt.mcp.server/src/com/ditrix/edt/mcp/server/tools/impl/TranslateConfigurationTool.java`.

> **DO NOT run this tool live during routine testing.** It mutates the
> configuration, is heavy, and needs LanguageTool + a configured dictionary/
> provider that the bare TestConfiguration harness does not ship. This reference
> documents the full procedure from source only; the **Result** block below is a
> labelled representative shape, not a captured live run.

**Preconditions.**
- Live EDT copy, workspace `D:\WS\EDT`, MCP on `:8765`; project open and
  **`ready`** (verify with `list_projects`, not the redeploy exit code — the
  redeploy script `pwsh D:\Soft\edt-redeploy.ps1` may exit 1 yet print
  `MCP server UP on 8765`, which is success).
- **LanguageTool must be installed in EDT** (Help → Install New Software; it is
  *not* bundled with EDT base 2025.x / 2026.1). Without it
  `Activator.getSynchronizeProjectApi()` returns `null` and the tool returns a
  clear "not available" error (see Gotchas).
- The project must have **dictionary storages bound** to it (an external
  dictionary-storage project with the `dependentProjectNature`, or in-config
  storages) covering the requested `targetLanguages`. With no usable
  dictionary/provider the LanguageTool API throws and the tool reports
  `Translate configuration failed: <message>`.
- The project must resolve as an EDT project (`IDtProjectManager.getDtProject`
  non-null) and pass `ProjectStateChecker.checkReadyOrError` (not building / not
  available).
- **Mutation safety.** Only ever run against **`TestConfiguration`** (repo path
  `TestConfiguration/src`), never against `IRP` or a real config. Mutate
  *through MCP* so the in-memory model and disk stay in sync, then revert (see
  the REVERT step). After a `-clean` redeploy EDT drops unsaved in-memory edits,
  so never leave the workspace mid-mutation.

**Call (DOCUMENTED — do not execute live; full mutate-then-revert procedure).**

1. Baseline (read tool, confirms current synonyms before mutation):
```
get_metadata_details(projectName="TestConfiguration", fqn="Catalog.Catalog")
```
Record the English synonym(s) so you can tell whether step 3 actually changed
anything. Also confirm the harness is sane: `list_projects` shows
`TestConfiguration` = `ready`; `get_translation_project_info(projectName="TestConfiguration")`
shows the configured languages and bound dictionary storages.

2. (Optional, recommended) Snapshot git so the revert is trivially verifiable:
```
git -C D:\GitHub\EDT-MCP status --porcelain -- TestConfiguration
```

3. The mutating call (representative args vs TestConfiguration — translate the
   ru source into en):
```
translate_configuration(projectName="TestConfiguration",
                        targetLanguages=["en"])
```
- `projectName` (string, **required**) — the project the user right-clicks in
  EDT, typically the **source** (e.g. the `ru`) configuration project. Empty/
  missing → `{"success":false,"error":"projectName is required"}`.
- `targetLanguages` (string array, **required**) — target language **codes** to
  synchronize, e.g. `["en"]` or `["en","kk"]`. Empty/missing →
  `{"success":false,"error":"targetLanguages is required (e.g. [\"en\"])"}`.
- These are the **only** two parameters (both declared in `getInputSchema()` and
  both read in `execute()` — schema/impl are in sync). There is **no**
  `provider`, `confirm`, or `preview` parameter: this tool is *not* a
  preview-then-confirm tool — it mutates immediately. The translation provider
  is whatever the bound dictionary storages / LanguageTool configuration use; it
  is not selectable through this MCP call.

4. VERIFY (read tool) — confirm the translated artifacts changed:
```
get_metadata_details(projectName="TestConfiguration", fqn="Catalog.Catalog")
```
The `en` synonym should now be populated/regenerated. Cross-check the disk
diff: `git -C D:\GitHub\EDT-MCP diff -- TestConfiguration` should show the
regenerated synonym/string entries. Remember synonyms are keyed by language
**code** (`en`/`ru`), so the change lands under the `en` key; the object **NAME**
is programmatic and is never translated — only the synonym text changes.

5. **REVERT (mandatory after any live run):**
```
git -C D:\GitHub\EDT-MCP checkout HEAD -- TestConfiguration
git -C D:\GitHub\EDT-MCP clean -fd -- TestConfiguration
```
Then re-sync EDT's in-memory model to the reverted disk state (it diverged when
you mutated): redeploy `pwsh D:\Soft\edt-redeploy.ps1` (a `-clean` relaunch
re-reads disk) or re-import the project. Confirm clean tree:
`git -C D:\GitHub\EDT-MCP status --porcelain -- TestConfiguration` is empty.

**Result.** `ResponseType.MARKDOWN` — a YAML frontmatter block (built by
`FrontMatter`) followed by a one-line body. Representative shape **from source**
(`FrontMatter.create().put(...).wrapContent("Translate configuration completed.")`):
```markdown
---
tool: translate_configuration
project: TestConfiguration
targetLanguages: en
status: success
---
Translate configuration completed.
```
Notes on the shape: `targetLanguages` is rendered as a comma-joined string
(`String.join(", ", targetLanguages)` → e.g. `en, kk` for two languages), not a
YAML list. `status` is the literal `success`. The body is always the fixed
string `Translate configuration completed.` — the tool does **not** report a
per-object count of what changed, so verify the actual effect with
`get_metadata_details` / git diff, not the response text.

**Gotchas.**
- **Mutates on disk, no preview, no undo via the tool.** Unlike rename/delete,
  there is no `confirm=true` gate and no preview — calling it applies the
  translation immediately. Your only safety net is git revert + EDT re-sync (see
  steps 5). Never run on a real/large config.
- **Cascade / blast radius.** `synchronizeProject` regenerates *all* translated
  artifacts the bound dictionaries cover for the requested languages, not a
  single object — expect a broad diff. After it runs the tool calls
  `BuildUtils.waitForDerivedData(project)`, so derived data is rebuilt before it
  returns; a slow rebuild can make the call take a while (heavy operation).
- **Structured error contract.** Genuine failures return
  `{"success":false,"error":"..."}` with `isError:true` (via `ToolResult.error`),
  NOT the success markdown. Specific cases from source:
  - missing/empty `projectName` → `projectName is required`
  - missing/empty `targetLanguages` → `targetLanguages is required (e.g. ["en"])`
  - unknown project → `Project not found: <name>`; closed project →
    `Project is closed: <name>`; not building-ready →
    `ProjectStateChecker.checkReadyOrError(...)` message; non-EDT project →
    `Not an EDT project: <name>`
  - **LanguageTool absent** → `LanguageTool ISynchronizeProjectApi is not
    available. Install LanguageTool in EDT.` (this is the most common
    "why did it fail" — the plugin simply isn't installed in the harness).
  - LanguageTool API throws (e.g. no dictionary/provider, provider error) → the
    `InvocationTargetException` cause is unwrapped (typically
    `com.e1c.langtool.v8.dt.cli.api.TranslationCliApiException`) and returned as
    `Translate configuration failed: <cause message>`; the full stack is logged
    via `Activator.logError`.
  - LanguageTool method-signature drift → `LanguageTool API mismatch: <message>`.
- **Bilingual correctness.** Pass language **codes** in `targetLanguages`
  (`en`, `ru`, `kk`), never display names ("English"/"Английский"). `projectName`
  is the programmatic project name. Translation writes synonyms keyed by the
  language **code**; the object's programmatic **Name** is never translated.
- **Flaky output channel.** If the result text arrives garbled or as a bare
  `Error`/`Done` instead of the frontmatter, do NOT retry-spam (a retry could
  re-run a heavy mutation). Re-verify independently via the EDT log
  `D:\WS\EDT\.metadata\.log` (the full request/response and any LanguageTool
  stack are logged there) and via `get_metadata_details` / `git diff` to see
  whether the mutation actually applied. Trust the log + disk over the echoed
  text.
- **Reflection, no build-time dependency.** The bundle calls LanguageTool purely
  by reflection (`api.getClass().getMethod("synchronizeProject", IDtProject.class,
  List.class)`), so a green Java build does not prove this path works — only a
  live EDT with LanguageTool exercises it. That, plus the mutation, is why this
  tool is documented-only here.
