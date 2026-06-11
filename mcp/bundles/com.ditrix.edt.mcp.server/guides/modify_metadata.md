Sets one or more properties of a metadata node addressed by a 1C full-name FQN (a top object or a member: attribute / tabular section / dimension / resource / enum value), then force-exports the owning top object to its `.mdo`. Replaces the former set_metadata_property (which set only Comment / Synonym); this tool sets any assignable scalar / boolean / integer / enum / synonym property.

## Validation (errors are help)
- A property that is NOT assignable on this node is rejected with the list of assignable properties - discover them with get_metadata_details(assignable:true).
- An ENUM value that is not one of the allowed literals is rejected WITH the allowed values; a non-boolean for a boolean property, or a non-integer for an integer property, is rejected too. Nothing is written unless EVERY property validates.

## Parameter details
- `projectName` (required) - EDT project name.
- `fqn` (required) - full-name FQN of the node.
- `properties` (required) - array of `{name, value, language?}`. `name` is the property name; `value` the new value; `language` the CODE for a synonym (default: config default).
- `normalizeYo` (optional, default true) - normalize the Russian letter `ё`->`е` / `Ё`->`Е` in localized-string values (synonym / title) and in the `comment` property (matches the 1C standard `mdo-ru-name-unallowed-letter`). Other free-text strings can be identifier-like (e.g. `XDTOPackage.namespace` is a URI) and always keep the supplied value. Set `false` to keep `ё` exactly as supplied everywhere. The result lists the rewritten properties under `normalized`.

## Not supported here
- `name` (rename): refused - use rename_metadata_object, which cascades the rename across BSL code, forms and metadata.

## Setting the data type
The `type` property takes a STRUCTURED value `{types:[{kind, ...}]}`. Primitive kinds String / Number / Boolean / Date carry inline qualifiers (length; precision / scale / nonNegative; fractions = DateTime | Date | Time). A reference is `{kind:'Ref', ref:'Type.Name'}` (or `{kind:'CatalogRef', ref:'Name'}`). The list may mix several (a composite type).

## Setting an object reference
A reference property to another metadata object is set by FQN: a SINGLE reference (e.g. `chartOfAccounts` on an AccountingRegister) takes `value:'Type.Name'`; a LIST reference (e.g. a Subsystem's `content`) takes `value:['Type.Name', ...]` and REPLACES the whole list (an empty array `[]` clears it). The target must be a top-level object whose type matches; get_metadata_details(assignable:true) shows the allowed target type. Structured content with per-item flags (e.g. a common attribute's content), and references whose target is a member (e.g. a default form), are not set here yet.

## Setting a StyleItem value (Color / Font)
A StyleItem (created generically with create_metadata) has no value yet; set its `value` property to a STRUCTURED object with EITHER a `color` OR a `font` member (not both). The style item's `type` (Color / Font) is set automatically to match the value.
- Color (explicit RGB): `{name:'value', value:{color:{red:255, green:0, blue:0}}}` - each component 0-255.
- Color (automatic): `{name:'value', value:{color:'auto'}}` - the platform automatic color.
- Font: `{name:'value', value:{font:{faceName:'Arial', height:12, bold:true, italic:false, underline:false, strikeout:false}}}` - at least one of faceName / height / bold / italic / underline / strikeout is required; height is a positive integer.
get_metadata_details renders the assigned value under a `Value` section (Style Type + Color `RGB(r, g, b)` / `Auto`, or the Font face / height / flags).

## Form members
A FORM member is addressed like its create FQN: `Catalog.X.Form.F.<Kind>.Name` (or `CommonForm.F.<Kind>.Name`), Kind = Attribute / Command / Field / Button / Group / Decoration / Table. The same assignable properties apply: an item's `title` (bilingual; defaults to the config language when `language` is omitted), `visible`, `readOnly` (fields / groups / tables only) and any other assignable scalar / boolean / enum the item carries. NB `type` is context-dependent: on a form ATTRIBUTE it aliases the data `valueType` (same `{types:[...]}` shape as an mdclass attribute); on a form FIELD / Button / Decoration it is the display-kind ENUM (InputField / LabelField / ...). A wrong property name is rejected WITH the member's assignable list. The form item `id` cannot be set (auto-allocated). The change persists to the form's `Form.form` on disk.

