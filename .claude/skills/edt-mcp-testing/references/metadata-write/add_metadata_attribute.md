# add_metadata_attribute — how to test

**Purpose.** Adds one new attribute to an existing metadata object via a BM **write** transaction. The attribute is created with default properties and the given `attributeName`, optionally with a localized `synonym` (display name). Supported parent types (from source): `Catalog`, `Document`, `ExchangePlan`, `ChartOfCharacteristicTypes`, `ChartOfAccounts`, `ChartOfCalculationTypes`, `BusinessProcess`, `Task`, `DataProcessor`, `Report`, `InformationRegister`, `AccumulationRegister`, `AccountingRegister`. Source: `AddMetadataAttributeTool` (extends `AbstractMetadataWriteTool`) → resolves project/config → `MetadataTypeUtils.findObject` → `BmTransactions.write` → `MdClassFactory.create<Type>Attribute()` + reflective `getAttributes().add(...)`.

> **MUTATES the source model + disk (BM write transaction).** This edits `TestConfiguration/src` (the parent object's MDO XML grows an attribute). It is reversible only from the source tree — **always revert** after testing with `git checkout HEAD -- TestConfiguration && git clean -fd -- TestConfiguration`. Run **only** on `TestConfiguration`. Because a `-clean` redeploy drops EDT's unsaved in-memory edits, you must mutate **through MCP** so model and disk stay in sync.

**Preconditions.**
- Running MCP server (`:8765`), live EDT workbench (non-elevated copy), workspace `D:\WS\EDT`. After a plugin change redeploy with `pwsh D:\Soft\edt-redeploy.ps1` (it may exit 1 yet print `MCP server UP on 8765` — that is success; confirm with `get_edt_version`, not the exit code).
- Project `TestConfiguration` open and `State=ready` (verify with `list_projects` — after a `-clean` relaunch the BSL/Xtext + BM index rebuilds and writes may fail or block until ready).
- Runs on the SWT UI thread (`AbstractMetadataWriteTool.execute` → `Display.syncExec` → `executeOnUiThread`). The mutation itself happens inside `BmTransactions.write(bmModel, "AddMetadataAttribute", …)` — this is the required write transaction boundary (don't try to mutate the model outside it).
- A clean working tree before you start (so the post-test `git` revert restores exactly the pre-test state). Pick a parent object that exists: `Catalog.Catalog` is the canonical supported object in `TestConfiguration`, and confirm its current attributes first with `get_metadata_details`.

**Call (real or documented — DO NOT run live during routine reference drafting; it mutates).** Representative args against `TestConfiguration`:

Minimal (no synonym):
```
add_metadata_attribute(
  projectName="TestConfiguration",
  parentFqn="Catalog.Catalog",
  attributeName="TestAttr")
```

With a localized synonym (synonym keyed by language CODE):
```
add_metadata_attribute(
  projectName="TestConfiguration",
  parentFqn="Catalog.Catalog",
  attributeName="TestAttr",
  synonym="Test Attribute",
  language="en")
```

Schema parameters (all that `getInputSchema()` declares; `execute()` reads exactly these):
- `projectName` *(required)* — EDT project name.
- `parentFqn` *(required)* — FQN of the parent object, `Type.Name`. The TYPE token may be English or Russian (`Справочник.Catalog` works — normalized via `MetadataTypeUtils.normalizeFqn`); the object name is the programmatic `Name`, **not** the synonym/display name.
- `attributeName` *(required)* — name for the new attribute; must be a valid 1C identifier (starts with a letter or underscore, then letters/digits/underscores; Cyrillic letters are valid).
- `synonym` *(optional)* — display name written for the configuration default language unless `language` is given.
- `language` *(optional)* — language code for the synonym (`"ru"`/`"en"`); defaults to the configuration default-language code.

**Full test procedure (mutate → verify → REVERT).**
1. **Confirm ready & clean.** `list_projects` → `TestConfiguration` `State=ready`. Ensure `git status` on `TestConfiguration` is clean.
2. **Capture baseline.** `get_metadata_details(projectName="TestConfiguration", objectFqns=["Catalog.Catalog"])` and note the existing `### Attributes` rows (e.g. `Attribute`, `TestSynAttr`, `BmTxTest`) — your new `TestAttr` must not collide with any (the tool rejects a duplicate name, case-insensitive, with `"Attribute already exists: …"`).
3. **Call (the mutation).** Run `add_metadata_attribute(...)` as above. Expect the success envelope (shape below).
4. **Verify it landed.** Re-run `get_metadata_details(projectName="TestConfiguration", objectFqns=["Catalog.Catalog"])` and confirm a new `### Attributes` row `TestAttr` is present (and, if you passed `synonym`, that the synonym column shows your value for the chosen language). This `get_metadata_details` round-trip is the read-side confirmation — do **not** trust only the write tool's own echo.
5. **REVERT.** `git checkout HEAD -- TestConfiguration && git clean -fd -- TestConfiguration` to drop the attribute from the source tree. Then re-confirm with `get_metadata_details` (after EDT re-reads the reverted files) that `TestAttr` is gone, returning the object to baseline.

**Result.** JSON envelope (`ResponseType.JSON`, inherited from `AbstractMetadataWriteTool`). **Representative success shape from source** (`AddMetadataAttributeTool.executeInternal`) — *not a live capture*.

Minimal call (no synonym → no `synonym`/`language` keys):
```json
{
  "success": true,
  "parentFqn": "Catalog.Catalog",
  "attributeName": "TestAttr",
  "message": "Attribute 'TestAttr' added successfully to Catalog.Catalog"
}
```

With a synonym (the resolved language CODE and the synonym are echoed back together):
```json
{
  "success": true,
  "parentFqn": "Catalog.Catalog",
  "attributeName": "TestAttr",
  "synonym": "Test Attribute",
  "language": "en",
  "message": "Attribute 'TestAttr' added successfully to Catalog.Catalog"
}
```

Field meaning (from source):
- **`parentFqn`** — the **normalized** parent FQN (Russian TYPE token folded to the English singular by `MetadataTypeUtils.normalizeFqn`), echoed.
- **`attributeName`** — the name written, echoed.
- **`synonym` / `language`** — present **only** when a non-empty `synonym` was supplied; `language` is the resolved language CODE from `MetadataLanguageUtils.resolveLanguageCode` (never the language *name*). Both are emitted together so callers can confirm the localized name without a second read.
- **`message`** — `"Attribute '<name>' added successfully to <normalizedParentFqn>"`.

**Error contract.** Genuine failures use `ToolResult.error(...)` → `{success:false, error:"…"}` with `isError:true`. Cases from source:
- Missing/empty args (validated before any model access): `"projectName is required. …"`; `"parentFqn is required. …"`; `"attributeName is required. …"`.
- Invalid identifier: `"Invalid attribute name '<name>'. A name must start with a letter or underscore and contain only letters, digits and underscores."`.
- Project/config resolution (from `AbstractMetadataWriteTool.resolveProjectAndConfig`): `"Project not found: <name>"`; `"Configuration provider not available"`; `"Could not get configuration for project: <name>"`.
- BM model unavailable: `"IBmModelManager not available"`; `"BM model not available for project: <name>"`.
- Bad/unknown parent: `"Invalid FQN: <fqn>"` (no dot); `"Parent object not found: <fqn>. … Use get_metadata_objects tool to list available objects."`.
- Unsupported parent type (e.g. a `CommonModule`, `Constant`, register-without-attributes): `"Object type '<eClass>' does not support attributes. Supported types: Catalog, Document, ExchangePlan, …"`.
- Parent not a BM object: `"Parent object is not a BM object"`.
- Synonym language unresolvable (only when a `synonym` was passed): `"Cannot determine a language code for the synonym in this configuration. Specify 'language' explicitly (e.g. 'en' or 'ru')."`.
- Inside the write transaction (caught and unwrapped to `"Failed to add attribute: <cause>"` via `unwrapCauseMessage`): `"Parent object not found in transaction"`; `"Attribute already exists: <name>"` (duplicate, case-insensitive); `"Cannot create attribute for: <eClass>"`; reflective failures (`"getAttributes() did not return EList"`, `"Failed to add attribute via reflection"`).

**Gotchas.**
- **Mutating — revert every time.** This changes `TestConfiguration/src` on disk. Always finish with `git checkout HEAD -- TestConfiguration && git clean -fd -- TestConfiguration`, and confirm via `get_metadata_details` that the attribute is gone. Never run this on a real config (`IRP`); `TestConfiguration` only.
- **Mutate through MCP, not by hand.** A `-clean` redeploy discards EDT's unsaved in-memory edits, so model and disk only stay consistent if the write went through this tool's BM transaction. Don't hand-edit the MDO XML and expect the running EDT to reflect it.
- **Write transaction boundary is mandatory.** The actual `setName`/`getSynonym().put`/`getAttributes().add` all run inside `BmTransactions.write(...)`. Reads (`findObject`, `supportsAttributes`, capturing `bmGetId`) happen before it; the duplicate check `hasAttribute` runs *inside* the transaction against the live object. Don't expect a mutation if the transaction throws — the whole add is rolled back and you get the `"Failed to add attribute: …"` error.
- **Bilingual.** TYPE token is bilingual (`Catalog`/`Справочник` both accepted, normalized to English); the object NAME and `attributeName` are programmatic identifiers (resolved/stored verbatim, never translated). The `synonym` is the only localized part and is stored **keyed by the language CODE** (`getSynonym().put(synonymLanguage, synonym)` where `synonymLanguage = MetadataLanguageUtils.resolveLanguageCode(config, language)`) — never by the `Language` object's name. With no `language` arg the config default-language code is used; if the config has no default it falls back to the first configured language's code (not a hardcoded `"ru"`). `TestConfiguration` is single-language `en`, so omit `language` or pass `"en"`.
- **No preview/confirm.** Unlike `rename_metadata_object` / `delete_metadata_object`, this tool applies immediately with no `confirm` step — there is no dry-run; the very first successful call has already mutated disk.
- **Idempotency / duplicates.** A second call with the same `attributeName` (case-insensitive) on the same parent fails with `"Attribute already exists: …"`; it does not silently overwrite. Pick a fresh name or revert first.
- **JSON tool, with HTML/char escaping.** Output is the `ToolResult` JSON envelope; `ToolResult.toJson()` HTML-escapes `>`, `<`, `&`, `=`, and the apostrophe `'` as `\uXXXX`. The `message` and several errors contain `'…'`, so when asserting on text match a delimiter-free substring (e.g. `added successfully`, `already exists`, `does not support attributes`), never the raw `'<name>'`.
- **Flaky output channel.** If the result comes back garbled/empty (a bare `Error`/`Done`), do **NOT** retry-spam this *mutating* tool — a blind retry would either add a duplicate (→ `"Attribute already exists"`) or mask a partial success. Re-verify independently: read the new attribute via `get_metadata_details`, and check the EDT log `D:\WS\EDT\.metadata\.log` (the tool logs `"Error adding attribute"` on failure). Trust the read/log over the dropped echo.
- **UI-thread tool — don't hammer it concurrently** with other `Display.syncExec` tools (`get_metadata_details`, `get_form_screenshot`, `get_content_assist`). Serialize the add → verify → revert steps.
