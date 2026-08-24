`dcs` addresses a data composition schema (DCS) or a form attribute's dynamic-list
configuration as one root plus an optional fragment. It supports `get`, `upsert`,
`update`, `replace` and `remove`; the action table below defines the valid root/layer
combinations.

Authoring covers the schema layer (data sources, query/object/union data sets, fields,
parameters, calculated fields, total fields, data-set links), the settings layer (default
settings, named variants, structure groups, tables, selection, filters, order, conditional
appearance, user fields, and data/output parameter values), and dynamic lists — their
ext-info scalars, schema-style fields/calculated fields/parameters, and `listSettings`,
which goes through the same settings implementation as a report variant.

Charts are read-only by design: `get` renders an existing chart and its address, and a
write never discards it, but chart authoring is not offered.

### Lossless XML round-trip

Use `format:"xml"` only with `action:"get"`, `type:"schema"`, and a bare schema root
without a `#/...` fragment:

```json
{
  "projectName": "MyProject",
  "fqn": "Report.Sales",
  "action": "get",
  "type": "schema",
  "format": "xml"
}
```

The response is a JSON page envelope:

```json
{
  "success": true,
  "totalChars": 134118,
  "offset": 0,
  "hasMore": true,
  "nextOffset": 40000,
  "hash": "0123456789abcdef0123",
  "xml": "<?xml version=..."
}
```

`xml` is a character chunk of the complete `DataCompositionSchema` XML produced by EDT's
own `DcsV8Serializer`. `limit` requests the raw chunk size (default `40000`) and `offset`
selects its zero-based character start. The tool measures the serialized JSON envelope
after escaping and shrinks a chunk when necessary, so the project-wide output guard never
truncates XML. Chunk boundaries never split a UTF-16 surrogate pair.

Page while `hasMore` is true, using the numeric `nextOffset` carried by those pages, and
concatenate `xml` in `offset` order. The terminal page always carries `hasMore=false` and
omits `nextOffset`. Verify that `hash` is unchanged on every page. The hash is the same
20-character structural hash returned by a normal Markdown get; if it changes during the
transfer, discard the chunks and restart because the schema changed mid-read:

```text
offset = 0; hash = null; chunks = []; hasMore = true
while hasMore:
  page = dcs(get schema, format="xml", offset=offset)
  require hash is null or page.hash == hash
  hash = page.hash
  chunks += page.xml
  hasMore = page.hasMore
  if hasMore: offset = page.nextOffset
xml = concatenate(chunks)
```

Every page request re-serializes the whole schema, so a transfer costs O(pages × schema
size). Callers whose clients tolerate larger results can raise `limit` to reduce the page
count. No server-side snapshot is cached; verify the unchanged `hash` on every page.

This is the lossless path for copying a schema whose full designer vocabulary is not
represented by the structured bodies below.

To import it, read the destination root once in the default `md` format to obtain that
destination's current hash, then replace it:

```json
{
  "projectName": "MyProject",
  "fqn": "Report.SalesCopy",
  "action": "replace",
  "type": "schema",
  "expectedHash": "<hash from the destination schema get>",
  "body": {"xml": "<DataCompositionSchema ...>...</DataCompositionSchema>"}
}
```

Send the reassembled WHOLE document in that one `replace`; `body.xml` is not chunked and
must never receive an individual page. It is mutually exclusive with every structured
schema member, is accepted only for `replace` + `schema` at a bare root, and still requires
`expectedHash`. The XML is deserialized before the write; malformed or truncated XML is
refused without mutation. Inside the existing BM write transaction, every imported schema
feature is deep-copied into the already attached external-property root. Keeping that root
preserves its BM FQN; the normal DCS force-export path then writes `Template.dcs` after
commit.

### Start with a root summary

Use the root's matching type and omit `body` and `expectedHash`:

```json
{
  "projectName": "MyProject",
  "fqn": "Report.Sales",
  "action": "get",
  "type": "schema"
}
```

