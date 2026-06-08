# modify_metadata

Set properties of a metadata node (object or member, including a FORM member - item / attribute / command) addressed by a 1C full-name FQN, as properties=[{name, value, language?}]. Each property is validated (it must be assignable, and an enum value must be one of the allowed literals) with an actionable error. Discover assignable properties + allowed values with get_metadata_details(assignable:true). To rename, use rename_metadata_object. Full parameters and examples: call get_tool_guide('modify_metadata').

## Parameters
| Parameter | Required | Type | Description |
| --- | --- | --- | --- |
| projectName | yes | string | EDT project name (required). |
| fqn | yes | string | Full-name FQN of the node to modify (required), e.g. 'Catalog.Products' or 'Catalog.Products.Attribute.Weight' (type / kind tokens may be English or Russian; the Name parts are the programmatic Name). |
| properties | yes | array | Properties to set, as [{name, value, language?}] (required, at least one). 'name' is the property name (e.g. 'comment', 'synonym', 'indexing'); 'value' is the new value; 'language' is the code for a synonym (default: config default). |

## Guide
Sets one or more properties of a metadata node addressed by a 1C full-name FQN (a top object or a member: attribute / tabular section / dimension / resource / enum value), then force-exports the owning top object to its `.mdo`. Replaces the former set_metadata_property (which set only Comment / Synonym); this tool sets any assignable scalar / boolean / integer / enum / synonym property.

## Validation (errors are help)
- A property that is NOT assignable on this node is rejected with the list of assignable properties - discover them with get_metadata_details(assignable:true).
- An ENUM value that is not one of the allowed literals is rejected WITH the allowed values; a non-boolean for a boolean property, or a non-integer for an integer property, is rejected too. Nothing is written unless EVERY property validates.

## Parameter details
- `projectName` (required) - EDT project name.
- `fqn` (required) - full-name FQN of the node.
- `properties` (required) - array of `{name, value, language?}`. `name` is the property name; `value` the new value; `language` the CODE for a synonym (default: config default).

## Not supported here
- `name` (rename): refused - use rename_metadata_object, which cascades the rename across BSL code, forms and metadata.

## Setting the data type
The `type` property takes a STRUCTURED value `{types:[{kind, ...}]}`. Primitive kinds String / Number / Boolean / Date carry inline qualifiers (length; precision / scale / nonNegative; fractions = DateTime | Date | Time). A reference is `{kind:'Ref', ref:'Type.Name'}` (or `{kind:'CatalogRef', ref:'Name'}`). The list may mix several (a composite type).

## Setting an object reference
A reference property to another metadata object is set by FQN: a SINGLE reference (e.g. `chartOfAccounts` on an AccountingRegister) takes `value:'Type.Name'`; a LIST reference (e.g. a Subsystem's `content`) takes `value:['Type.Name', ...]` and REPLACES the whole list (an empty array `[]` clears it). The target must be a top-level object whose type matches; get_metadata_details(assignable:true) shows the allowed target type. Structured content with per-item flags (e.g. a common attribute's content), and references whose target is a member (e.g. a default form), are not set here yet.

## Form members
A FORM member is addressed like its create FQN: `Catalog.X.Form.F.<Kind>.Name` (or `CommonForm.F.<Kind>.Name`), Kind = Attribute / Command / Field / Button / Group / Decoration / Table. The same assignable properties apply: an item's `title` (bilingual; defaults to the config language when `language` is omitted), `visible`, `readOnly` (fields / groups / tables only) and any other assignable scalar / boolean / enum the item carries. NB `type` is context-dependent: on a form ATTRIBUTE it aliases the data `valueType` (same `{types:[...]}` shape as an mdclass attribute); on a form FIELD / Button / Decoration it is the display-kind ENUM (InputField / LabelField / ...). A wrong property name is rejected WITH the member's assignable list. The form item `id` cannot be set (auto-allocated); modifying an event handler is not supported (create/delete it). The change persists to the form's `Form.form` on disk.

## Examples
- Set a comment: `{projectName:'P', fqn:'Catalog.Products', properties:[{name:'comment', value:'Goods'}]}`
- Set a synonym: `{projectName:'P', fqn:'Catalog.Products', properties:[{name:'synonym', value:'Goods', language:'en'}]}`
- Set an enum on an attribute: `{projectName:'P', fqn:'Catalog.Products.Attribute.Weight', properties:[{name:'indexing', value:'Index'}]}`
- Set a type: `{projectName:'P', fqn:'Catalog.Products.Attribute.Weight', properties:[{name:'type', value:{types:[{kind:'Number', precision:10, scale:2}]}}]}`
- Set a list reference: `{projectName:'P', fqn:'Subsystem.Sales', properties:[{name:'content', value:['Catalog.Products', 'Document.Order']}]}`
- Hide a form item: `{projectName:'P', fqn:'Catalog.Products.Form.ItemForm.Field.Price', properties:[{name:'visible', value:false}]}`
- Set a form attribute's type: `{projectName:'P', fqn:'Catalog.Products.Form.ItemForm.Attribute.Total', properties:[{name:'type', value:{types:[{kind:'Number', precision:10, scale:2}]}}]}`

## Result
JSON with `action='modified'`, the normalized `fqn`, the `applied` property names, and `persisted`.

## Reverting (no undo)
There is no automatic undo: to revert a change, call modify_metadata again with the previous value (read the current value first with get_metadata_details). modify_metadata is intentionally NOT confirm-gated because it is reversible that way; only the destructive / high-blast-radius writes (delete_metadata, rename_metadata_object, update_database, delete_project) are gated with a confirm-preview.

---
*Generated from the live MCP server (`get_tool_guide`) by `docs/generate_tool_docs.py`. Do not edit this file. Edit the tool's description/schema in its Java source and its guide body in `mcp/bundles/com.ditrix.edt.mcp.server/guides/<tool>.md`.*
