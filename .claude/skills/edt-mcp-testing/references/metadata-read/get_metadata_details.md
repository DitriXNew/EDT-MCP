# get_metadata_details — how to test

**Purpose.** Returns detailed properties of one or more metadata objects from a 1C configuration, one Markdown section per requested FQN. `full=false` (default) gives a Basic Properties block + a Synonym table + the object's containment collections (Attributes, Tabular Sections, Forms, Commands, …) in compact form; `full=true` dumps *all* EMF properties plus extended (10-column) attribute tables and per-tabular-section sub-tables. Source: `GetMetadataDetailsTool.java` → `MetadataFormatterRegistry.format` → `UniversalMetadataFormatter` (dynamic EMF reflection; there are no per-type formatters). Read-only.

**Preconditions.**
- Live EDT (non-elevated copy), MCP on `:8765`, workspace `D:\WS\EDT`, project `TestConfiguration` open and `State=ready` (verify with `list_projects` — after a `-clean` redeploy the index rebuilds and reads come back empty/partial until ready).
- `TestConfiguration` is small (`Catalog.Catalog`, `CommonModules` `Error`/`OK`, one form). `Catalog.Catalog` has attributes `Attribute`/`TestSynAttr`/`BmTxTest` and a form `ItemForm`, so it is the canonical representative object. `IRP` is a big real config — use it only for stress/variety.
- Runs on the SWT UI thread (`Display.syncExec`) and reads the in-memory `Configuration` via `IConfigurationProvider` — no open editor, cursor, or transaction needed. Does NOT mutate the model or disk; no revert needed.

**Call (real).**
```
get_metadata_details(
  projectName="TestConfiguration",
  objectFqns=["Catalog.Catalog"])
```
Other args (optional): `full` (boolean, default false — pass `true` for the all-properties dump), `language` (synonym language code, e.g. `"en"`/`"ru"`; defaults to the configuration's default-language code). `objectFqns` is a **required array** — you can request several objects in one call, and Russian TYPE tokens are accepted (`Справочник.Catalog`).

**Result.** Markdown resource (`ResponseType.MARKDOWN`, filename `metadata-details-<projectname>.md`), NOT JSON. A top `# Metadata Details: <project>` heading, then for each FQN a `## <Type>: <Name>` block followed by a `\n---\n` separator. Real output for the call above:
```
# Metadata Details: TestConfiguration

## Catalog: Catalog


### Basic Properties

| Property | Value |
|---|---|
| Name | Catalog |
| Synonym | Catalog |

### Synonym

| Language | Value |
|---|---|
| en | Catalog |

### Attributes

| Name | Synonym | Type |
|---|---|---|
| Attribute | Attribute | String |
| TestSynAttr | Test Attribute Display | - |
| BmTxTest | Bm Tx Test | - |

### Forms

| Name | Synonym | Form Type |
|---|---|---|
| ItemForm | Item form | Managed |

---
```
Bilingual TYPE token works — `objectFqns=["Справочник.Catalog"]` returns the identical `## Catalog: Catalog` block (the Russian `Справочник` is normalized to the English singular `Catalog` via `MetadataTypeUtils.toEnglishSingular`; the object NAME `Catalog` is programmatic, never translated).

A single bad FQN does NOT fail the call — it yields an informational `**Error:**` line *inside that FQN's body*, while good FQNs in the same array still format. Real output for `objectFqns=["Справочник.Catalog","Catalog.DoesNotExist"]` (second body only):
```
---

**Error:** Object not found: Catalog.DoesNotExist

---
```
An FQN with no dot gives the same in-body shape: `**Error:** Invalid FQN: <fqn>. Expected format: Type.Name (e.g. Catalog.Products)`.

**Gotchas.**
- **`full=true` is a different, much larger shape (live-uncaptured here).** Basic mode emits `### Basic Properties` (Name/Synonym), a `### Synonym` language/value table, and compact 3-column collection tables (Attributes = Name|Synonym|Type; Forms = Name|Synonym|Form Type; Commands = Name|Synonym|Group). `full=true` replaces Basic Properties with an `### All Properties` reflection dump, expands Attributes to a **10-column** table (Name, Synonym, Type, Indexing, Fill Checking, Full Text Search, Password Mode, Multi Line, Quick Choice, Create On Input), and renders each tabular section as a `#### <TS name>` sub-block with its own properties table + nested `**Attributes:**` table. Standard attributes are emitted via a dedicated path, not the generic collection loop. If you change any of these tables, re-run — the synonym table format is also asserted by e2e (`tests/e2e/tools/test_get_metadata_details.py`, with the language-CODE echo in `test_create_metadata.py`).
- **In-body `**Error:**` ≠ the structured error contract.** Per-FQN "Object not found" / "Invalid FQN" are plain Markdown lines inside the section body, and the overall call still succeeds (no `success`/`isError`). Do NOT assert on a JSON flag for these — match the `**Error:** Object not found:` substring. Genuine *tool-level* failures (below) arrive as `ToolResult.error(...)` JSON: `{"success":false,"error":"…"}` with `isError:true`.
- **Tool-level (structured) error triggers:** missing/empty `projectName` → `"projectName is required"`; missing/empty `objectFqns` → `"objectFqns is required (array of FQNs like 'Catalog.Products')"`; project not in workspace → `"Project not found: <name>"`; no configuration provider / no configuration model → `"Configuration provider not available"` / `"Could not get configuration for project: <name>"`; an exception on the UI thread → `ToolResult.error(e.getMessage())`. Success-path Markdown has no `success` field — assert on the `# Metadata Details:` heading or a table.
- **Bilingual / synonym keying.** `language` and the per-object synonym are keyed by the language CODE (`en`/`ru`), never by the `Language` object's name (`MetadataLanguageUtils.resolveLanguageCode`). With no `language` arg, the default-language code is used; if the config has no default language it falls back to the first configured language's code (not a hardcoded `"ru"`). TestConfiguration is single-language `en`, so the `### Synonym` table shows one `en` row. An attribute with no synonym in the chosen language shows the first non-empty synonym, else blank — in the live output `TestSynAttr`/`BmTxTest` show synonyms but a `-` Type (untyped attributes).
- **Multiple FQNs are independent.** Each FQN is formatted in its own `##` block separated by `---`; one not-found object does not abort the others. Use this to batch-inspect several objects in one call.
- **UI-thread tool — don't hammer it concurrently** with other `Display.syncExec` tools (`get_form_screenshot`, `get_content_assist`, `get_symbol_info`). If the output channel garbles (a bare `Error`/`Done` instead of the tables), do NOT retry-spam — re-verify against the EDT log `D:\WS\EDT\.metadata\.log` (records the full request/response) and trust it over the dropped echo. (Both live calls here returned clean.)
