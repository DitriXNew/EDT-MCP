# dcs

Read and author 1C DCS schemas, their settings variants, and form dynamic lists. Call action='get' first, pass its hash as expectedHash for index-addressed mutations, and call get_tool_guide('dcs') for body shapes.

## Parameters

| Parameter | Required | Type | Description |
| --- | --- | --- | --- |
| projectName | yes | string | EDT project name. |
| fqn | yes | string | A supported DCS root FQN, optionally followed by an RFC-6901 `#/...` pointer. |
| action | yes | enum | `get`, `upsert`, `update`, `replace`, or `remove`. Schema writes implement `upsert` and `update`. |
| type | yes | enum | `schema`, `dynamicList`, `dataSource`, `dataSet`, `field`, `parameter`, `calculatedField`, `totalField`, `variant`, `grouping`, `selection`, `filter`, `dataParameter`, `order`, `conditionalAppearance`, `table`, `userField`, `outputParameter`, or `userSettings`. |
| body | no | object | Mutation body. It is forbidden for `get` and `remove`; body shapes are in `get_tool_guide('dcs')`. |
| expectedHash | no | string | Structural hash returned by `get`; checked inside every write transaction when supplied and required for index-addressed mutations. |
| language | no | string | Declared configuration language code for localized presentations. |
| limit | no | integer | Collection page size, default 100 and clamped to 1..1000. |
| offset | no | integer | Zero-based collection offset. |

## Guide

Start with `action="get"` and the root's matching type. Supported roots are a Report's
main DCS (`Report.Sales`), a common DCS template (`CommonTemplate.Analytics`), an
object-owned DCS template (`Report.Sales.Template.CustomDcs`), and a form attribute's
dynamic list (`Catalog.Products.Form.ListForm.Attribute.List`). A bare `schema` or
`dynamicList` root returns a compact summary: hash, section counts and copyable section
addresses, plus one-line item tables. It deliberately omits full queries and recursive
settings expansion.

Append an RFC-6901 fragment to read one node in full:

```text
Report.Sales#/dataSets/Sales
Report.Sales#/dataSets/Sales/fields/Customer
Report.Sales#/parameters/Period
Report.Sales#/defaultSettings/filter/items/1
Report.Sales#/variants/ManagerView/settings/items/0
Catalog.Products.Form.ListForm.Attribute.List#/listSettings/order/items/0
```

Pointer segments use `/` separators; encode literal `~` as `~0` and literal `/` as
`~1`. Named collections use `name`, while fields and calculated/total fields use
`dataPath`. Ordered settings nodes use zero-based indices. Copy the canonical addresses
from a response: an invalid segment reports the keys or indices that exist at that
level. Query data-set drill-down includes its complete query in a fenced block and its
complete fields table. Settings drill-down renders the whole subtree as a nested,
address-aware outline. Charts remain visible as read-only one-line entries; chart
authoring is unsupported and there is no `chart` type.

At a bare root, a collection type pages that collection. The response says `showing N
of M`; if truncated, pass its `Next offset` in the next call. `field` includes fields
from all schema data sets with their full parent addresses. Settings collection types
refer to `defaultSettings` for schemas and `listSettings` for dynamic lists unless the
FQN explicitly points inside a variant.

## Action semantics

| Action | Meaning | Body | Hash | Current status |
| --- | --- | --- | --- | --- |
| `get` | Read a summary, collection page, or full node | absent | absent; returns current hash | implemented |
| `upsert` | Create by natural key, append to an ordered collection, or partially update an exact target | required | required for index-addressed targets | schema, settings, dynamic lists |
| `update` | Update an existing node only | required | required for index-addressed targets | schema, settings, dynamic lists |
| `replace` | Authoritative replacement; omitted values reset and omitted collections clear | required | always required | schema and settings, a dynamic list below `#/listSettings` |
| `remove` | Remove one fragment-addressed node | absent | always required | schema and settings, a dynamic list below `#/listSettings` |

The complete per-type mutation body shapes are returned by `get_tool_guide('dcs')`.
The schema layer authors data sources, query data sets and their fields, parameters,
calculated fields, and total fields. The shared settings layer authors variants, recursive
groupings, selection, filter groups/items, order, data parameters, output parameters, and
holder scaffolding for both schemas and dynamic lists. Dynamic lists also author ext-info
scalars and reuse the schema item writers for fields, calculated fields, and parameters.
Conditional-appearance rules are authorable too: their `appearance` keys are validated
against the platform parameter list for the project version, and an unknown key is refused
with the valid keys listed. An empty holder may carry the same view-mode and user-setting
scaffolding.

`replace` and `remove` are implemented on the settings layer, which on a dynamic list means
an address below `#/listSettings`; the list's own scalars, fields, calculated fields and
parameters take `upsert` or `update`. Both refuse rather than cascade. `replace` refuses a
target that still holds content this writer cannot reproduce - a chart, a nested schema, an
area template - naming the node instead of dropping it on write-back. `remove` and
identity-changing updates refuse while other nodes still refer to the target, listing the
exact referring addresses.

## Get-edit-verify protocol

Every successful read returns a short structural hash. It is a within-session stale
tree guard, not a cross-version identifier. Read first and copy the canonical address
and hash. Index-addressed mutations must pass that value as `expectedHash`;
`replace` and `remove` always require it. If it is stale, read again rather than guessing
a changed index. Read once more after an edit to verify the node and new hash.

## Bilingual rules

Only the metadata type token may use an English or Russian spelling. Every object,
template, form, attribute, data-set, and field segment is its programmatic `Name`, not a
synonym. `language` and presentation-map keys are language codes declared by the
project. Query text and expressions are returned exactly as authored and are not
translated or normalized.

---
*Generated-facing reference. The source of truth is the Java schema and `get_tool_guide('dcs')`.*
