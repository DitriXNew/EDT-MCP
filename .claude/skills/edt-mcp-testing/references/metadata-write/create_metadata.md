# create_metadata — how to test

**Purpose.** Create a new metadata node addressed by a **1C full-name FQN** — either a **top-level object** (`Catalog.Products`) **or** a **subordinate member** (`Catalog.Products.Attribute.Weight`, `InformationRegister.Prices.Dimension.Product`, `AccumulationRegister.Stock.Resource.Quantity`, `Document.Order.TabularSection.Goods`, `Enum.Colors.EnumValue.Red`). The **kind is inferred from the FQN**: a `Type.Name` pair makes a top object; a `Type.Name.Kind.Name` quad makes a member of that object. The node is created with EDT default content (a generated UUID and default properties), registered/attached inside a **write transaction**, and the owning top object is force-exported to its `.mdo` on disk. This tool **unifies and replaces** the two removed tools `create_metadata_object` (top-level) and `add_metadata_attribute` (member) — one FQN-addressed entry point for both.

> **MUTATES the model and the source tree.** This is a BM **write** transaction that creates a brand-new `.mdo` file (for a top object) or rewrites the owner's `.mdo` (for a member) under `TestConfiguration/src`. Run it **only** on `TestConfiguration` and **always revert** afterward (procedure below). Do **not** run it live during routine reference drafting unless a fresh capture is explicitly requested.

**Preconditions.**
- Live (non-elevated) EDT copy, MCP on `:8765`, workspace `D:\WS\EDT`. After a plugin change redeploy with `pwsh D:\Soft\edt-redeploy.ps1` (it may **exit 1** yet print `MCP server UP on 8765` — that *is* success; confirm with `get_edt_version`, not the exit code).
- Project `TestConfiguration` open and `State=ready` — verify with `list_projects`. After a `-clean` relaunch the Xtext/BSL + BM index rebuilds for a while; a mutation against a still-indexing project can misbehave, so wait for `ready`.
- Runs on the SWT UI thread and mutates the in-memory `Configuration` strictly inside the write boundary (`BmTransactions.write(...)`, the explicit boundary required by CLAUDE.md don't #1). Do not hammer it concurrently with other UI-thread tools.
- **Mutate THROUGH MCP, not by hand-editing files.** A later `-clean` redeploy makes EDT reload from disk; mutating through this tool keeps the in-memory model and the on-disk `.mdo` in sync.
- **Settle between dependent creates.** Creating a top object triggers a derived-data rebuild; a *member* create that immediately follows can hit the `BUILDING` write-guard. When you create a top object and then a member on it, wait for `ready` (`wait_for_project_ready`) before the member call.

**Parameters.** Required: `projectName`, `fqn`. Optional: `properties`, `expectedNotExists`.
- `fqn` is a 1C full name. `Type.Name` → top object; `Type.Name.Kind.Name` → member. The type and kind tokens may be **English or Russian** (`Справочник`/`Catalog`, `Реквизит`/`Attribute`, `ТабличнаяЧасть`/`TabularSection`, `Измерение`/`Dimension`, `Ресурс`/`Resource`, `ЗначениеПеречисления`/`EnumValue`) — normalized to English before lookup. The **Name** segments are programmatic and are *never* translated.
- Supported **top-level** types: `Catalog`, `Document`, `InformationRegister`, `AccumulationRegister`, `Enum`, `CommonModule`, `Report`, `DataProcessor`. A recognized-but-unsupported top type (e.g. `Subsystem`) is rejected with a clear "not supported" error.
- `properties` is an array of `{name, value, language?}`. **At creation only `synonym` and `comment` are accepted**; any other property name is rejected with an actionable error pointing at `modify_metadata`. A `synonym`'s language is resolved by its `language` (else the config default-language **code**, else the first configured language's code — never a hardcoded `"ru"`), and stored under the language **CODE** key.
- `expectedNotExists` (optional) asserts the FQN does not already exist; duplicates are rejected regardless.
- **CAVEAT — one member level only.** Exactly one level of member under a top object is supported right now. A tabular-section field (nested-of-nested, depth-6, e.g. `Catalog.Catalog.TabularSection.Goods.Attribute.Foo`) is **rejected for now** ("not yet supported").

**Call (DOCUMENTED — not executed here).** Representative args against `TestConfiguration`:
```
# top-level object, with a synonym
create_metadata(
  projectName="TestConfiguration",
  fqn="Catalog.E2EUnifiedCatalog",
  properties=[{name:"synonym", value:"MCP Test Catalog", language:"en"}])

# member on an existing object (the add_metadata_attribute fold)
create_metadata(
  projectName="TestConfiguration",
  fqn="Catalog.Catalog.Attribute.E2EUnifiedAttr")
```