## Rebinding a form event handler / a button's command
Two form links are not ordinary assignable properties and have their own rebind paths (both force-export the `Form.form`):
- REBIND a handler's procedure: address the EXISTING handler by its FQN `Catalog.X.Form.F.Handler.<Event>` (form-level), `Catalog.X.Form.F.<ItemKind>.<ItemName>.Handler.<Event>` (item-level) or `Catalog.X.Form.F.Command.<Name>.Handler.Action` (a command's Action) and pass a `procedure` property with the new BSL procedure name. This only re-points an existing handler; to BIND a new event use create_metadata, to remove it delete_metadata. A handler FQN accepts no other property.
- RE-POINT a button at a different (existing) form command: address the Button by its FQN `Catalog.X.Form.F.Button.<Name>` and pass a `command` property naming an existing form command (the button's `commandName` targets a FormCommand, a form-model object, so it is rebound here rather than via a generic reference). The command must already exist; a `command` change cannot be combined with other property changes in one call.

## Moving / reordering a form item
A FORM ITEM (a field / group / decoration / button / table - anything in the form's items tree) is RE-PARENTED and/or REORDERED with two move properties on `properties`:
- `parent` - the destination: an existing container name (a group, a table) to nest the item inside, the special `AutoCommandBar` token for the form's command bar (`MyTable.AutoCommandBar` for a table's own bar), or the FORM name (or an empty string) to move it to the form ROOT. Omit `parent` to keep the current parent (a pure reorder).
- `position` - the destination order among the children: `first`, `last`, `before:<siblingName>`, `after:<siblingName>`, or a 0-based integer INDEX. The integer index is the desired FINAL position "as you see it" (reordering within the same container is not off-by-one). Omit `position` to append to the end of the destination. Out-of-range handling is deliberately asymmetric: an integer index past the last position is CLAMPED to the end (tolerant, like list APIs), while `before:`/`after:` naming an unknown sibling is an ERROR (an explicit sibling reference asserts the form's current structure, so a stale name must surface instead of being silently re-interpreted).
A move is structural, so it CANNOT be combined with ordinary property changes in the same call (move first, then modify). `parent`/`position` apply to an ITEM only - a form Attribute / Command is not positioned. The same placement rules as create apply (e.g. no decorations in command bars); a button's CommandBarButton/UsualButton type re-derives when it crosses a bar boundary; the designer auto-children (tooltips / context menus / command bars) are not movable. A group cannot be moved into ITSELF or one of its own descendants (a cycle); an ambiguous / missing item or an unknown parent is a clean error. The move force-exports the form's `Form.form` to disk; the result carries a `destination` describing where the item ended up.

## Examples
- Move a field into a group: `{projectName:'P', fqn:'Catalog.Products.Form.ItemForm.Field.Price', properties:[{name:'parent', value:'PriceGroup'}]}`
- Move a button into the command bar: `{projectName:'P', fqn:'Catalog.Products.Form.ItemForm.Button.Print', properties:[{name:'parent', value:'AutoCommandBar'}]}`
- Reorder a field to the top of its container: `{projectName:'P', fqn:'Catalog.Products.Form.ItemForm.Field.Price', properties:[{name:'position', value:'first'}]}`
- Move a field back to the form root, after another item: `{projectName:'P', fqn:'Catalog.Products.Form.ItemForm.Field.Price', properties:[{name:'parent', value:'ItemForm'}, {name:'position', value:'after:Description'}]}`
- Set a comment: `{projectName:'P', fqn:'Catalog.Products', properties:[{name:'comment', value:'Goods'}]}`
- Set a synonym: `{projectName:'P', fqn:'Catalog.Products', properties:[{name:'synonym', value:'Goods', language:'en'}]}`
- Set an enum on an attribute: `{projectName:'P', fqn:'Catalog.Products.Attribute.Weight', properties:[{name:'indexing', value:'Index'}]}`
- Set a type: `{projectName:'P', fqn:'Catalog.Products.Attribute.Weight', properties:[{name:'type', value:{types:[{kind:'Number', precision:10, scale:2}]}}]}`
- Set a list reference: `{projectName:'P', fqn:'Subsystem.Sales', properties:[{name:'content', value:['Catalog.Products', 'Document.Order']}]}`
- Hide a form item: `{projectName:'P', fqn:'Catalog.Products.Form.ItemForm.Field.Price', properties:[{name:'visible', value:false}]}`
- Set a form attribute's type: `{projectName:'P', fqn:'Catalog.Products.Form.ItemForm.Attribute.Total', properties:[{name:'type', value:{types:[{kind:'Number', precision:10, scale:2}]}}]}`
- Rebind an item-level handler's procedure: `{projectName:'P', fqn:'Catalog.Products.Form.ItemForm.Field.Price.Handler.OnChange', properties:[{name:'procedure', value:'PriceOnChange'}]}`
- Re-point a button at another command: `{projectName:'P', fqn:'Catalog.Products.Form.ItemForm.Button.Go', properties:[{name:'command', value:'Refresh'}]}`
- Set a style item to a red color: `{projectName:'P', fqn:'StyleItem.MyColor', properties:[{name:'value', value:{color:{red:255, green:0, blue:0}}}]}`
- Set a style item to the automatic color: `{projectName:'P', fqn:'StyleItem.MyColor', properties:[{name:'value', value:{color:'auto'}}]}`
- Set a style item to a font: `{projectName:'P', fqn:'StyleItem.MyFont', properties:[{name:'value', value:{font:{faceName:'Arial', height:12, bold:true}}}]}`

## Result
JSON with `action='modified'`, the normalized `fqn`, the `applied` property names, `persisted`, and (when the ё->е normalization rewrote anything) the list of `normalized` properties. A move additionally returns `destination` (where the moved item ended up, e.g. `group 'Main' at index 1`).

## Reverting (no undo)
There is no automatic undo: to revert a change, call modify_metadata again with the previous value (read the current value first with get_metadata_details). modify_metadata is intentionally NOT confirm-gated because it is reversible that way; only the destructive / high-blast-radius writes (delete_metadata, rename_metadata_object, update_database, delete_project) are gated with a confirm-preview.