Supported roots are:

| Root kind | FQN example | Root type |
| --- | --- | --- |
| Report main DCS | `Report.Sales` | `schema` |
| Common DCS template | `CommonTemplate.Analytics` | `schema` |
| Object-owned DCS template | `Report.Sales.Template.CustomDcs` | `schema` |
| Form-attribute dynamic list | `Catalog.Products.Form.ListForm.Attribute.List` | `dynamicList` |

A root read is deliberately a summary. It returns the current `hash`, a counts table
whose addresses can be copied into later calls, and one-line name tables. It does not
print full query text or recursively expand settings. A dynamic-list summary also
prints its scalar configuration; `queryText` is represented by its character count,
not by the query itself, and that row names the copyable `#/queryText` drill-down
address. Read that address with `type='dynamicList'` to retrieve the exact text.

### Addressing grammar

The part before `#` is resolved as an existing metadata FQN. The part after `#` is an
RFC-6901 pointer:

- Every pointer starts with `#/`.
- `/` separates decoded segments.
- Encode a literal `~` in a name as `~0` and a literal `/` as `~1`.
- Named collections use a natural key: `name` for a data source, data set, parameter,
  or variant; `dataPath` for fields, calculated fields, and total
  fields.
- Ordered nodes use a zero-based index. This applies to selection, filter, order,
  conditional-appearance, table row/column, and every structure item, including a
  grouping that has a `name`.
- Copy addresses from `get` output. Do not invent or persist MCP-only IDs.

Worked examples:

```text
Report.Sales#/dataSources/Local
Report.Sales#/dataSets/Sales
Report.Sales#/dataSets/Sales/fields/Customer
Report.Sales#/parameters/Period
Report.Sales#/calculatedFields/AmountWithTax
Report.Sales#/totalFields/Amount
Report.Sales#/defaultSettings
Report.Sales#/defaultSettings/filter/items/1
Report.Sales#/variants/ManagerView
Report.Sales#/variants/ManagerView/settings/items/0
Report.Sales#/variants/ManagerView/settings/items/0/filter/items/1
Catalog.Products.Form.ListForm.Attribute.List#/fields/Description
Catalog.Products.Form.ListForm.Attribute.List#/listSettings/order/items/0
```

For example, a data-set drill-down is:

```json
{
  "projectName": "MyProject",
  "fqn": "Report.Sales#/dataSets/Sales",
  "action": "get",
  "type": "dataSet"
}
```

It renders the complete data-set properties, full query in a fenced block, complete
field table, and a canonical address on every rendered node. A settings pointer renders
the entire nested settings subtree as an address-aware outline. Existing charts appear
as one read-only line with their address; chart authoring is unsupported and there is no
`chart` type.

If a segment cannot be resolved, the error names that segment and lists the keys or
indices that exist at its parent. Copy one of the listed values or read the parent
collection.

### Paging

At a bare root, pass a collection type to page that collection:

```json
{
  "projectName": "MyProject",
  "fqn": "Report.Sales",
  "action": "get",
  "type": "dataSet",
  "limit": 50,
  "offset": 0
}
```

For collection and root-summary pages, `limit` is an item count that defaults to 100 and
is clamped to 1..1000. For an exact composite node or an advertised `#/query` or
`#/queryText` scalar, it is a character count that defaults to 40000 and is bounded by
the measured output envelope. `offset` is zero-based in the corresponding unit and must
not be negative. The result prints `Next offset`; pass that value in the next call and
concatenate character chunks in offset order.
`field` pages all schema data-set fields, retaining each field's full
`#/dataSets/<name>(/items/<name>)*/fields/<dataPath>` address. On a dynamic-list root it
pages the dynamic list's own `#/fields` collection.