**Full test procedure (mutate-then-revert; explicit-request-only; do NOT run during reference drafting).**
1. **Confirm ready.** `list_projects` → `TestConfiguration` `State=ready`.
2. **Pick a fresh name** that does not already exist (the tool rejects duplicates). Read it back first to be sure — e.g. `get_metadata_objects(projectName="TestConfiguration", metadataType="catalogs")` should NOT already contain the chosen name.
3. **Create a top object.** `create_metadata(projectName="TestConfiguration", fqn="Catalog.E2EUnifiedCatalog")`. Expect `success:true` and structured `action:"created"`, `fqn:"Catalog.E2EUnifiedCatalog"`, `kind:"Catalog"`.
4. **Verify via a model read-back (PRIMARY).** `get_metadata_objects(projectName="TestConfiguration", metadataType="catalogs")` — the new name must now appear in the listing. A no-op create would leave it absent ⇒ failure. Optionally `get_metadata_details(...)` for the synonym row, and `get_project_errors(...)` to confirm no new markers.
5. **Confirm on disk (independent of the echo).** The create force-exports to disk with a **sub-second async lag**, so poll. For a top object: the new object's own `.mdo` carries `<name>E2EUnifiedCatalog</name>` and `Configuration.mdo` gains `<catalogs>Catalog.E2EUnifiedCatalog</catalogs>`. For a member: the **owner** `.mdo` (e.g. `Catalog.Catalog.mdo`) gains the new `<name>…</name>`.
6. **Member case.** Add an attribute on the existing `Catalog.Catalog`: `create_metadata(projectName="TestConfiguration", fqn="Catalog.Catalog.Attribute.E2EUnifiedAttr")`. Expect `action:"created"`, `kind:"CatalogAttribute"`, and the new `<name>` landing in `Catalogs/Catalog/Catalog.mdo`. For a NEW member kind the old `add_metadata_attribute` could not make (a register Resource): create `InformationRegister.E2EUnifiedReg` first, **wait for `ready`**, then `create_metadata(... fqn="InformationRegister.E2EUnifiedReg.Resource.E2EUnifiedRes")` → `kind:"InformationRegisterResource"`.
7. **REVERT — mandatory.** Restore the source tree and drop the new untracked `.mdo`:
   `git checkout HEAD -- TestConfiguration && git clean -fd -- TestConfiguration`.
   (`git clean -fd` is required — a newly created top object is an **untracked** file that `git checkout` alone will not remove.) Then, if EDT keeps running against the reverted tree, trigger a re-read / `-clean` redeploy (or `clean_project`) so the in-memory model drops the now-deleted node.

**Result.** JSON envelope (`ResponseType.JSON`, from `AbstractMetadataWriteTool`) — the payload lives in `structuredContent`; `content[0].text` is only the `Done`/`Error` placeholder. **Representative success shapes, mirrored from `test_create_metadata.py` assertions — *not* a live capture.**

Top object with a synonym:
```json
{
  "action": "created",
  "fqn": "Document.E2EUnifiedDoc",
  "kind": "Document",
  "name": "E2EUnifiedDoc",
  "persisted": true,
  "synonym": "E2E Doc",
  "language": "en",
  "message": "Object 'Document.E2EUnifiedDoc' created successfully. Run get_project_errors to verify, or revalidate_objects if needed."
}
```

Member (attribute) on an existing object — no synonym:
```json
{
  "action": "created",
  "fqn": "Catalog.Catalog.Attribute.E2EUnifiedAttr",
  "kind": "CatalogAttribute",
  "name": "E2EUnifiedAttr",
  "persisted": true,
  "message": "..."
}
```
Field meaning (from the e2e contract):
- **`action`** — always `created` on success; the quickest programmatic discriminator from an error envelope.
- **`fqn`** — the **normalized** full name (canonical English type/kind tokens + the programmatic Name), e.g. `Catalog.…` even if the call used `Справочник.…`.
- **`kind`** — the concrete created EClass: `Catalog` / `Document` / `InformationRegister` … for a top object; `CatalogAttribute`, `InformationRegisterResource`, etc. for a member.
- **`name`** — the programmatic Name of the created node (the last FQN segment).
- **`persisted`** — `true` once the owning top object's `.mdo` has been force-exported (the on-disk write; subject to the sub-second lag — poll the disk, don't trust the echo alone).
- **`synonym` / `language`** — present **only** when a `synonym` property was supplied; `language` is the resolved language **CODE** used as the synonym-map key. Emitted together or not at all.
- **`message`** — fixed success message pointing at `get_project_errors` / `revalidate_objects`.

