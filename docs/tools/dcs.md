# dcs

Read, author, and losslessly XML-round-trip 1C DCS schemas, settings variants, form conditional appearance, and form dynamic lists. Call action='get' first; replace, remove and any index-addressed edit require its hash as expectedHash. Query autoFillFields=true fills and exports fields and parameters in this call. Writes wait for disk export. Call get_tool_guide('dcs') for body shapes.

## Parameters
| Parameter | Required | Type | Description |
| --- | --- | --- | --- |
| projectName | yes | string | EDT project name. |
| fqn | yes | string | DCS root FQN, optionally followed by an RFC-6901 '#/...' pointer. |
| action | yes | string (one of: get, options, upsert, update, replace, remove) | Operation; options lists version-aware writable vocabularies, replace resets omitted members, and remove deletes one node. |
| type | yes | string (one of: schema, dynamicList, dataSource, dataSet, field, fieldFolder, parameter, calculatedField, totalField, variant, grouping, selection, filter, dataParameter, order, conditionalAppearance, table, chart, userField, outputParameter, userSettings) | Target kind; body shapes are in get_tool_guide('dcs'). |
| body | — | object | Mutation body; forbidden for get/options/remove and required by the other mutations. |
| expectedHash | — | string | Hash from get; conditionally required for mutation actions. |
| language | — | string | Optional declared configuration language code for localized values and presentations; parameter names always use the configuration's default language code. |
| format | — | string (one of: md, xml) | Read output; defaults to md. xml is only for a bare schema get. |
| limit | — | integer | Markdown collection/summary item count (default 100, maximum 1000), or exact-value/XML chunk characters (default 40000, bounded by the output envelope). |
| offset | — | integer | Zero-based item or character offset for get/options pagination. |

## Guide
`dcs` addresses a schema or form-backed root plus an optional fragment. The action and
body tables define supported operations. Dynamic-list `listSettings` shares the report
settings writer.

Changing an empty MCP template to `templateType=DataCompositionSchema` immediately
exports `Templates/<name>/Template.dcs`. An empty schema also supports `get format=xml`.
For a submitted query or `autoFillFields` flag, an enabled query data set fills fields
and missing parameters synchronously using EDT's designer routines, then exports them
in this call. With no explicit fields, auto-fill defaults to true; explicit fields
default it to false. Set `autoFillFields:false` to author fields/parameters yourself.
Existing parameter defaults are preserved. A fill failure refuses the whole write;
resolve the query sources or use explicit fields/parameters with auto-fill disabled.

Nested data sets are deliberately excluded from the typed authoring surface. `get` renders
existing ones and their addresses read-only; carry a schema containing them through the lossless
XML channel below (`action='replace'`, `type='schema'`, `body={xml:...}` on a bare schema root),
which preserves them untouched. A chart's appearance values nested under its `ChartType` output
parameter (label type, legend, axes and the like) are not authorable either: an update keeps them,
and a `replace` that would drop them is refused. A `replace` is also refused when it would drop a
value its writer cannot restate, in settings data parameters and output parameters (including
grouping, table and chart output parameters), conditional appearance, input parameters, and the
untyped value slots (a filter's left and right side, a group field's period bounds, additional
properties). The writer's own dry run decides; the refused values include, for example: a value
the writer would not rebuild exactly (a value kind it cannot build, an empty field, parameter or
expression, a localized text in a language the configuration does not declare or a formatted
localized text, a color channel outside 0-255, an AutoColor, which EDT saves as #000000, or a color
reference whose name no longer resolves), more values than the parameter takes, an input parameter
without a parameter name, a conditional-appearance item with no value, a name absent from the
platform catalogue for the project's version (including an older alias), a value that does not
match the declared type, or a font the writer cannot rebuild exactly. A font is accepted only
when a dry run of the font writer rebuilds it exactly, including every feature and its set or
unset state. Use
`action='update'` on those items to keep every member the body does not name; on a schema root you can also carry the whole schema through the lossless XML
channel. A schema parameter's defaults (`values`) and available values (`availableValues`),
including available values on data-set and calculated fields, are also dry-run through their
declared-value writer using the containing parameter or field's `valueType`. A `replace` is refused
if that writer rejects a value or cannot rebuild it exactly.

