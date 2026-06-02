# create_metadata_object — how to test

**Purpose.** Create a new **top-level** metadata object (Catalog, Document, InformationRegister, AccumulationRegister, Enum, CommonModule, Report, DataProcessor) with EDT default content — the same result as the EDT "New" wizard: a generated UUID and default properties. Optionally sets a `synonym` (display name, keyed by the configuration's language **code**) and a `comment`. The object is registered as a BM top object and added to the matching Configuration collection inside a **write transaction**, and EDT persists it into a new `.mdo` file on disk. Source: `CreateMetadataObjectTool.java` (extends `AbstractMetadataWriteTool`) → `IModelObjectFactory.create(eClass, version)` + `IBmTransaction.attachTopObject` + `BmTransactions.write(...)`.

> **MUTATES the model and the source tree.** This is a BM **write** transaction that creates a brand-new `.mdo` file under `TestConfiguration/src`. Run it **only** on `TestConfiguration` and **always revert** afterward (procedure below). Do **not** run it live during routine reference drafting unless a fresh capture is explicitly requested.

**Preconditions.**
- Live (non-elevated) EDT copy, MCP on `:8765`, workspace `D:\WS\EDT`. After a plugin change redeploy with `pwsh D:\Soft\edt-redeploy.ps1` (it may **exit 1** yet print `MCP server UP on 8765` — that *is* success; confirm with `get_edt_version`, not the exit code).
- Project `TestConfiguration` open and `State=ready` — verify with `list_projects`. After a `-clean` relaunch the Xtext/BSL index rebuilds for a while; a mutation against a still-indexing project can misbehave, so wait for `ready`.
- Runs on the SWT UI thread (`AbstractMetadataWriteTool.execute` → `Display.syncExec`) and mutates the in-memory `Configuration`. The actual model change happens inside `BmTransactions.write(...)` (the explicit write boundary required by CLAUDE.md don't #1).
- **Mutate THROUGH MCP, not by hand-editing files.** A later `-clean` redeploy makes EDT reload from disk; mutating through this tool keeps the in-memory model and the on-disk `.mdo` in sync.
- The OSGi services `IV8ProjectManager`, `IModelObjectFactory`, and `IBmModelManager` must be available (they are in the normal EDT runtime). The tool resolves the platform `Version` from the V8 project to pick correct default content.

**Call (DOCUMENTED — not executed here).** Representative args against `TestConfiguration`:
```
create_metadata_object(
  projectName="TestConfiguration",
  metadataType="Catalog",
  name="McpTestCatalog",
  synonym="MCP Test Catalog",
  language="en")
```
Required: `projectName`, `metadataType`, `name`. Optional: `synonym`, `comment`, `language`.
- `metadataType` accepts the English singular **or** the Russian TYPE token (e.g. `Справочник`, `Документ`) — it is normalized via `MetadataTypeUtils.toEnglishSingular`. Supported set (canonical English): `Catalog`, `Document`, `InformationRegister`, `AccumulationRegister`, `Enum`, `CommonModule`, `Report`, `DataProcessor`. A recognized-but-unsupported type (e.g. another EDT type) is rejected with an explicit "not supported for creation" error.
- `name` must be a valid 1C identifier (starts with a letter or `_`, then letters/digits/`_`), and is the **programmatic** object name — never a synonym.
- `synonym` is written **only when supplied**; its language is resolved by `language` if given, else the configuration's default-language **code**, else the first configured language's code (never a hardcoded `"ru"`). It is stored under the language CODE key (`en`/`ru`), not the language object's name.

**Full test procedure (mutate-then-revert; explicit-request-only; do NOT run during reference drafting).**
1. **Confirm ready.** `list_projects` → `TestConfiguration` `State=ready`.
2. **Pick a fresh name** that does not already exist (the tool rejects duplicates). E.g. `McpTestCatalog`.
3. **Create.** Call `create_metadata_object(projectName="TestConfiguration", metadataType="Catalog", name="McpTestCatalog", synonym="MCP Test Catalog", language="en")`. Expect `success:true` and `fqn:"Catalog.McpTestCatalog"`.
4. **Verify via a read tool.** Confirm with `get_metadata_details(projectName="TestConfiguration", objectFqns=["Catalog.McpTestCatalog"])` — the `## Catalog: McpTestCatalog` block should show Name `McpTestCatalog` and an `en` synonym row `MCP Test Catalog`. Optionally also `get_metadata_objects(projectName="TestConfiguration")` to see it listed, and `get_project_errors(projectName="TestConfiguration")` to confirm the new object introduced no markers.
5. **Confirm the file on disk** (independent of the echo): a new `.mdo` exists under `TestConfiguration/src/Catalogs/McpTestCatalog/` (`Test-Path`).
6. **REVERT — mandatory.** Restore the source tree and drop the new untracked `.mdo`:
   `git checkout HEAD -- TestConfiguration && git clean -fd -- TestConfiguration`.
   (`git clean -fd` is required — the new object is an **untracked** file that `git checkout` alone will not remove.) Then, if EDT will keep running against the reverted tree, trigger a re-read / `-clean` redeploy so the in-memory model drops the now-deleted object.

**Result.** JSON envelope (`ResponseType.JSON`, from `AbstractMetadataWriteTool`). **Representative success shape from source** (`CreateMetadataObjectTool.executeInternal`) — *not a live capture*:
```json
{
  "success": true,
  "fqn": "Catalog.McpTestCatalog",
  "metadataType": "Catalog",
  "name": "McpTestCatalog",
  "synonym": "MCP Test Catalog",
  "language": "en",
  "message": "Object 'Catalog.McpTestCatalog' created successfully. Run get_project_errors to verify, or revalidate_objects if needed."
}
```
Field meaning (from source):
- **`fqn`** — `<canonicalType>.<name>` (the canonical *English* type token + the programmatic name), e.g. `Catalog.McpTestCatalog` even if the call used `Справочник`.
- **`metadataType`** — the normalized canonical English singular type.
- **`name`** — the programmatic object name as written.
- **`synonym` / `language`** — present **only** when a non-empty `synonym` was supplied; `language` is the resolved language **CODE** actually used as the synonym map key. Both are emitted together or not at all.
- **`message`** — fixed success message pointing at `get_project_errors` / `revalidate_objects` for verification.

**Error contract.** Genuine failures use `ToolResult.error(...)` → `{success:false, error:"…"}` with `isError:true`. Cases from source:
- Missing/invalid args: `"projectName is required. Usage: …"`; `"metadataType is required. Supported: Catalog, Document, …"`; `"name is required. Usage: …"`; `"Invalid object name '<name>'. A name must start with a letter or underscore and contain only letters, digits and underscores."`.
- Type resolution: `"Unknown metadata type: <type>"` (not recognized at all); `"Metadata type '<type>' is not supported for creation. Supported: …"` (recognized but outside the supported set); `"No configuration collection mapping for type: <type>"`.
- Project / config: `"Project not found: <name>"`; `"Configuration provider not available"`; `"Could not get configuration for project: <name>"` (all from `resolveProjectAndConfig`); `"Could not resolve configuration collection '<refName>'"`; `"Configuration is not a BM object"`.
- Duplicate: `"Object already exists: <Type>.<name>"` (checked via `MetadataTypeUtils.findObject` before any write).
- Synonym language unresolvable (only when a synonym was supplied): `"Cannot determine a language code for the synonym in this configuration. Specify 'language' explicitly (e.g. 'en' or 'ru')."`.
- Services: `"IV8ProjectManager not available"`; `"Could not resolve V8 project for: <name>"`; `"IModelObjectFactory not available"`; `"IBmModelManager not available"`; `"BM model not available for project: <name>"`.
- Write-transaction failure (caught): `"Failed to create object: <cause>"` where `<cause>` is `unwrapCauseMessage(e)` (the cause's message if present, e.g. "Factory returned null for type: …", "Configuration not found in transaction"). Any other exception escaping to the base class returns `ToolResult.error(e.getMessage())`.

**Gotchas.**
- **Mutating + creates a new file — always revert with `git clean`.** A plain `git checkout HEAD -- TestConfiguration` will NOT remove the new untracked `.mdo`; you must also run `git clean -fd -- TestConfiguration`. Run only on `TestConfiguration`, only on explicit request.
- **Top-level objects only; supported subset only.** This creates the *object itself*, not its attributes/tabular sections/forms (use `add_metadata_attribute` etc. afterward). Only the 8 supported types create; other EDT types return the "not supported for creation" error even though they exist.
- **Write transaction boundary is mandatory.** The mutation runs strictly inside `BmTransactions.write(bmModel, "CreateMetadataObject", …)`; the configuration is re-fetched **inside** the transaction by its `bmGetId()` before being mutated. Never mutate the model outside this boundary (CLAUDE.md don't #1).
- **Bilingual — synonym keyed by language CODE.** Only the *type token* is bilingual (`Catalog`/`Справочник`) and is normalized to English. The object **name** is programmatic and never translated. The `synonym` is stored under the language **code** key (`en`/`ru`) via `MetadataLanguageUtils.resolveLanguageCode` — using the language object's *name* would write a key EDT never reads, leaving the synonym blank in the editor (CLAUDE.md don't #2). With no `language` arg, the config default-language code is used; with no default language, the first configured language's code (TestConfiguration is single-language `en`).
- **Duplicate guard.** Re-running with an existing name returns `"Object already exists: …"` and writes nothing — pick a fresh name each test, or revert between runs.
- **JSON tool, with HTML/char escaping.** `ToolResult.toJson()` HTML-escapes `>`, `<`, `&`, `=`, and the apostrophe `'` as `\uXXXX`. The success/error messages here contain apostrophes (`Object 'X' …`, `Invalid object name 'X'`) — if you assert on text, match a delimiter-free substring (e.g. `created successfully`, `is required`, `already exists`), never the raw `'…'`.
- **Flaky output channel — do NOT retry-spam a mutating tool.** A dropped/garbled echo does not mean the create failed; a blind retry can hit the duplicate guard or leave a stray object. Re-verify independently: `get_metadata_details` / `get_metadata_objects` for the FQN, `Test-Path` the new `.mdo`, and read the EDT log `D:\WS\EDT\.metadata\.log` (the tool logs creation and any error via `Activator`).
- **UI-thread tool.** It runs under `Display.syncExec`; don't fire it concurrently with other UI-thread tools. After a successful create, run `get_project_errors` (or `revalidate_objects`) as the success message suggests to confirm the new object is well-formed.
