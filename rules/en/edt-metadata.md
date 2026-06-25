# Working with EDT metadata (`.mdo`, Form, other XML)

## Main rule

Before editing `.mdo` or `Form.form` manually — **ask yourself**: is there an MCP tool that covers this?

| Task | Tool | Why it is better than doing it manually |
|---|---|---|
| Create a new top-level object | `create_metadata` (top-level FQN, e.g. `Catalog.Products`) | EDT default content (same as the "New" wizard); UUID and `producedTypes` are generated automatically |
| Add an attribute / tabular section / dimension / resource | `create_metadata` (member FQN, e.g. `Catalog.Products.Attribute.Weight`) | No risky manual XML editing; UUID is generated automatically |
| Set an object's / member's property or data type | `modify_metadata` (discover what is settable via `get_metadata_details(assignable: true)`) | Structured `type` spec and language-keyed synonyms; no risky manual XML editing |
| Rename an object / attribute / tabular section / dimension / resource | `rename_metadata_object` | Cascadingly updates BSL code, forms, roles, subsystems. Provides a preview of all change points |
| Delete an object / member, etc. | `delete_metadata` | Cleans up references across the entire project. Provides a preview |
| Find where a top-level object is used | `find_references` | Full search across metadata, BSL, forms, roles |

Manual `.mdo` editing is justified when:
- changing properties for which no assignable mapping exists in `modify_metadata` (e.g. exotic ones such as `FullTextSearch`, `Format`, `DataLockControlMode`, form `usePurposes`, etc.);
- performing a mass uniform change that is simpler to do with a search.

## `rename_metadata_object` / `delete_metadata` workflow

Both tools work in two phases — this protects you from cascading edits made blindly:

1. **Preview**: call the tool **without `confirm`** (or with `confirm: false`). You get:
   - a table of change points with indices, files, line/column, description and an "optional / required" flag (for rename); for `delete_metadata` — a preview of the affected references;
   - a list of problems (references that will be affected).
2. **Analysis**: review the list. If there are unwanted edits and they are marked as optional — note their indices.
3. **Execute**: call **with `confirm: true`**. For `rename_metadata_object` you can additionally pass `disableIndices: "2,3,5"` — this excludes those change points from being applied. **Only optional changes** can be disabled; required change points cannot be skipped.

The `maxResults` parameter of `rename_metadata_object` controls only the size of the preview table (default 20, `0` = unlimited). It does not affect execution itself.

### FQNs accepted by rename/delete

- Top-level: `Catalog.Products`, `Document.SalesOrder`, `CommonModule.MyModule`, `Subsystem.Sales`, etc. Russian type names are also accepted (`Справочник.Товары`).
- Nested child objects (member FQNs): `Catalog.Products.Attribute.Weight`, `Document.SalesOrder.TabularSection.Goods`, `InformationRegister.Prices.Dimension.Product`, `AccumulationRegister.Stock.Resource.Quantity`.

**Supported child types** in FQNs for rename/delete: `Attribute`, `TabularSection`, `Dimension`, `Resource` (and their Russian singular/plural forms: `Реквизит`, `ТабличнаяЧасть`, `Измерение`, `Ресурс`). Other kinds (Forms, Commands, Templates, EnumValues, AccountingFlags, etc.) are **not** handled by these tools — for those, either edit XML manually or use dedicated tools.

## `find_references` — top-level only

`find_references` searches for references to **top-level configuration objects** (`Catalog.X`, `Document.Y`, `CommonModule.Z`, etc.). Passing a sub-object (`Catalog.X.Attribute.Y`, `Document.Z.Form.MainForm`) **returns an error** with an explanatory message.

To find references to attributes / tabular sections, use:
- `rename_metadata_object` in preview mode (without `confirm`) — it shows every place where the attribute is mentioned, without actually renaming.
- Or `search_in_code` for a textual search across BSL.

## `create_metadata` — supported FQNs and types