**Error contract.** Genuine failures use `ToolResult.error(...)` → `{success:false, error:"…"}` with `isError:true` (the harness surfaces it via `error_text()`). Cases the e2e asserts, each with `assert_no_diff` (a rejected create must change nothing on disk):
- **Missing args:** `projectName` missing → names `projectName`, suggests `required`. `fqn` missing → names `fqn`, suggests `required`.
- **Non-existent project:** names the bogus project, suggests `not found` and `list_projects`.
- **Duplicate node:** creating `Catalog.Catalog` again → names `Catalog.Catalog`, suggests `already exists`.
- **Recognized-but-unsupported top type:** `Subsystem.…` → names `Subsystem`, suggests `not supported` (and lists `Catalog` among the supported set).
- **Unknown type token:** `Sprocket.…` → names the bad FQN, suggests the `Type.Name` shape.
- **Malformed FQN (no dot / odd arity):** a bare `JustAName` → names it, suggests the `Type.Name` shape (must NOT silently fall back).
- **Invalid identifier Name:** `Catalog.1Bad-Name` → names `1Bad-Name`, suggests `must start with` (a Name starts with a letter or `_`, then letters/digits/`_`).
- **Unsupported property at creation:** `properties:[{name:"indexing", …}]` → names `indexing`, suggests `synonym, comment` and `modify_metadata`.
- **Nested member (depth-6):** a tabular-section field → suggests `not yet supported`.

**Gotchas.**
- **Mutating + may create a new file — always revert with `git clean`.** A plain `git checkout HEAD -- TestConfiguration` will NOT remove a newly created top object's untracked `.mdo`; you must also run `git clean -fd -- TestConfiguration`. Members rewrite a tracked owner `.mdo`, which `git checkout` does restore — but run both anyway. Run only on `TestConfiguration`, only on explicit request.
- **Kind is inferred from FQN arity, not a parameter.** `Type.Name` → top; `Type.Name.Kind.Name` → member. Get the arity wrong and you create the wrong thing (or hit the unknown/malformed-FQN error). One member level only — depth-6 is rejected for now.
- **`persisted` + async export lag — poll the disk.** The on-disk `.mdo` is written *after* the model write with a sub-second lag. Verify BOTH a model read-back (`get_metadata_objects` / `get_metadata_details`) AND the on-disk `.mdo` (poll for the `<name>` / collection reference), not just the structured echo.
- **Settle before a dependent member create.** A top-object create kicks off a derived-data rebuild; firing the member create immediately can hit the `BUILDING` write-guard (and can even mask an intended rejection). Wait for `ready` between the two.
- **Bilingual — type/kind tokens bilingual, Names never; synonym keyed by language CODE.** Only the leading type token and the member-kind token are bilingual (`Catalog`/`Справочник`, `Attribute`/`Реквизит`, …) and are normalized to English. Every **Name** segment is programmatic and is never translated — pass the real `Name`, not a synonym. A `synonym` is stored under the language **code** key (`en`/`ru`), never the language object's *name* (using the name writes a key EDT never reads, leaving the synonym blank — CLAUDE.md don't #2). TestConfiguration is single-language `en`.
- **Duplicate guard.** Re-running with an existing FQN returns `already exists` and writes nothing — pick a fresh name each test, or revert between runs. `expectedNotExists` makes the non-existence check explicit.
- **JSON tool, with char escaping on the error path.** `ToolResult.toJson()` HTML-escapes `>`, `<`, `&`, `=`, and the apostrophe `'` as `\uXXXX`. Success/error messages contain apostrophes (`Object 'X' …`); if you assert on text, match a delimiter-free substring (e.g. `created successfully`, `is required`, `already exists`, `not yet supported`), never the raw `'…'`.
- **Flaky output channel — do NOT retry-spam a mutating tool.** A dropped/garbled echo does not mean the create failed; a blind retry can hit the duplicate guard or leave a stray node. Re-verify independently: model read-back for the FQN, `Test-Path` / poll the `.mdo`, and the EDT log `D:\WS\EDT\.metadata\.log`. Trust the model + disk state over the echoed text.
- **History.** This card supersedes the old `create_metadata_object` (top-level) and `add_metadata_attribute` (member) cards — both tools are removed; `create_metadata` is the single FQN-addressed replacement.