### Lossless XML round-trip

Use `format:"xml"` only with `action:"get"`, `type:"schema"`, and a bare schema root
without a `#/...` fragment:

```json
{"projectName":"MyProject","fqn":"Report.Sales","action":"get","type":"schema","format":"xml"}
```

The response is a JSON page envelope:

```json
{"success":true,"totalChars":134118,"offset":0,"hasMore":true,"nextOffset":40000,"hash":"0123456789abcdef0123","xml":"<?xml version=..."}
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
{"projectName":"MyProject","fqn":"Report.SalesCopy","action":"replace","type":"schema","expectedHash":"<hash from the destination schema get>","body":{"xml":"<DataCompositionSchema ...>...</DataCompositionSchema>"}}
```

Send the reassembled WHOLE document in that one `replace`; `body.xml` is not chunked and
must never receive an individual page. It is mutually exclusive with every structured
schema member, is accepted only for `replace` + `schema` at a bare root, and still requires
`expectedHash`. The XML is deserialized before the write; malformed or truncated XML is
refused without mutation. Inside the existing BM write transaction, every imported schema feature
is deep-copied into the already attached external-property root, preserving its BM FQN. The attached
model is compared with the parsed schema, including counts of root data sources, root data sets,
concrete fields (including union/folder fields), schema parameters, and variants. It is also
re-serialized and compared asymmetrically with the submitted document: EDT-added defaults and
namespace-prefix/formatting changes are allowed. A model difference or a lost submitted XML path
refuses and rolls back. All writes await export off the UI thread, outside the BM transaction,
within a shared 60s budget. `Export scheduled` means submission; `Export drained: true` confirms
queue completion, not byte correctness. Pending/unobservable export returns an error with
`mutationCommitted:true`; nothing is rolled back. Check `list_projects`, save/resync, then re-read
before retrying. XML replacement also reports `xml=wholesale` and those model counts.

### Start with a root summary

Use the root's matching type and omit `body` and `expectedHash`:

```json
{"projectName":"MyProject","fqn":"Report.Sales","action":"get","type":"schema"}
```

Supported roots are:

| Root kind | FQN example | Root type |
| --- | --- | --- |
| Report main DCS | `Report.Sales` | `schema` |
| External report main DCS | `ExternalReport.Sales` | `schema` |
| Common DCS template | `CommonTemplate.Analytics` | `schema` |
| Object-owned DCS template | `Report.Sales.Template.CustomDcs` | `schema` |
| Managed form conditional appearance | `Catalog.Products.Form.ListForm` | `conditionalAppearance` |
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
  or variant; `dataPath` for fields, field folders, calculated fields, and total
  fields.
- Ordered nodes use a zero-based index. This applies to selection, filter, order,
  conditional-appearance, table row/column, chart point/series, and every structure item, including a
  grouping that has a `name`.
- Copy addresses from `get` output. Do not invent or persist MCP-only IDs.

Worked examples:

```text
Report.Sales#/dataSources/Local
Report.Sales#/dataSets/Sales
Report.Sales#/dataSets/Sales/fields/Customer
Report.Sales#/dataSets/Sales/fields/CustomerFolder/fields/CustomerFolder.Name
Report.Sales#/parameters/Period
Report.Sales#/calculatedFields/AmountWithTax
Report.Sales#/totalFields/Amount
Report.Sales#/defaultSettings
Report.Sales#/defaultSettings/filter/items/1
Report.Sales#/variants/ManagerView
Report.Sales#/variants/ManagerView/settings/items/0
Report.Sales#/variants/ManagerView/settings/items/0/filter/items/1
Catalog.Products.Form.ListForm
Catalog.Products.Form.ListForm#/items/0
Catalog.Products.Form.ListForm.Attribute.List#/fields/Description
Catalog.Products.Form.ListForm.Attribute.List#/listSettings/order/items/0
```

For example, a data-set drill-down is:

```json
{"projectName":"MyProject","fqn":"Report.Sales#/dataSets/Sales","action":"get","type":"dataSet"}
```