Creates a metadata node addressed by a 1C full-name FQN. The **kind is inferred from the FQN**, and type/kind tokens may be English **or** Russian (`Справочник`/`Catalog`, `Реквизит`/`Attribute`, `ТабличнаяЧасть`/`TabularSection`, `Измерение`/`Dimension`, `Ресурс`/`Resource`, `ЗначениеПеречисления`/`EnumValue`).

- **Top-level object** — `Catalog.Products`, `Document.SalesOrder`, etc. Created with the same default content as the EDT "New" wizard (correct UUID, `producedTypes`, default forms/properties where applicable). Supported top-level types: `Catalog`, `Document`, `InformationRegister`, `AccumulationRegister`, `Enum`, `CommonModule`, `Report`, `DataProcessor`.
- **Subordinate member** — `Catalog.Products.Attribute.Weight`, `InformationRegister.Prices.Dimension.Product`, `AccumulationRegister.Stock.Resource.Quantity`, `Document.Order.TabularSection.Goods`, `Enum.Colors.EnumValue.Red`. The member is created with default properties and a **properly generated UUID** — do not pass a UUID by hand.

Parameters: `projectName`, `fqn` (required); `properties` (optional, an array of `{name, value, language?}`); `expectedNotExists` (optional). **At creation only `synonym` and `comment` are accepted** in `properties` — any other property errors and points to `modify_metadata`. `synonym` is written for the configuration default language unless `language` is given on that property. A duplicate (an object/member with the same FQN already exists) is rejected.

**Caveat:** only **one** level of member under a top object is supported right now — a tabular-section field (a nested-of-nested member, e.g. `Document.Order.TabularSection.Goods.Attribute.Price`) is rejected for now; add such fields by editing the `.mdo` manually.

To set the data type, synonyms, comment or any other assignable property of the new object/member — use `modify_metadata` (see below) or edit the `.mdo` afterwards. After creation, run `get_project_errors` (and `revalidate_objects` on the new object if validation looks stale).

## `modify_metadata` — setting properties and data types

Sets properties of an object **or** member by FQN. Parameters: `projectName`, `fqn`, `properties` (required, an array of `{name, value, language?}`). Each property is **validated**:
- it must be **assignable** — otherwise the error lists the assignable properties and points to `get_metadata_details(assignable: true)`;
- an enum-valued property must be one of the allowed literals — otherwise the error lists the allowed literals.

It can set `synonym` (language-keyed via the `language` field on the property), `comment`, and the data `type`.

The `type` value is a **structured spec**: `{"types":[{"kind":...}]}`, where each `kind` is either a primitive with inline qualifiers, or a reference:
- primitives: `String` (`length`), `Number` (`precision`, `scale`, `nonNegative`), `Boolean`, `Date` (`fractions` = `DateTime` | `Date` | `Time`);
- references: `{"kind":"Ref","ref":"Type.Name"}` or `{"kind":"CatalogRef","ref":"Name"}`;
- the list may mix several kinds — that produces a **composite** (multi-type) value.

To **rename**, use `rename_metadata_object` — `modify_metadata` refuses a `name` property. Discover exactly what is settable (per property: value kind, current value, allowed enum literals) with `get_metadata_details(assignable: true)`.

## Synonym language (`language`)

Read-side metadata tools (`get_metadata_objects`, `get_metadata_details`, `list_subsystems`, `get_subsystem_content`) accept a `language` parameter — the language code for `<synonym>` values (e.g. `ru`, `en`). If omitted, the configuration's default language is used. In Russian-language projects pass `language: "ru"` to get readable display names.

The write tools `create_metadata` and `modify_metadata` **can** set synonyms: pass a `synonym` property with a per-property `language` field (it is keyed by language code, e.g. `ru`/`en`). If `language` is omitted, the configuration's default language is used. You no longer have to set synonyms manually in the `.mdo`.

## Manual `.mdo` / Form XML editing: UUID v4

`.mdo` and `Form.form` files are XML where almost every structural element carries a `uuid="..."` attribute. EDT/1C use these as stable identifiers for refactoring, history and metadata indexes.

