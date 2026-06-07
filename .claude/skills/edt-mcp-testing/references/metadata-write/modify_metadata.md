# modify_metadata — how to test

**Purpose.** Set one or more **properties** of a metadata object *or* one of its members (attribute / tabular section / dimension / resource), addressed by a 1C full-name FQN. `properties` is an array of `{name, value, language?}`. Each property is **validated before any mutation** (fail-fast — nothing is written unless *every* property validates), then the owning top object is force-exported to its `.mdo` on disk. It can set the language-keyed **`synonym`**, the **`comment`**, and the data **`type`** (a structured type spec). To rename, use `rename_metadata_object` — `modify_metadata` refuses a `name` property and points there.

> **THINK TWICE — model + disk mutation.** This is a BM write tool: it mutates the in-memory model *and* rewrites the owner `.mdo`. Run it **only** on `TestConfiguration`, and **always revert** the source tree afterwards (mutate-then-revert discipline below). It is not cascading like `rename_metadata_object`, but a wrong value still dirties the model and a `.mdo` until you revert.

**Preconditions.**
- Running MCP server (`:8765`), live EDT workbench, workspace `D:\WS\EDT`. After a plugin change redeploy with `pwsh D:\Soft\edt-redeploy.ps1` (it may exit 1 yet print `MCP server UP on 8765` — that is success; confirm with `get_edt_version`, not the exit code). Use a **non-elevated COPY of EDT** (the `Program Files` install is elevated → relaunch triggers UAC).
- Project **`State=ready`** — poll `list_projects` until `ready` (after a `-clean` relaunch the BSL/Xtext + BM index rebuilds for a while). A cold/partial index can mis-report assignable properties or enum literals; do not assert against a partial model.
- The write happens inside a BM **write transaction**; the read-back (`get_metadata_details`) is a separate **read transaction**. Runs on the model/UI side — do not hammer it concurrently with other write tools (`create_metadata`, `rename_metadata_object`, `delete_metadata`).
- **Mutate THROUGH MCP.** This tool changes the in-memory BM model *and* the source tree. After a `-clean` redeploy EDT discards unsaved in-memory edits, so drive the change through this tool (not a hand edit of the `.mdo`) to keep model + disk in sync.
- **No infobase exclusivity needed.** It touches the source tree / model, not the infobase DB; it does not require freeing the IB.
- **Disk lag.** The owner top object is force-exported asynchronously after the write — there is a sub-second lag before the `.mdo` reflects the change. Poll the on-disk diff (the e2e harness uses `poll_diff_contains`), don't assert on the file instantly.

**Discovery companion.** `get_metadata_details(assignable: true)` is the read for *"what can I set, and to what"*: for each assignable property it returns the value **kind** (e.g. `ENUM`), the **current value**, and the **allowed enum literals**. Always discover the real property name + allowed value from there before composing a `modify_metadata` call — property names and enum literals are model-driven, not guessable.

**Call (representative).** Targets against `TestConfiguration` (`Catalog.Catalog` exists, with attribute `Attribute`; `CommonModule.OK` is a non-ref object). Members are seeded in tests via `create_metadata` on `Catalog.Catalog.Attribute.<Name>`.

Set a comment on an object:
```
modify_metadata(
  projectName="TestConfiguration",
  fqn="Catalog.Catalog",
  properties=[{"name": "comment", "value": "E2E modify comment"}]
)
```

Set a language-keyed synonym:
```
modify_metadata(
  projectName="TestConfiguration",
  fqn="Catalog.Catalog",
  properties=[{"name": "synonym", "value": "E2ESynonymMod", "language": "en"}]
)
```

Set an enum property on an attribute (property name + value discovered from `get_metadata_details(assignable:true)`):
```
modify_metadata(
  projectName="TestConfiguration",
  fqn="Catalog.Catalog.Attribute.E2EModEnumAttr",
  properties=[{"name": "<discovered enum prop>", "value": "<discovered allowed literal>"}]
)
```

Set a structured `type` (primitive `Number` with precision/scale):
```
modify_metadata(
  projectName="TestConfiguration",
  fqn="Catalog.Catalog.Attribute.E2ETypeNumAttr",
  properties=[{"name": "type", "value": {"types": [{"kind": "Number", "precision": 10, "scale": 2}]}}]
)
```