It renders the complete data-set properties, full query in a fenced block, complete
field table, and a canonical address on every rendered node. A settings pointer renders
the entire nested settings subtree as an address-aware outline. A chart node also lists
its references under `## Chart references`: each point, series and measure with its address
and a `Drawn` column, `no` when it, its chart, or a group or folder above it is switched off.

If a segment cannot be resolved, the error names that segment and lists the keys or
indices that exist at its parent. Copy one of the listed values or read the parent
collection.

### Paging

At a bare root, pass a collection type to page that collection:

```json
{"projectName":"MyProject","fqn":"Report.Sales","action":"get","type":"dataSet","limit":50,"offset":0}
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
`conditionalAppearance`, `table`, `chart`, `userField`, `outputParameter`, `userSettings`) refer
to `defaultSettings` for a schema and `listSettings` for a dynamic list. Inside a named
variant, every named holder reads by its own type at
`#/variants/<name>/settings/<holder>/items`. A structure collection takes its read type
from its owner: the settings object owns `#/variants/<name>/settings/items`, so that
polymorphic structure collection reads as `type='userSettings'`; a grouping
owns `#/variants/<name>/settings/items/<group-index>/items`, so that collection reads as
`type='grouping'`; and a table owns
`#/variants/<name>/settings/items/<table-index>/rows` and
`#/variants/<name>/settings/items/<table-index>/columns`, so both read as `type='table'`;
a chart's `points` and `series` likewise read as `type='chart'`.
A single structure item at `#/variants/<name>/settings/items/<index>` reads by its own
type, `grouping`, `table` or `chart`.

A form's own conditional appearance is rooted directly at the form FQN, for example
`Catalog.Products.Form.ListForm` with `type='conditionalAppearance'`; its rules continue
at `#/items/<index>`. This is distinct from a dynamic-list attribute below that form.

### Actions

| Action | Meaning | `body` | `expectedHash` | Current support |
| --- | --- | --- | --- | --- |
| `get` | Read a root summary, collection page, or full node | Must be absent | Must be absent; the current `hash` is returned | Implemented |
| `options` | Read version-aware parameter keys, declared types, and enum literals accepted by writes | Must be absent | Must be absent | Conditional appearance, output parameters, and enum-bearing body members; supports `limit`/`offset` |
| `upsert` | Create by natural key, append to an ordered collection, or partially update an exact target; omitted members stay unchanged | Required | Required for every index-addressed target | Schema, settings, dynamic lists, and form conditional appearance |
| `update` | Modify an existing node only; never create | Required | Required for every index-addressed target | Schema, settings, dynamic lists, and form conditional appearance |
| `replace` | Authoritative replacement; omitted values reset and omitted collections clear | Required structured body, or `{xml:"..."}` for a bare schema root | Always required | Schema/settings, form conditional appearance, and a dynamic list below `#/listSettings` |
| `remove` | Remove exactly one addressed node | Must be absent | Always required | Schema/settings, form conditional appearance, and a dynamic list below `#/listSettings` |

For example, ask the target platform for the output-parameter vocabulary before writing:

```json
{"projectName":"MyProject","fqn":"Report.Sales","action":"options","type":"outputParameter","limit":100}
```

The answer uses the project's language spelling and notes that the other English/Russian
spelling is accepted on input. A schema, managed form, and dynamic list use their own
conditional-appearance catalogues. The declared value type is the concrete catalogue default
accepted by the writer. If the platform type description has multiple entries, `options` reports
that writable type rather than an apparent union; for example, `Text` reports `LocalString`.
Options are paged rows and never return or require a hash.

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
PresentationSpec = "text" | {"<languageCode>": "localized text", ...}
  A plain string is stored under the language the call is working in: the `language`
  parameter when one is given, and the CONFIGURATION's default language when it is not.
  It is not stored as a language-neutral value: EDT never writes the neutral form and
  platform checks assume it is absent. A read falls back to the first non-empty localized
  value when the requested language is absent; pass the map form to set more than one.