Settings collection types (`grouping`, `selection`, `filter`, `dataParameter`, `order`,
`conditionalAppearance`, `table`, `userField`, `outputParameter`, `userSettings`) refer
to `defaultSettings` for a schema and `listSettings` for a dynamic list. Inside a named
variant, every named holder reads by its own type at
`#/variants/<name>/settings/<holder>/items`. A structure collection takes its read type
from its owner: the settings object owns `#/variants/<name>/settings/items`, so that
polymorphic groupings-and-tables collection reads as `type='userSettings'`; a grouping
owns `#/variants/<name>/settings/items/<group-index>/items`, so that collection reads as
`type='grouping'`; and a table owns
`#/variants/<name>/settings/items/<table-index>/rows` and
`#/variants/<name>/settings/items/<table-index>/columns`, so both read as `type='table'`.
A single structure item at `#/variants/<name>/settings/items/<index>` reads by its own
type, `grouping` or `table`.

### Actions

| Action | Meaning | `body` | `expectedHash` | Current support |
| --- | --- | --- | --- | --- |
| `get` | Read a root summary, collection page, or full node | Must be absent | Must be absent; the current `hash` is returned | Implemented |
| `upsert` | Create by natural key, append to an ordered collection, or partially update an exact target; omitted members stay unchanged | Required | Required for every index-addressed target | Schema, settings, and dynamic lists |
| `update` | Modify an existing node only; never create | Required | Required for every index-addressed target | Schema, settings, and dynamic lists |
| `replace` | Authoritative replacement; omitted values reset and omitted collections clear | Required structured body, or `{xml:"..."}` for a bare schema root | Always required | Schema and settings, a dynamic list below `#/listSettings` |
| `remove` | Remove exactly one fragment-addressed node | Must be absent | Always required | Schema and settings, a dynamic list below `#/listSettings` |

`replace` and `remove` act on the settings layer. On a dynamic list that means an address below
`#/listSettings`; the list's own scalars, fields, calculated fields and parameters take `upsert` or
`update`, and asking them for `replace` is refused rather than quietly treated as an update.

An `update` addressed at an exact schema identity (`#/dataSets/<name>`, `#/dataSources/<name>`,
`#/parameters/<name>`, `#/calculatedFields/<dataPath>`, `#/totalFields/<dataPath>`,
`#/dataSets/<name>(/items/<name>)*/fields/<dataPath>`) may carry a DIFFERENT natural key in
its body, which renames that node in place. The rename is refused while anything still
refers to the old identity, and refused when the new key is already taken. Every other
action still requires the body key to match the pointer.

A rename and a `remove` are also refused when the address is AMBIGUOUS - the natural key
matches more than one existing node - and when a schema expression still names the identity.
Expression dependencies are matched as whole, case-insensitive tokens outside string literals,
and a token immediately followed by `(` is read as a function call rather than a field, so
`Сумма(Оборот)` does not pin a field named `Сумма`. Update the expression the refusal names,
re-run get, and retry.

An empty array is a no-op in `upsert` and clears that collection in `replace`. `update`
rejects schema-layer root/collection targets where no single existing node is selected;
settings holders and dynamic-list roots can be updated when they already exist. `remove`
rejects a bare root. Unknown body members are errors. The whole request is validated
before the first mutation, then committed in one BM write transaction and force-exported.

### Types and body shapes

The body shapes below define the current authoring contract. Optional members are
marked with `?`; omitted members have the action semantics from the table above.

Shared localized text and value shapes:

```text
PresentationSpec = "neutral text" | {"<languageCode>": "localized text", ...}
ValueSpec = {"kind": "field|parameter|expression|string|number|boolean|date|null",
             "value": <matching value>}
ValueTypeSpec = {"types": [{"kind": "Date|String|Number|Boolean|...", ...}]}
```