Set a `type` to a reference (Catalog):
```
modify_metadata(
  projectName="TestConfiguration",
  fqn="Catalog.Catalog.Attribute.E2ERefTypeAttr",
  properties=[{"name": "type", "value": {"types": [{"kind": "Ref", "ref": "Catalog.Catalog"}]}}]
)
```

**The `type` value is a STRUCTURED spec**, never a bare string: `{"types": [{"kind": ...}]}`, where each entry's `kind` is one of —
- `String` — with `length`.
- `Number` — with `precision`, `scale`, `nonNegative`.
- `Boolean`.
- `Date` — with `fractions` = `DateTime` | `Date` | `Time`.
- a reference — `{"kind": "Ref", "ref": "Type.Name"}` (e.g. `Catalog.Catalog`) or `{"kind": "CatalogRef", "ref": "Name"}`.

The `types` list may mix several entries (a composite type). Pointing a `Ref` at an object with no reference type (e.g. a `CommonModule`) is a **clean actionable error**, not a crash. A malformed spec (a bare string, or missing `types`/`kind`) **fails fast** with a shape error.

**Full test procedure (mutate-then-revert; `TestConfiguration` only).**
1. **Confirm ready & clean.** `list_projects` → `TestConfiguration` `State=ready`. `git status -- TestConfiguration` should be clean so the post-revert diff is meaningful.
2. **Discover.** `get_metadata_details(projectName="TestConfiguration", objectFqns=["Catalog.Catalog.Attribute.Attribute"], assignable=true)` — note the heading **"Assignable properties"**, the **"Allowed values"** column, and at least one `| ENUM |` row. Pick a real property name + an allowed literal from it.
3. **Happy paths (each asserts the structured echo AND disk):**
   - **comment** — set `comment` on `Catalog.Catalog`; expect `action="modified"`, `comment` in `applied`; then poll the `.mdo` diff for the comment text.
   - **synonym** — set `synonym` with `language:"en"`; expect `synonym` in `applied`; poll the `.mdo` diff for the synonym text.
   - **enum on a member** — seed an attribute via `create_metadata` (`Catalog.Catalog.Attribute.E2EModEnumAttr`), wait ready, discover its enum prop+value, set it; expect that prop in `applied`.
   - **structured type Number** — seed an attribute, set `type` = `Number(precision=10, scale=2)`; expect `type` in `applied`; poll the owner `.mdo` diff for `precision`.
   - **structured type Ref → Catalog** — seed an attribute, set `type` = `{"kind":"Ref","ref":"Catalog.Catalog"}`; expect `type` in `applied`.
4. **Validation matrix (each reject must be actionable AND change nothing — assert no git diff):**
   - **unknown property** — `name:"noSuchProperty_e2e"` → error names the bad property and suggests `not assignable` / `Assignable properties` / `assignable:true`.
   - **`name` property** — `name:"name"` → error points at `rename_metadata_object`.
   - **bad enum value** — discover a real enum prop, send `NotAValidLiteral_zzz` → error names the bad value and lists the **allowed literals** inline (the discovered one included).
   - **ref to a non-ref object** — `type` = `{"kind":"Ref","ref":"CommonModule.OK"}` → clean error containing `not a reference type` (NOT a crash / AssertionError leak).
   - **malformed type spec** — `type` value is a bare string `"String"` → error references the expected `types` / `kind` shape.
   - **empty value** — `comment` = `""` → rejected (never a silent clear); error names `comment` and suggests `non-empty` / `does not clear`.
   - **missing `properties`** — omit `properties` → error names `properties`, suggests `required`.
   - **missing `projectName`** → error names `projectName`, suggests `required` / `list_projects`.
   - **nonexistent node** — `fqn="Catalog.DoesNotExist_e2e"` → error names the FQN, suggests `not found` / `get_metadata_objects`.