ValueSpec = {"kind": "field|parameter|expression|string|number|boolean|date|null",
             "value": <matching value>}
          | {"kind": "standardPeriod", "value": {"variant"?: string, "startDate"?: date,
                                                  "endDate"?: date}}
          | {"kind": "standardBeginningDate", "value": {"variant"?: string, "date"?: date}}
          | {"kind": "valueList", "value": {"items"?: [{"value"?: ValueSpec, "presentation"?: string,
                                                        "checkState"?: int, "id"?: int}],
                                            "lastId"?: int}}
  A date is a platform date literal such as "2026-01-31T23:59:59". A variant is the platform
  literal (ThisMonth, LastYear, BeginningOfThisDay, ...); it defaults to Custom, the variant that
  uses the dates. A valueList is the platform ValueList with an empty value type, as the DCS
  designer stores it in a filter's right side; each item value is itself a ValueSpec. An item
  without "id" takes one more than the highest id before it (the first takes 1), and "lastId"
  defaults to the highest item id, or -1 for an empty list. A list's value type and available
  values, and an item's picture and format presentation, are not authorable.
  As a group field's periodAdditionBegin/periodAdditionEnd, {"kind":"null"} means the
  bound is not set; see the `grouping` row.
AvailableValueSpec = {"value":ValueSpec, "presentation"?:PresentationSpec}
InputParametersSpec = {"items":[{"parameter":ValueSpec(kind=parameter),
                                  "values"?:ValueSpec[], "use"?:bool}]}