| `type` | Target / body shape |
| --- | --- |
| `schema` | Root schema: `{dataSources?, dataSets?, dataSetLinks?, calculatedFields?, totalFields?, parameters?, defaultSettings?, variants?}`. A link is `{sourceDataSet, destinationDataSet, sourceExpression, destinationExpression, parameter?, linkCondition?, startExpression?, required?}`; links have no natural key, so they are addressed by index (`#/dataSetLinks/<i>`) and read under this same `type="schema"`. For a lossless bare-root replacement, use only `{xml:"<DataCompositionSchema ...>..."}`. Structured `replace` is authoritative and will refuse unsupported designer content rather than drop it. |
| `dynamicList` | Dynamic-list ext-info: `{queryText?, mainTable?, dynamicDataRead?, autoFillAvailableFields?, customQuery?, autoSaveUserSettings?, getInvisibleFieldPresentations?, keyType?, keyField?, fields?, calculatedFields?, parameters?, listSettings?}`. Existing dynamic-list conversion safety gates still apply. |
| `dataSource` | `{name, type?}`; natural key is `name`; `type` defaults to `"Local"`. |
| `dataSet` | Query data set: `{name, type:"query", dataSource?, query?, autoFillFields?, fields?}`; natural key is `name`. Creating one requires `query`; an existing node may omit it. Object data set: `{name, type:"object", objectName}`. Union data set: `{name, type:"union", items:[...nested data sets]}`. An exact `replace` may declare a different `type` to change the subtype in place; omitting `type` keeps the existing one. |
| `field` | `{dataPath, field?, title?:PresentationSpec, role?, useRestriction?}`; natural key is `dataPath`. `DataCompositionField` values use their string path. |
| `parameter` | `{name, title?:PresentationSpec, valueType?:ValueTypeSpec, use?}`; natural key is `name`. |
| `calculatedField` | `{dataPath, title?:PresentationSpec, expression?}`; natural key is `dataPath`. An exact `replace` must carry `expression`: it has no valid empty value, so it cannot be reset by omission. |
| `totalField` | `{dataPath, expression?, groups?:string[]}`; natural key is `dataPath`. An exact `replace` must carry `expression`, as for `calculatedField`. |
| `variant` | `{name, presentation?:PresentationSpec, settings?}`; natural key is `name`. |
| `grouping` | `{name?, use?, groupFields?:{items:[{field?:ValueSpec, use?, groupType?, periodAdditionType?, periodAdditionBegin?:ValueSpec, periodAdditionEnd?:ValueSpec}]}, selection?, filter?, order?, outputParameters?, items?, ...GroupScaffold}`. Groups recurse through `items`; all group addresses use the returned index and hash. Renaming writes the group's `name` property without changing the indexed addressing rule. |
| `selection` | `{items:[{kind?:"field", field?:ValueSpec, title?:PresentationSpec, use?, viewMode?} | {kind:"group", field?:ValueSpec, title?:PresentationSpec, use?, placement?, items:[...], viewMode?} | {kind:"auto", use?}], ...HolderScaffold}`. Items are ordered/indexed. |
| `filter` | `{items:[{kind?:"item", left?:ValueSpec, comparisonType?, right?:ValueSpec[], use?, ...ItemScaffold} | {kind:"group", groupType?, use?, items:[...], ...ItemScaffold}], ...HolderScaffold}`. Groups can be nested; items are ordered/indexed. |
| `dataParameter` | `{items:[{parameter?:ValueSpec, value?:ValueSpec, use?, viewMode?, userSettingID?, userSettingPresentation?:PresentationSpec}]}`. Items are ordered/indexed. |
| `order` | `{items:[{kind?:"item", field?:ValueSpec, orderType?, use?, viewMode?} | {kind:"auto", use?}], ...HolderScaffold}`. Items are ordered/indexed. |
| `conditionalAppearance` | `{items:[{use?, selection?:{items:[{field?:ValueSpec, use?}]}, filter?, appearance?, presentation?:PresentationSpec, useInGroup?, useInHierarchicalGroup?, useInOverall?, useInFieldsHeader?, useInHeader?, useInParameters?, useInFilter?, useInResourceFieldsHeader?, useInOverallHeader?, useInOverallResourceFieldsHeader?, ...ItemScaffold}], ...HolderScaffold}`. Items are ordered/indexed. `appearance` keys are validated against the platform parameter list for the project version; an unknown key is refused and the valid keys are listed. |
| `table` | `{kind?:"table", name?, use?, rows?, columns?, selection?, conditionalAppearance?, outputParameters?, rowsViewMode?, rowsUserSettingID?, rowsUserSettingPresentation?, columnsViewMode?, columnsUserSettingID?, columnsUserSettingPresentation?, ...HolderScaffold}`. `rows` and `columns` hold group items and recurse like `grouping`. Tables are structure items, so they share the `items` tree and its indexed addressing. An exact `replace` of a structure item builds it from the `kind` in the body, so a grouping and a table can be exchanged at the same index. |
| `userField` | Expression field: `{kind:"expression", dataPath, use?, title?:PresentationSpec, detailExpression?, detailExpressionPresentation?, totalExpression?, totalExpressionPresentation?}`. Case field: `{kind:"case", dataPath, use?, title?:PresentationSpec, variants?}`. Items are ordered/indexed. |
| `outputParameter` | `{items:[{parameter?:ValueSpec, value?:ValueSpec, use?, viewMode?, userSettingID?, userSettingPresentation?:PresentationSpec}]}`. Items are ordered/indexed. |
| `userSettings` | Whole settings body: `{items?, selection?, filter?, dataParameters?, order?, conditionalAppearance?:{items:[], ...HolderScaffold}, outputParameters?, itemsViewMode?, itemsUserSettingID?, itemsUserSettingPresentation?:PresentationSpec}`. Never use these fields to store invented MCP IDs. |

