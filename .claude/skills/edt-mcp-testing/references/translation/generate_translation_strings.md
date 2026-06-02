# generate_translation_strings — how to test

**Purpose.** Generate (extract) translation strings for a **configuration project** — the MCP equivalent of the EDT UI action *Translation → Generate translation strings*. It scans the configuration's translatable features and **writes** the resulting keys into a declared translation storage (`.lstr` interface keys / `.trans` model keys / `.dict` dictionary entries), then waits for derived data to settle. The storage either routes to an external dictionary storage project (a plain Eclipse project with the dependent-project nature) or to the configuration itself, depending on `.settings/translation_storages.yml`. This is a **MUTATING** tool: it persists translation data to disk. It is a thin reflection wrapper over LanguageTool's public CLI API `com.e1c.langtool.v8.dt.cli.api.IGenerateTranslationStringsApi#generateTranslationStrings(...)`, so it has **no build-time dependency on LanguageTool** — LanguageTool must be installed separately into the EDT workbench (Help → Install New Software). Source: `tools/impl/GenerateTranslationStringsTool.java`.

**Preconditions.**
- Live EDT copy, workspace `D:\WS\EDT`, MCP on `:8765`. After a plugin change: `pwsh D:\Soft\edt-redeploy.ps1` (build → kill → swap → `-clean` relaunch → waits for `:8765`). The script may **exit 1** yet print `MCP server UP on 8765` — that *is* success; confirm via `get_edt_version`, not the exit code.
- Target project open and `State=ready` (`list_projects`). After a `-clean` relaunch give the index time to finish — the tool runs `ProjectStateChecker.checkReadyOrError` and returns a structured error if the project is still building.
- **LanguageTool must be installed in EDT.** If it is not, `Activator.getGenerateTranslationStringsApi()` returns `null` and the tool errors out (`"LanguageTool IGenerateTranslationStringsApi is not available. Install LanguageTool in EDT."`). This is the most common reason the tool can't be exercised at all.
- **A translation storage must be attached** to the configuration. The default `storageId` is `edit:default`; list the real ones with `get_translation_project_info` first. If that tool reports `Storages: (none)`, generation has nowhere to write — set up a storage in EDT manually (create a plain Eclipse project, attach it via the configuration's Translation settings) before testing. There is **no MCP tool** to create the storage.
- Must be run on the **configuration project** (`com._1c.g5.v8.dt.core.V8ConfigurationNature`), **not** on a dictionary storage project or an extension — the tool rejects non-configuration projects with an explicit error.
- **Bilingual note on `TestConfiguration`:** it declares only **one** language, `English` (`languageCode: en`, also the default language) and has **no** `translation_storages.yml`. So out of the box `get_translation_project_info` on `TestConfiguration` will likely show `(none)` for storages, and `targetLanguages:["en"]` is the source language (degenerate). A meaningful live test needs a config with a second language **and** an attached storage — `TestConfiguration` as shipped cannot fully exercise the write path. Do not invent results; document and skip the live call if the storage/second language is absent.

**Call (documented — do NOT run live without an attached storage + second language; it mutates translation data).**
Params (only `projectName` + `targetLanguages` are required; every other has a default applied in `execute()`):
- `projectName` (required) — configuration project (V8ConfigurationNature), NOT a storage project.
- `targetLanguages` (required, string array) — target language **codes**, e.g. `["en"]`.
- `storageId` (default `"edit:default"`) — from `get_translation_project_info`.
- `collectInterface` (default `true`) — generate interface (`.lstr`) keys.
- `collectModel` (default `true`) — generate model (`.trans`) keys.
- `collectModelType` (default `"ANY"`) — one of `ANY | NONE | COMPUTED_ONLY | UNKNOWN_ONLY | TAGS_ONLY`.
- `fillUpType` (default `"NOT_FILLUP"`) — one of `NOT_FILLUP | FROM_SOURCE_LANGUAGE | FROM_PROVIDER`.
- `providerId` (default empty) — **required only when** `fillUpType=FROM_PROVIDER` (e.g. `"com.e1c.langtool.history.externalTranslationProvider"`); list providers via `get_translation_project_info`.

Full write-test procedure (run ONLY on `TestConfiguration`, and only once a storage is attached):

```
# 0. SETUP — confirm liveness, readiness, storages, language
get_edt_version()                                  # MCP is up
list_projects()                                    # require TestConfiguration State=ready
get_translation_project_info(projectName="TestConfiguration")
  # note the Storages list; if "(none)", STOP — attach a storage in EDT first.
  # pick a real storageId (e.g. edit:default) and, for FROM_PROVIDER, a providerId.

# 1. CAPTURE PRE-STATE (so the revert is verifiable)
#    Translation keys land under the storage's files (.lstr/.trans/.dict).
#    Snapshot the working tree before mutating:
#    git -C D:\GitHub\EDT-MCP status --porcelain -- TestConfiguration

# 2. CALL (mutate THROUGH MCP so model + disk stay in sync across -clean)
generate_translation_strings(
    projectName="TestConfiguration",
    targetLanguages=["en"],            # use a real *target* (2nd) language if the config has one
    storageId="edit:default",          # a real ID from step 0
    collectInterface=true,
    collectModel=true,
    collectModelType="ANY",
    fillUpType="NOT_FILLUP")           # NOT_FILLUP avoids needing a providerId / network

# 3. VERIFY the write happened
#    - The tool returns status: success in the frontmatter (see Result).
#    - There is NO dedicated read-back MCP tool for translation keys; confirm by:
#        a) git status showing new/changed .lstr/.trans/.dict under the storage path, and/or
#        b) the EDT log D:\WS\EDT\.metadata\.log (no LanguageTool error logged), and
#        c) re-running get_translation_project_info (storage still present / counts sane).

# 4. REVERT (mandatory — this is a mutating tool)
#    git -C D:\GitHub\EDT-MCP checkout HEAD -- TestConfiguration
#    git -C D:\GitHub\EDT-MCP clean -fd -- TestConfiguration
#    Then, so EDT's in-memory model matches disk again, trigger a re-read:
#    pwsh D:\Soft\edt-redeploy.ps1   (a -clean relaunch re-reads from disk)
```

**Result.** Markdown with a YAML frontmatter block (`ResponseType.MARKDOWN`, built by `FrontMatter.create()...wrapContent(...)`). On success the frontmatter echoes the effective parameters and `status: success`, and the body is the single line `Translation strings generated.`. The tool does **not** return a count of generated keys — success is signalled by the `status` field, not by any payload. Representative shape **from source** (not a live capture):

```markdown
---
tool: generate_translation_strings
project: TestConfiguration
targetLanguages: en
storageId: "edit:default"
collectInterface: true
collectModel: true
collectModelType: ANY
fillUpType: NOT_FILLUP
status: success
---
Translation strings generated.
```

(Frontmatter scalars are YAML-escaped by `FrontMatter`; values containing `:` such as `edit:default` are emitted double-quoted, hence `"edit:default"`. `targetLanguages` is rendered as a comma-joined string, e.g. `en, ru` for two languages.)

On a **genuine failure** the tool returns the structured error contract instead — `ToolResult.error(...).toJson()`, i.e. `{"success":false,"error":"…"}` with `isError:true`. The distinct error messages, all sourced from `execute()`:
- `"projectName is required"` — empty/missing `projectName`.
- `"targetLanguages is required (e.g. [\"en\"])"` — empty/missing `targetLanguages`.
- `"providerId is required when fillUpType=FROM_PROVIDER. Use get_translation_project_info to list available providers."` — `FROM_PROVIDER` with no `providerId`.
- `"Project not found: <name>"` / `"Project is closed: <name>"` — bad/closed project.
- the `ProjectStateChecker.checkReadyOrError` message — project still building / not ready.
- `"Not a V8 configuration project: <name>. This action must be run on the configuration project (V8ConfigurationNature), not on a dictionary storage project or extension."` — wrong project type.
- `"EDT has not yet resolved an IDtProject for: <name>. The project may still be indexing — please retry."` — DtProject not yet resolved (retry).
- `"LanguageTool IGenerateTranslationStringsApi is not available. Install LanguageTool in EDT."` — LanguageTool not installed.
- `"LanguageTool failed: <cause.message>"` — the LangTool API threw (unwrapped `InvocationTargetException`, typically `TranslationCliApiException`).
- `"LanguageTool API mismatch: <message>"` — the reflected method signature changed (`NoSuchMethodException`/`IllegalAccessException`).

**Gotchas.**
- **MUTATES — always revert.** This writes `.lstr`/`.trans`/`.dict` translation data to the storage. Run it **only** on `TestConfiguration`, then revert with `git checkout HEAD -- TestConfiguration && git clean -fd -- TestConfiguration`. Because a `-clean` redeploy makes EDT re-read from disk, do the mutation **through MCP** (not by editing files) so model and disk stay in sync, and re-launch after the git revert so the workbench model matches the reverted disk.
- **No preview / confirm.** Unlike rename/delete tools, there is **no** `confirm` parameter and no dry-run — the call applies immediately. There is no built-in undo other than the git revert above.
- **Storage must exist or it does nothing useful.** The default `storageId=edit:default` only works if such a storage is declared on the project. If `get_translation_project_info` shows `Storages: (none)`, generation has no target; set one up in EDT first (no MCP tool creates it). `TestConfiguration` as shipped has no `translation_storages.yml`.
- **Must be the configuration project.** Passing a dictionary storage project or an extension is rejected up front (the `V8ConfigurationNature` check). Pass the configuration whose features should be scanned.
- **LanguageTool dependency is optional/reflective.** The whole feature is reflection over a separately-installed plugin. On a base EDT without LanguageTool the tool cannot run — expect the `…is not available` error, not a crash. A signature drift surfaces as `"LanguageTool API mismatch: …"`.
- **`fillUpType` ↔ `providerId` coupling.** `providerId` is appended (`FROM_PROVIDER:<id>`) **only** for `FROM_PROVIDER`; for other modes it is ignored to avoid malformed values. `FROM_PROVIDER` with no `providerId` is rejected before any API call. `FROM_PROVIDER` may hit an external translation service (network/credentials) — use `NOT_FILLUP` for a deterministic, offline test.
- **Schema/params:** every parameter read in `execute()` is declared in `getInputSchema()`; the array param is `targetLanguages` (string array of **language codes**), not `language`/`targetLanguage`. Defaults (`edit:default`, `ANY`, `NOT_FILLUP`, `collect*`=true) are applied server-side when omitted.
- **Bilingual.** `targetLanguages` are language **codes** (`en`, `ru`) — the same code space as the synonym key (synonyms are keyed by language CODE, not name). Object names extracted are programmatic `Name`s; only the metadata TYPE token is bilingual. `TestConfiguration` declares a single language (`en`), so a real two-language test needs a config with a second configured language; targeting the source language alone is degenerate.
- **Error contract.** Genuine failures return `{"success":false,"error":"…"}` with `isError:true`; do not treat those as the markdown success shape. Conversely the success markdown has no `success:false`/`isError` — `status: success` in the frontmatter is the signal.
- **Flaky output channel.** If the result text is dropped, truncated, or arrives as a bare `Error`/`Done`, do NOT retry-spam (a retry re-runs the mutation). Re-verify independently via the EDT log `D:\WS\EDT\.metadata\.log` (LanguageTool errors are logged there via `Activator.logError`) and via `git status` on the storage files before deciding whether the write actually happened.
- **Heavy / blocking.** After invoking the API the tool calls `BuildUtils.waitForDerivedData(project)` (up to a 5-minute default timeout) so validation/derived data settle before returning — the call can take a while on a large configuration; that is expected, not a hang.