OrderExpressionSpec = {"expression":string, "orderType"?:"Asc|Desc", "autoOrder"?:bool}
ValueTypeSpec = {"types":[{"kind":"String","length"?:int,"fixed"?:bool} | {"kind":"Number","precision"?:int,"scale"?:int,"nonNegative"?:bool} | {"kind":"Date","fractions"?:"DateTime|Date|Time"} | {"kind":"Boolean"} | {"kind":"Ref|...Ref","ref":string} | {"kind":"ValueStorage|UUID|..."}]}
```

| `type` | Target / body shape |
| --- | --- |
| `schema` | Root schema: `{dataSources?, dataSets?, dataSetLinks?, calculatedFields?, totalFields?, parameters?, defaultSettings?, variants?}`. A link is `{sourceDataSet, destinationDataSet, sourceExpression, destinationExpression, parameter?, parameterListAllowed?, linkConditionExpression?, startExpression?, required?}`; supplying `parameterListAllowed` sets the model's unsettable feature. The legacy alias `linkCondition` is accepted, but reads use the canonical `linkConditionExpression`. Links have no natural key, so they are addressed by index (`#/dataSetLinks/<i>`) and read under this same `type="schema"`. For a lossless bare-root replacement, use only `{xml:"<DataCompositionSchema ...>..."}`. Structured `replace` is authoritative and will refuse unsupported designer content rather than drop it. |
| `dynamicList` | Dynamic-list ext-info: `{queryText?, mainTable?, dynamicDataRead?, autoFillAvailableFields?, customQuery?, autoSaveUserSettings?, getInvisibleFieldPresentations?, keyType?, keyField?, fields?, calculatedFields?, parameters?, listSettings?}`. Existing dynamic-list conversion safety gates still apply. |
| `dataSource` | `{name, type?}`; natural key is `name`; `type` defaults to `"Local"`. |
| `dataSet` | Query data set: `{name, type:"query", dataSource?, query?, autoFillFields?, fields?}`; natural key is `name`. Creating one requires `query`; an existing node may omit it. Object data set: `{name, type:"object", objectName}`. Union data set: `{name, type:"union", items:[...nested data sets]}`. Field entries may declare `kind:"field"` (the default) or `kind:"folder"`; a folder's `fields` recursively carries its children. Every child keeps its full dotted `dataPath`. An exact `replace` may declare a different `type` to change the subtype in place; omitting `type` keeps the existing one. |
| `field` | `{kind?:"field", dataPath, field?, title?:PresentationSpec, role?, useRestriction?, valueType?:ValueTypeSpec, appearance?:object, attributeUseRestriction?, presentationExpression?:string, orderExpressions?:OrderExpressionSpec[], inHierarchyDataSet?:string, inHierarchyDataSetParameter?:string, availableValues?:AvailableValueSpec[], inputParameters?:InputParametersSpec}`; natural key is `dataPath`. Appearance uses the same platform-typed keys and merge-on-update behavior as conditional appearance. `inHierarchyDataSet` and `inHierarchyDataSetParameter` must name a data set and a parameter that the assembled schema actually contains, and removing or renaming that target is refused while a field still points at it. |
| `fieldFolder` | `{kind?:"folder", dataPath, title?:PresentationSpec, useRestriction?, fields?:[(field | fieldFolder), ...]}`; natural key is the full dotted `dataPath`. Its child collection is the returned `<folder-address>/fields`; nested fields and folders use that address while the platform stores the same hierarchy as flat dotted paths. |
| `parameter` | `{name, title?:PresentationSpec, valueType?:ValueTypeSpec, use?, values?:ValueSpec[], availableValues?:AvailableValueSpec[], expression?:string, useRestriction?:bool, valueListAllowed?:bool, availableAsField?:bool, denyIncompleteValues?:bool, functionalOptionsParameter?:string, inputParameters?:InputParametersSpec}`; natural key is `name`. `values` is the model's plural list containing the default value(s); String/Number/Boolean/Date/Null defaults are checked against and serialized as the declared `valueType`. Unsupported declared ValueSpec pairings are refused. |
| `calculatedField` | `{dataPath, title?:PresentationSpec, expression?, valueType?:ValueTypeSpec, appearance?:object, useRestriction?, presentationExpression?:string, orderExpression?:OrderExpressionSpec[], availableValues?:AvailableValueSpec[], inputParameters?:InputParametersSpec}`; natural key is `dataPath`. Note the model's singular `orderExpression` list name. Creation and an exact `replace` must carry `expression`; pass an empty string only when intentionally resetting it. An existing calculated field may keep an empty expression during a partial update. |
| `totalField` | `{dataPath, expression?, groups?:string[]}`; natural key is `dataPath`. Creation and an exact `replace` must carry a non-empty `expression`. |
| `variant` | `{name, presentation:PresentationSpec, settings?}`; natural key is `name`. `presentation` is required when creating or replacing a variant; an update/upsert that finds an existing variant may omit it. |
| `grouping` | `{name?, use?, id?, groupState?:"Enabled|Disabled|DeletedByUser", groupFields?:{items:[{field?:ValueSpec, use?, groupType?, periodAdditionType?, periodAdditionBegin?:ValueSpec, periodAdditionEnd?:ValueSpec}]}, selection?, filter?, order?, conditionalAppearance?, outputParameters?, items?, ...GroupScaffold}`. `id` and `groupState` are real settable platform members, not invented MCP identifiers. Groups recurse through `items`; all group addresses use the returned index and hash. Renaming writes the group's `name` property without changing the indexed addressing rule. For `periodAdditionBegin`/`periodAdditionEnd`, `{kind:'null'}` means the bound is not set: it clears a bound the field already has and writes no element to Template.dcs. Send `{kind:'null'}` rather than a bare JSON `null`: the server drops null members of `body`, so a bare `null` arrives as an omitted member. An `update` or `upsert` then keeps an existing bound, and a `replace` resets it like any omitted member. |
| `selection` | `{items:[{kind?:"field", field?:ValueSpec, title?:PresentationSpec, use?, viewMode?} | {kind:"group", field?:ValueSpec, title?:PresentationSpec, use?, placement?, items:[...], viewMode?} | {kind:"auto", use?}], ...HolderScaffold}`. Items are ordered/indexed. |
| `filter` | `{items:[{kind?:"item", left?:ValueSpec, comparisonType?, right?:ValueSpec[], use?, ...ItemScaffold} | {kind:"group", groupType?, use?, items:[...], ...ItemScaffold}], ...HolderScaffold}`. Groups can be nested; items are ordered/indexed. |
| `dataParameter` | `{items:[{parameter?:ValueSpec, value?:ValueSpec, values?:ValueSpec[], use?, viewMode?, userSettingID?, userSettingPresentation?:PresentationSpec}]}`. Items are ordered/indexed. `values` carries every value of an item that holds several; pass it instead of `value`. |
| `order` | `{items:[{kind?:"item", field?:ValueSpec, orderType?, use?, viewMode?} | {kind:"auto", use?}], ...HolderScaffold}`. Items are ordered/indexed. |
| `conditionalAppearance` | `{items:[{use?, selection?:{items:[{field?:ValueSpec, use?}]}, filter?, appearance?, presentation?:PresentationSpec, useInGroup?, useInHierarchicalGroup?, useInOverall?, useInFieldsHeader?, useInHeader?, useInParameters?, useInFilter?, useInResourceFieldsHeader?, useInOverallHeader?, useInOverallResourceFieldsHeader?, ...ItemScaffold}], ...HolderScaffold}`. Items are ordered/indexed. Schema/settings targets validate `appearance` keys against the schema catalogue; a form root uses EDT's `FormAppearanceParameters` catalogue and a dynamic list uses `DynamicListAppearanceParameters`. Unknown keys are refused and valid keys are listed. A form appearance field reference is accepted but is not validated against the form's data in this release. A color accepts `{color:{red,green,blue}}`, `{color:'auto'}`, `{color:{style:'<name>'}}` (a configuration style item, else the platform style color of that name), `{color:{palette:'<name>'}}` (a configuration palette color, else the platform palette color of that name), `{color:{web:'<name>'}}` or `{color:{windows:'<name>'}}`; a named color must resolve in the project configuration or in the platform color catalogue of the project's version. |
| `table` | `{kind?:"table", name?, use?, id?, rows?, columns?, selection?, conditionalAppearance?, outputParameters?, rowsViewMode?, rowsUserSettingID?, rowsUserSettingPresentation?, columnsViewMode?, columnsUserSettingID?, columnsUserSettingPresentation?, ...HolderScaffold}`. `id` is the table's real settable platform member. `rows` and `columns` hold group items and recurse like `grouping`. Tables are structure items, so they share the `items` tree and its indexed addressing. An exact `replace` of a structure item builds it from the `kind` in the body, so a grouping, a table and a chart can be exchanged at the same index. |
| `chart` | `{kind?:"chart", name?, use?, id?, points?, series?, selection?, conditionalAppearance?, outputParameters?, pointsViewMode?, pointsUserSettingID?, pointsUserSettingPresentation?, seriesViewMode?, seriesUserSettingID?, seriesUserSettingPresentation?, ...HolderScaffold}`. `points` (the categories) and `series` hold group items and recurse like `grouping`; `selection` holds the measures. Addressing, `id` and the exact `replace` work as for `table`; a body appended without `kind:"chart"` is a grouping. Every chart of the schema is checked against the state a write leaves it in, so the same `type='schema'` call may declare the resource a chart measures: a point or series must group by a data-set field, calculated field or user field, or by an attribute of a data-set or calculated field (`<field>.<attribute>`), that its use restriction allows; a measure must be a resource (`totalFields`), a user field with a total expression or aggregate, a `totalFields` resource's percent field such as `Amount.OverallPercent`, or `{kind:"auto"}`; a chart needs at least one measure. Paths compare case-insensitively; the user-field folder and percent terms are accepted in English or Russian, and while an auto-fill query data set exists any unlisted field is accepted as a point or series. A write that leaves more broken references of some role and field in a settings tree (the default settings or one variant) than that tree had is refused with the choices and nothing is written, including a schema edit that breaks an unchanged chart; a problem that was already there in the same tree does not block an unrelated edit, and a switched-off chart, point, series or measure (`use:false` or a non-Enabled `groupState`, also through its structure group, axis group or folder) is not judged, and a switched-off user field is not available; a lossless `xml` replacement is copied as it is and not judged. `outputParameters` takes the platform chart catalogue (`ChartType`, `Title`, ...; `action='options'`, `type='chart'` lists it). Dynamic-list `listSettings` refuse a new or changed chart and offer no chart options. |
| `userField` | Expression field: `{kind:"expression", dataPath, use?, title?:PresentationSpec, detailExpression?, detailExpressionPresentation?, totalExpression?, totalExpressionPresentation?}`. Case field: `{kind:"case", dataPath, use?, title?:PresentationSpec, variants?}`. Items are ordered/indexed. |
| `outputParameter` | `{items:[{parameter?:ValueSpec, value?:ValueSpec, use?, viewMode?, userSettingID?, userSettingPresentation?:PresentationSpec}]}`. Items are ordered/indexed. An output parameter takes one typed `value`; only data parameters accept a `values` array. The current platform output catalogues declare no value-list parameters. Platform-enum values accept either a bare literal string or `{kind:'string',value:'<literal>'}`; other shapes are refused with the allowed literals. A localized-text value such as `Title` takes a string, `{kind:"string",value:"text"}`, or a `{"<languageCode>": "text"}` map, and `{}` writes the empty localized text EDT stores for a cleared title; the same holds for the `Format` and `Text` appearance keys. The `ChartType.ScaleColor` output parameter refuses `{color:'auto'}` because EDT cannot reload it; pass an explicit RGB color or omit the parameter. |
| `userSettings` | Whole settings body: `{items?, selection?, filter?, dataParameters?, order?, conditionalAppearance?:{items:[], ...HolderScaffold}, outputParameters?, additionalProperties?:{"Name":ValueSpec, ...}, itemsViewMode?, itemsUserSettingID?, itemsUserSettingPresentation?:PresentationSpec}`. `additionalProperties` is the platform `Structure`; `upsert`/`update` merge named entries and `replace` is authoritative. Value kinds outside the documented `ValueSpec` set are refused rather than discarded. Never use settings scaffold fields to store invented MCP IDs. |

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