`HolderScaffold` means `viewMode?`, `userSettingID?`, and
`userSettingPresentation?:PresentationSpec`. `GroupScaffold` includes those three plus
`itemsViewMode?`, `itemsUserSettingID?`, and
`itemsUserSettingPresentation?:PresentationSpec`. These members are written even when a
selection/filter/order holder has an empty `items` array, so the empty EDT scaffolding can
be reproduced faithfully.

For a schema-root batch, use the established plural payload vocabulary:
`dataSources`, `dataSets`, `parameters`, `calculatedFields`, and `totalFields`. A field
is nested under its query data set. For a singular write, use the corresponding row
body and either the root/collection address (`upsert`) or exact returned node address
(`update`). Unknown members are rejected before any model change.

Enum values are platform literals such as `Equal`, `AndGroup`, `Asc`, `Items`, and
`Normal`. An invalid comparison, order direction, grouping kind, or similar token is
rejected; the error names the bad value and lists every allowed platform literal.

### Settings examples

Create a variant with nested structure, selection, an AND/OR filter, and order:

```json
{
  "projectName": "MyProject",
  "fqn": "Report.Sales",
  "action": "upsert",
  "type": "variant",
  "body": {
    "name": "ManagerView",
    "presentation": {"en": "Manager view"},
    "settings": {
      "items": [{
        "name": "CustomerGroup",
        "groupFields": {"items": [{
          "field": {"kind": "field", "value": "Customer"},
          "groupType": "Items",
          "use": true
        }]},
        "items": [{"name": "PeriodGroup", "groupFields": {"items": [{
          "field": {"kind": "field", "value": "Period"},
          "groupType": "Items"
        }]}}]
      }],
      "selection": {
        "viewMode": "Normal",
        "userSettingID": "selection",
        "items": [{"field": {"kind": "field", "value": "Customer"}, "use": true}]
      },
      "filter": {"viewMode": "Normal", "userSettingID": "filter", "items": [{
        "kind": "group", "groupType": "AndGroup", "items": [
          {"left": {"kind": "field", "value": "Quantity"},
           "comparisonType": "Greater", "right": [{"kind": "number", "value": 0}]},
          {"kind": "group", "groupType": "OrGroup", "items": [
            {"left": {"kind": "field", "value": "Status"},
             "comparisonType": "Equal", "right": [{"kind": "string", "value": "Open"}]}
          ]}
        ]
      }]},
      "order": {"viewMode": "Normal", "userSettingID": "order", "items": [
        {"field": {"kind": "field", "value": "Customer"}, "orderType": "Asc"}
      ]}
    }
  }
}
```