If you add a new element manually — **always generate a cryptographically random UUID v4**. Otherwise:
- two identical UUIDs → EDT will not be able to distinguish elements, refactoring breaks;
- a predictable UUID → collision with an existing project object.

This applies in particular to:
- `<attributes uuid="...">` — attributes
- `<tabularSections uuid="...">` and the nested `<attributes uuid="...">` inside them (tabular section fields)
- `<dimensions uuid="...">`, `<resources uuid="...">` — register dimensions / resources
- `<forms uuid="...">` — forms
- `<commands uuid="...">` — commands
- `<templates uuid="...">` — templates
- `<enumValues uuid="...">` — enumeration values
- Inside `Form.form`: new `<items>`, `<attributes>`, `<commands>` of the form

**Do not touch** `<producedTypes>` or the `typeId`/`valueTypeId` fields inside it — these are auto-generated reference types (`CatalogRef.X`, `CatalogManager.X`, etc.) that EDT/1C generate themselves. Overwriting them with a random UUID by hand desynchronizes the types from the index and breaks the `.mdo`.

### What NOT to do

- Placeholder UUIDs: `a1b2c3d4-...`, `00000000-...`, `11111111-...`
- Identical UUIDs for different elements in the same or neighboring files
- Sequential / predictable UUIDs
- Copying a UUID from a neighboring attribute "as a template"

### How to generate

PowerShell:
```powershell
[guid]::NewGuid().ToString()
```

In batch:
```powershell
1..5 | ForEach-Object { [guid]::NewGuid().ToString() }
```

Bash (Linux/macOS):
```bash
uuidgen
```

## Structure of an `<attributes>` element (template)

```xml
<attributes uuid="<generated-uuid-v4>">
  <name>AttributeName</name>
  <synonym>
    <key>ru</key>
    <value>Отображаемое название</value>
  </synonym>
  <type>
    <types>DataType</types>
  </type>
  <minValue xsi:type="core:UndefinedValue"/>
  <maxValue xsi:type="core:UndefinedValue"/>
  <fillValue xsi:type="core:UndefinedValue"/>
  <fullTextSearch>Use</fullTextSearch>
  <dataHistory>Use</dataHistory>
</attributes>
```

## Element ordering

When adding a new element — insert it **after** existing elements of the same type (attributes after attributes, tabular sections after tabular sections), preserving the sectional order of the `.mdo` file. Reordering existing elements is treated by EDT as a change to the object and may produce noisy diffs in source control.

## Forms (`Form.form`)

- Before modifying a form — `get_form_layout_snapshot` (YAML structure) or `get_form_screenshot` (PNG). Do not make changes "blind" without this.
- UUID rules for form attributes and elements are the same.
- If you change a form's binding to an object/attribute — the form is not automatically rebuilt; sync it with `revalidate_objects([FQN])` (it refreshes from disk first), then run `get_project_errors`. See "After any `.mdo` / `.form` edit" below.

## Referring to ref types in `<types>`

An attribute type referencing a metadata object:
- `CatalogRef.<Name>` — catalog
- `DocumentRef.<Name>` — document
- `EnumRef.<Name>` — enumeration
- `InformationRegisterRecordKey.<Name>` — information register record key
- and so on.

The object name in the reference must **exactly** match the object name in the configuration (case-sensitive).

## After any `.mdo` / `.form` edit

After a manual `.mdo` / `.form` edit, sync EDT in a **targeted** way: call `revalidate_objects([FQN])` for the affected objects (it refreshes the project from disk first, so it picks up the on-disk change), then run `get_project_errors` (or `get_problem_summary` after a batch of edits).

- For a single externally-edited object **do not use `clean_project`** — it is a full rebuild of the whole configuration (slow on large projects). Reach for it only when the project state is truly stuck/stale.
- `resync_to_disk` is **not** for this: it exports memory → disk and would clobber your manual edit. Use it only when you deliberately want the in-memory model written back to disk.