LocalString output values accept a bare string, a string ValueSpec, or the same
language map as `title` (codes must be declared); `{}` clears localized text:

```json
{"projectName":"MyProject","fqn":"Report.Sales#/defaultSettings/outputParameters","action":"upsert","type":"outputParameter","body":{"items":[{"parameter":{"kind":"parameter","value":"Title"},"value":{"kind":"string","value":"Sales"}}]}}
```

The same `value` may be `"Sales"` or `{"en":"Sales","ru":"Продажи"}`.
Both field and conditional `appearance` use one parser: bare values and `{kind,value}`
are accepted, including Boolean, Number, string/LocalString, enum, color and font:

```json
{"projectName":"MyProject","fqn":"Report.Sales#/defaultSettings/conditionalAppearance","action":"upsert","type":"conditionalAppearance","body":{"items":[{"appearance":{"Text":"Highlighted","MarkNegatives":{"kind":"boolean","value":true},"TextColor":{"kind":"color","value":{"red":255,"green":0,"blue":0}}}}]}}
```

`options type=field` derives dictionaries from the EDT model. Restriction keys are
Boolean flags, not enums; enum dictionaries include `role.periodType`:
Other role enums that require the XML channel are marked `XML only`.

```json
{"projectName":"MyProject","fqn":"Report.Sales#/dataSets/Sales/fields","action":"upsert","type":"field","body":{"dataPath":"Period","role":{"periodType":"Main","periodNumber":1,"dimension":true},"useRestriction":{"field":false,"condition":false,"group":false,"order":true}}}
```