To change the nested OR condition, first run `get`, then use its root hash and exact
index address:

```json
{
  "projectName": "MyProject",
  "fqn": "Report.Sales#/variants/ManagerView/settings/filter/items/0/items/1/items/0",
  "action": "update",
  "type": "filter",
  "expectedHash": "<20-character hash from get>",
  "body": {"kind": "item", "right": [{"kind": "string", "value": "Closed"}]}
}
```

Configure a dynamic list and reproduce empty settings holders in the same call:

```json
{
  "projectName": "MyProject",
  "fqn": "Catalog.Products.Form.ListForm.Attribute.List",
  "action": "upsert",
  "type": "dynamicList",
  "body": {
    "queryText": "SELECT Ref, Description FROM Catalog.Products",
    "customQuery": true,
    "dynamicDataRead": true,
    "autoSaveUserSettings": true,
    "fields": [{"dataPath": "Ref"}, {"dataPath": "Description"}],
    "listSettings": {
      "selection": {"items": [], "viewMode": "Normal", "userSettingID": "selection"},
      "filter": {"items": [], "viewMode": "Normal", "userSettingID": "filter"},
      "order": {"items": [], "viewMode": "Normal", "userSettingID": "order"},
      "conditionalAppearance": {
        "items": [], "viewMode": "Normal", "userSettingID": "appearance"
      }
    }
  }
}
```

Converting a plain form attribute is destructive. The existing consent gate, dynamic-list
type availability check, main-table resolution, and orphaned-column refusal all run before
the conversion. Request conversion with `action='upsert'`; `update` requires ext-info that
already exists and never converts a plain attribute. Query properties remain available through
`modify_metadata` as well.

Dynamic-list ext-info and schema-style items are stored with the content form and exported
to `Form.form`. `listSettings` is a separate external top object; EDT serializes and the
tool separately force-exports it as
`<Form>/Attributes/<AttributeName>/ExtInfo/ListSettings.dcss`.

### Hash and the get-edit-verify loop

Every successful `get` carries a short structural `hash`. It is a within-session stale
tree guard, not a cross-EDT-version content identifier.

1. Call `get` for the root, collection, or node and copy both the canonical address and
   root `hash`.
2. Prepare one mutation call. Pass that hash as `expectedHash` whenever the target uses
   an index; `replace` and `remove` always require it.
3. The server recomputes the hash inside the write
   transaction. If it changed, do not guess the new index: re-run `get`, inspect the new
   addresses, and rebuild the mutation.
4. Call `get` again and verify the intended node plus the new hash.

### Bilingual rules

- Only metadata type tokens in an FQN may be English or Russian. Object, template,
  form, attribute, field, and data-set names are programmatic `Name` values, never
  synonyms.
- `language` and localized presentation-map keys are language codes declared by the
  project, not language object names. Matching is case-insensitive but output uses the
  configuration's declared spelling. An undeclared code is rejected with the declared
  list. Omit `language` to use the project's default code.
- Query text and DCS expressions are preserved exactly. They are not synonym-resolved,
  normalized, or translated; both 1C query-language dialects remain valid data.
- A write into a language the configuration DECLARES but does not itself use reports
  `localeUnusedInConfiguration: true (<codes>)` in the result. It is not an error -
  the text will display - but the configuration's own synonym has no text in that
  language, so it may be a single-language build or one that is not supported yet. **Ask the
  user before translating further** rather than continuing to write into it.