5. **Verify a successful write (BOTH model and disk):**
   - **Model read-back:** `get_metadata_details(projectName="TestConfiguration", objectFqns=["Catalog.Catalog"])` → the new comment/synonym/type is present.
   - **Disk:** poll the owner `.mdo` (`Catalogs/Catalog/Catalog.mdo`) for the new literal — there is a sub-second async export lag, so poll, don't read once.
   - **Sanity:** `get_project_errors(projectName="TestConfiguration")` — a valid property change should not introduce new errors.
6. **REVERT (mandatory).** Restore the source tree:
   `git checkout HEAD -- TestConfiguration && git clean -fd -- TestConfiguration`.
   Then bring the **in-memory model** back in line with the reverted disk — the safest reset is a `pwsh D:\Soft\edt-redeploy.ps1` `-clean` relaunch (it reloads the model from disk), or re-import. Do **not** leave EDT running with a model that no longer matches the reverted `.mdo` files. (The e2e harness resets via `reset_model()` after every `write-metadata` test.)

**Result.** JSON-responseType tool — the data is in the structured payload, with a `Done`/`Error` placeholder in the text channel. Shape below is **representative, from what `test_modify_metadata.py` asserts — not a live capture.**

Success (structured payload):
```json
{
  "action": "modified",
  "fqn": "Catalog.Catalog",
  "applied": ["comment"],
  "persisted": true,
  "message": "..."
}
```

Field/shape notes:
- **`action`** — `"modified"` on success; the quickest programmatic discriminator from an error envelope.
- **`applied`** — the list of property names actually written (e.g. `comment`, `synonym`, the discovered enum prop, `type`). Assert membership here, not on the placeholder text.
- **`persisted`** — the model write committed; the on-disk `.mdo` follows after the async force-export (verify it separately via the diff).
- **`fqn`** — echoes the addressed node.

**Gotchas.**
- **Validation is fail-fast and atomic.** Every property is checked *before* any mutation — one bad property in the array rejects the whole call and writes nothing. A rejected `modify_metadata` must leave **zero git diff** (the e2e tests assert `assert_no_diff`). If you ever see a partial write after a rejection, that's a bug.
- **Discover, don't guess.** Property names and enum literals are model-driven. Read `get_metadata_details(assignable:true)` first; the error on an unknown property *also* lists the assignable set and points back to `assignable:true`, and the error on a bad enum literal lists the allowed values inline.
- **`name` is reserved for rename.** Setting `name` is refused with a pointer to `rename_metadata_object` — `modify_metadata` never renames (no cascade).
- **`type` is structured, never a string.** `"value": "String"` is a malformed spec (shape error citing `types`/`kind`). Use `{"types":[{"kind":...}]}`. A `Ref` at a non-ref object (e.g. `CommonModule.OK`) is a clean error (`not a reference type`), not a crash — the underlying `getRefType` would `AssertionError` on such kinds, and the tool converts that to an actionable message.
- **Empty value never clears.** `value: ""` is rejected (error suggests `non-empty` / `does not clear`) — it does NOT silently null the property.
- **Mutation safety / revert.** After any successful write, revert with `git checkout HEAD -- TestConfiguration && git clean -fd -- TestConfiguration`, then `-clean`-relaunch EDT so the in-memory model reloads from the reverted disk (otherwise model and `.mdo` disagree until restart). Always mutate *through MCP*, never by hand-editing `.mdo`.
- **Disk lag.** The owner `.mdo` is exported asynchronously (sub-second). Poll the diff (`poll_diff_contains`) for the new literal rather than reading the file once.
- **Flaky output channel.** This is a JSON-responseType tool, so the text channel is a bare `Done`/`Error` placeholder — read the **structured** payload (`action`/`applied`/`persisted`), not the echoed text. If output comes back garbled, do NOT retry-spam a write; re-verify independently via `get_metadata_details` and `git status -- TestConfiguration`, and consult the EDT log `D:\WS\EDT\.metadata\.log`.
- **Bilingual.** The leading **TYPE token** of the FQN is bilingual (`Справочник`/`Catalog`, child tokens `реквизит`/`Attribute` etc.) and is normalized before resolution; the object/member **NAME** is programmatic and never translated — pass the real `Name`. The **`synonym`** is language-keyed via the property's **`language`** (a language **code** `en`/`ru`, NOT the language name) — that is a separate concern from the object's `Name`.