Create a variant with nested structure, selection, an AND/OR filter, and order:

```json
{"projectName":"MyProject","fqn":"Report.Sales","action":"upsert","type":"variant","body":{"name":"ManagerView","presentation":{"en":"Manager view"},"settings":{"items":[{"name":"CustomerGroup","groupFields":{"items":[{"field":{"kind":"field","value":"Customer"},"groupType":"Items","use":true}]},"items":[{"name":"PeriodGroup","groupFields":{"items":[{"field":{"kind":"field","value":"Period"},"groupType":"Items"}]}}]}],"selection":{"viewMode":"Normal","userSettingID":"selection","items":[{"field":{"kind":"field","value":"Customer"},"use":true}]},"filter":{"viewMode":"Normal","userSettingID":"filter","items":[{"kind":"group","groupType":"AndGroup","items":[{"left":{"kind":"field","value":"Quantity"},"comparisonType":"Greater","right":[{"kind":"number","value":0}]},{"kind":"group","groupType":"OrGroup","items":[{"left":{"kind":"field","value":"Status"},"comparisonType":"Equal","right":[{"kind":"string","value":"Open"}]}]}]}]},"order":{"viewMode":"Normal","userSettingID":"order","items":[{"field":{"kind":"field","value":"Customer"},"orderType":"Asc"}]}}}}
```

To change the nested OR condition, first run `get`, then use its root hash and exact
index address:

```json
{"projectName":"MyProject","fqn":"Report.Sales#/variants/ManagerView/settings/filter/items/0/items/1/items/0","action":"update","type":"filter","expectedHash":"<20-character hash from get>","body":{"kind":"item","right":[{"kind":"string","value":"Closed"}]}}
```

Configure a dynamic list and reproduce empty settings holders in the same call:

```json
{"projectName":"MyProject","fqn":"Catalog.Products.Form.ListForm.Attribute.List","action":"upsert","type":"dynamicList","body":{"queryText":"SELECT Ref, Description FROM Catalog.Products","customQuery":true,"dynamicDataRead":true,"autoSaveUserSettings":true,"fields":[{"dataPath":"Ref"},{"dataPath":"Description"}],"listSettings":{"selection":{"items":[],"viewMode":"Normal","userSettingID":"selection"},"filter":{"items":[],"viewMode":"Normal","userSettingID":"filter"},"order":{"items":[],"viewMode":"Normal","userSettingID":"order"},"conditionalAppearance":{"items":[],"viewMode":"Normal","userSettingID":"appearance"}}}}
```

Converting a plain form attribute is destructive. Windowed consent, dynamic-list
type availability check, main-table resolution, and orphaned-column refusal all run before
the conversion. Request conversion with `action='upsert'`; `update` requires ext-info that
already exists and never converts a plain attribute. Query properties remain available through
`modify_metadata` as well.

Dynamic-list ext-info and schema-style items are stored with the content form and exported
to `Form.form`. `listSettings` is a separate external top object; EDT serializes and the
tool separately force-exports it as
`<Form>/Attributes/<AttributeName>/ExtInfo/ListSettings.dcss`.

### Hash and the get-edit-verify loop

`get` returns a structural `hash`, valid within this session, not across EDT versions.
Copy its canonical address and root hash; pass `expectedHash` for indexed edits and
always for `replace`/`remove`. The write transaction rechecks it. On mismatch, get fresh
addresses and rebuild the edit; never guess an index. Read back the result and new hash.

### Bilingual rules

- FQN type tokens accept English/Russian; all object/member names are programmatic
  `Name` values, never synonyms.
- `language` and presentation-map keys are declared language codes, not language names.
  Matching ignores case; output uses declared spelling. Unknown codes are refused with
  the declared list. Omitted `language` uses the default code. Parameter names always
  follow the configuration default, independently of presentation language.
- Queries and expressions remain verbatim in either 1C dialect; no translation or
  synonym resolution occurs.
- `localeUnusedInConfiguration: true (<codes>)` means the code is declared but absent
  from the configuration's own synonym. Text displays, but the configuration may not
  support that language. **Ask the user before translating further**.

## Vendor support
A vendor support lock on the owner refuses schema, dynamic-list query, and conditional
appearance writes without mutation; reads remain available. An unavailable support check
also refuses writes. The error names the owner. Use `adopt_metadata_object` in an extension,
or have the user allow changes in EDT support settings; the server never changes them.
Support applies only to native configuration roots, never extensions or external projects.


## Query validation before writes

Submitted query datasets (including nested unions and XML schema replacements) use
`validate_query`'s shared native EDT validator with `dcsMode=true`. Dynamic-list
`queryText` writes use the same service with `dcsMode=false`, including the
`modify_metadata` form-attribute path. `INVALID` refuses the whole call before BM
mutation. The error includes `queryValidation` details and the submitted query slot.

`PARTIAL` permits forward authoring and explicitly reports query validation incomplete,
`validationComplete=false`, and unresolved sources. It does not mean the query has
been validated. A missing QL service or a failed validation run refuses the write;
retry after project loading or QL support is restored. Queries omitted by a partial
update are left untouched and are not revalidated.

---
*Generated from the live MCP server (`get_tool_guide`) by `docs/generate_tool_docs.py`. Do not edit this file. Edit the tool's description/schema in its Java source and its guide body in `mcp/bundles/com.ditrix.edt.mcp.server/guides/<tool>.md`.*
