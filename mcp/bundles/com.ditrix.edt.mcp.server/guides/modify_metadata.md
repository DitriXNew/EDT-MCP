Sets one or more properties of a metadata node addressed by a 1C full-name FQN (a top object or a member: attribute / tabular section / dimension / resource / enum value), then force-exports the owning top object to its `.mdo`. Replaces the former set_metadata_property (which set only Comment / Synonym); this tool sets any assignable scalar / boolean / integer / enum / synonym property. A ROLE FQN (`Role.<Name>`) is modified through a dedicated access-rights surface (`rights` / `templates` / `roleProperties`) instead of `properties` - see [Setting role access rights](#setting-role-access-rights). A structured membership LIST is edited through a dedicated `content` surface instead of `properties`, dispatched by the FQN's kind - a COMMON ATTRIBUTE's owners (`CommonAttribute.<Name>`), an EXCHANGE PLAN's content objects (`ExchangePlan.<Name>`), a CATALOG's owners (`Catalog.<Name>`) or a DOCUMENT's register records (`Document.<Name>`) - see [Editing a membership list (content)](#editing-a-membership-list-content).

## Validation (errors are help)
- A property that is NOT assignable on this node is rejected with the list of assignable properties - discover them with get_metadata_details(assignable:true).
- An ENUM value that is not one of the allowed literals is rejected WITH the allowed values; a non-boolean for a boolean property, or a non-integer for an integer property, is rejected too. Nothing is written unless EVERY property validates.

## Parameter details
- `projectName` (required) - EDT project name.
- `fqn` (required) - full-name FQN of the node.
- `properties` (required, EXCEPT for a Role FQN with a role payload or a membership FQN with a content payload) - array of `{name, value, language?}`. `name` is the property name; `value` the new value; `language` the CODE for a synonym (default: config default). It is optional (and cannot be combined with) the role `rights` / `templates` / `roleProperties` payload (see [Setting role access rights](#setting-role-access-rights)) or the membership `content` payload (see [Editing a membership list (content)](#editing-a-membership-list-content)).
- `content` (CommonAttribute / ExchangePlan / Catalog / Document FQN) - array of `{op?, metadata, use?, autoRecord?}` to attach / detach members in the resolved object's structured list (owners / content objects / register records); cannot be combined with `properties`. See [Editing a membership list (content)](#editing-a-membership-list-content).
- `normalizeYo` (optional, default true) - normalize the Russian letter `ё`->`е` / `Ё`->`Е` in localized-string values (synonym / title) and in the `comment` property (matches the 1C standard `mdo-ru-name-unallowed-letter`). Other free-text strings can be identifier-like (e.g. `XDTOPackage.namespace` is a URI) and always keep the supplied value. Set `false` to keep `ё` exactly as supplied everywhere. The result lists the rewritten properties under `normalized`.

## Not supported here
- `name` (rename): refused - use rename_metadata_object, which cascades the rename across BSL code, forms and metadata.

## Setting the data type
The `type` property takes a STRUCTURED value `{types:[{kind, ...}]}`. Primitive kinds String / Number / Boolean / Date carry inline qualifiers (length; precision / scale / nonNegative; fractions = DateTime | Date | Time). A reference is `{kind:'Ref', ref:'Type.Name'}` (or `{kind:'CatalogRef', ref:'Name'}`). The list may mix several (a composite type).

## Setting an object reference
A reference property to another metadata object is set by FQN: a SINGLE reference (e.g. `chartOfAccounts` on an AccountingRegister) takes `value:'Type.Name'`; a LIST reference (e.g. a Subsystem's `content`) takes `value:['Type.Name', ...]` and REPLACES the whole list (an empty array `[]` clears it). The target must be a top-level object whose type matches; get_metadata_details(assignable:true) shows the allowed target type. A structured membership list (a common attribute's owners with a per-owner flag, an exchange plan's content objects with an optional autoRecord flag, a catalog's owners, a document's register records) is edited through the sibling `content` payload - see [Editing a membership list (content)](#editing-a-membership-list-content); references whose target is a member (e.g. a default form) are not set here yet.

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

## Setting a dynamic-list custom query
A list / choice form shows its rows through a **dynamic list** form attribute. To give it a CUSTOM query (e.g. a multilingual name from a common attribute, a calculated column, or an in-query filter) set the query on that ATTRIBUTE - addressed `Catalog.X.Form.ListForm.Attribute.<Name>`:
- `queryText` - the 1C query, e.g. `SELECT Ref, Description AS Description FROM Catalog.Products`. Setting it turns the attribute into a dynamic list (if it is not one already) and implies `customQuery=true`.
- `customQuery` - `true` to use the custom `queryText`, `false` to switch the dynamic list back to its automatic main-table query (the `queryText` is kept but ignored while false).
- `mainTable` (optional) - the FQN of the object the list reads from, e.g. `Document.Order` / `Catalog.Products`. It is resolved to the object's main-table view; setting it enables the list's available-table fields and dynamic data reading. The list is valid without it (the query's FROM defines the source).

When the attribute is not yet a dynamic list it is converted: a `DynamicList` value type and a dynamic-list ext-info are created, the form's main attribute is set when it has none, and `autoFillAvailableFields` is turned on so EDT derives the available `<fields>` from the query - you do NOT author a DCS `<fields>` block. Create the bare attribute first (`create_metadata` with `...Form.ListForm.Attribute.List`), then set its query here. **Output a column** with `create_metadata` for a form Field bound to `dataPath` `List.<field>` (e.g. `List.Number`), where `<field>` is a query select field; the Field shows that query column in the list table. The query props are structural, so they cannot be combined with other property changes in one call (set the query first, then make other changes). A non-existent attribute or a malformed FQN is a clean error. The change force-exports the form's `Form.form` to disk; verify with get_project_errors (an invalid query is reported by the platform's dynamic-list validation). Property names are bilingual: ru `ТекстЗапроса` / `ПроизвольныйЗапрос` / `ОсновнаяТаблица`.

## Setting role access rights
When the `fqn` is a **Role** (`Role.<Name>`) you set the role's ACCESS RIGHTS through three sibling payload keys instead of `properties` - a role is modified through its rights surface, not the generic property bag. All three keys are optional; give any combination in one call. A role payload CANNOT be combined with a generic `properties` change in the same call (set the role's own comment / synonym separately). Read a role's current rights matrix, RLS restrictions and templates with `get_metadata_details` on the Role FQN. The change goes through the EDT-native rights tasks and force-exports `Role.<Name>` (draining the sibling `Rights.rights` sub-resource).

- `rights` - array of `{object, right, value?, rls?, rlsFields?}` (per-object right VALUES + optional per-object Row-Level-Security):
  - `object` (required) - the guarded metadata FQN, e.g. `Catalog.Products` or the Russian `Справочник.Товары` (only the type token is bilingual; the Name is the programmatic Name).
  - `right` (required) - a bilingual right name, e.g. `Read` / `Чтение`, `Update` / `Изменение`, `Insert` / `Добавление`. An unknown right is rejected WITH the list of valid rights for that object type.
  - `value` (optional, default `set`) - `set` (allowed) / `unset` (denied) / `provided` (default / inherited), or a boolean (`true`=set, `false`=unset).
  - `rls` (optional) - a Row-Level-Security restriction condition (1C query text). Setting it adds (or edits, if one already exists for that object+right) the RLS restriction.
  - `rlsFields` (optional) - an array of field names the RLS applies to; omit or leave empty for a WHOLE-OBJECT restriction. Field names are matched bilingually against the object's RLS field pool.
- `templates` - array of `{op?, name, condition?}` (RLS restriction templates):
  - `op` (optional, default `add`) - `add` / `edit` / `delete`.
  - `name` (required) - the template name.
  - `condition` (required for `add` / `edit`) - the RLS restriction text.
- `roleProperties` - object of optional booleans `{setForNewObjects, setForAttributesByDefault, independentRightsOfChildObjects}` - the three role-wide flags. Only supplied flags are changed.

An unknown right or a bad object FQN is a clean, actionable error (not-found + the valid list / a suggestion). Nothing is written unless the payload resolves.

## Editing a membership list (content)
Several metadata objects carry a structured MEMBERSHIP list - which objects they include or apply to. You edit that list through the sibling `content` payload instead of `properties`, dispatched by the resolved FQN's kind. The list is edited through its own surface, not the generic property bag, so a `content` payload CANNOT be combined with a generic `properties` change in the same call (set the object's own comment / synonym separately). The change goes through a BM write transaction and force-exports the addressed object's `.mdo` to disk once (no manual `clean_project` needed). Read the current list with `get_metadata_details` on the FQN.

The four supported kinds:
- **CommonAttribute** (`CommonAttribute.<Name>`) - a single attribute shared across many objects (an audit `Author` / `EditDate`, a data-separator). Its OWNERS live in the `content` list, each with a per-owner `use` flag (`Use` / `DontUse` / `Auto`, default `Use`).
- **ExchangePlan** (`ExchangePlan.<Name>`) - the plan's CONTENT objects (which objects participate in the exchange), each with an optional `autoRecord` flag (`Allow` / `Deny`; omit to keep the platform default) that governs auto-registration of changes.
- **Catalog** (`Catalog.<Name>`) - the catalog's OWNERS (a subordinate catalog is owned by one or more objects). A plain reference - no per-owner flag.
- **Document** (`Document.<Name>`) - the document's REGISTER RECORDS / движения (the registers the document posts to). A plain reference - no per-record flag.

- `content` - array of `{op?, metadata, use?, autoRecord?}`:
  - `op` (optional, default `add`) - `add` attaches (or updates) a member; `remove` detaches one by its `metadata` FQN.
  - `metadata` (required) - the member object FQN, e.g. `Catalog.Products` or the Russian `Справочник.Товары` (only the type token is bilingual; the Name is the programmatic Name). It must exist and be a valid member kind for the target list; otherwise the entry is rejected with an actionable error (a CommonAttribute / Catalog owner, an ExchangePlan content object, a Document register - a `BasicRegister`: Information / Accumulation / Accounting / Calculation).
  - `use` (CommonAttribute only, optional, `add` only, default `Use`) - the per-owner usage: `Use` / `DontUse` / `Auto`. Ignored for `remove` and for the other kinds.
  - `autoRecord` (ExchangePlan only, optional, `add` only) - `Allow` / `Deny`. Omit to keep the platform default. Ignored for `remove` and for the other kinds.

Adding is IDEMPOTENT: attaching a member already listed does not duplicate it. For a CommonAttribute / ExchangePlan (a wrapper list with a per-entry flag) a re-add UPDATES that member's flag (`use` / `autoRecord`, counted under `updated` rather than `added`); for a Catalog owner / Document register record (a plain reference, no flag) a re-add is a no-op. Removing a member that is not listed is a clean error. Nothing is written unless every entry resolves. The result's `content` counts object is `{added, updated, removed}` for a CommonAttribute / ExchangePlan change and `{added, removed}` for a Catalog owners / Document register records change.

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
- Give a list form a custom dynamic-list query: `{projectName:'P', fqn:'Catalog.Products.Form.ListForm.Attribute.List', properties:[{name:'queryText', value:'SELECT Ref, Description AS Description FROM Catalog.Products'}, {name:'customQuery', value:true}]}`
- Set the dynamic list's main table: `{projectName:'P', fqn:'Catalog.Products.Form.ListForm.Attribute.List', properties:[{name:'mainTable', value:'Catalog.Products'}]}`
- Switch a dynamic list back to its automatic query: `{projectName:'P', fqn:'Catalog.Products.Form.ListForm.Attribute.List', properties:[{name:'customQuery', value:false}]}`
- Rebind an item-level handler's procedure: `{projectName:'P', fqn:'Catalog.Products.Form.ItemForm.Field.Price.Handler.OnChange', properties:[{name:'procedure', value:'PriceOnChange'}]}`
- Re-point a button at another command: `{projectName:'P', fqn:'Catalog.Products.Form.ItemForm.Button.Go', properties:[{name:'command', value:'Refresh'}]}`
- Set a style item to a red color: `{projectName:'P', fqn:'StyleItem.MyColor', properties:[{name:'value', value:{color:{red:255, green:0, blue:0}}}]}`
- Set a style item to the automatic color: `{projectName:'P', fqn:'StyleItem.MyColor', properties:[{name:'value', value:{color:'auto'}}]}`
- Set a style item to a font: `{projectName:'P', fqn:'StyleItem.MyFont', properties:[{name:'value', value:{font:{faceName:'Arial', height:12, bold:true}}}]}`
- Grant a role a right on an object: `{projectName:'P', fqn:'Role.FullAccess', rights:[{object:'Catalog.Products', right:'Read', value:'set'}]}`
- Deny a right (bilingual right name): `{projectName:'P', fqn:'Role.FullAccess', rights:[{object:'Справочник.Товары', right:'Изменение', value:'unset'}]}`
- Add a whole-object RLS restriction: `{projectName:'P', fqn:'Role.Sales', rights:[{object:'Catalog.Products', right:'Read', value:'set', rls:'WHERE Ref.Company = &Company'}]}`
- Add a per-field RLS restriction: `{projectName:'P', fqn:'Role.Sales', rights:[{object:'Catalog.Products', right:'Read', value:'set', rls:'WHERE Ref.Company = &Company', rlsFields:['Price', 'Cost']}]}`
- Add an RLS restriction template: `{projectName:'P', fqn:'Role.Sales', templates:[{op:'add', name:'ByCompany', condition:'WHERE Company = &Company'}]}`
- Set the role-wide flags: `{projectName:'P', fqn:'Role.Sales', roleProperties:{setForNewObjects:true, setForAttributesByDefault:false}}`
- Attach an owner to a common attribute: `{projectName:'P', fqn:'CommonAttribute.Author', content:[{metadata:'Catalog.Products', use:'Use'}]}`
- Attach several owners (bilingual FQN): `{projectName:'P', fqn:'CommonAttribute.Author', content:[{op:'add', metadata:'Document.Order'}, {op:'add', metadata:'Справочник.Товары', use:'Auto'}]}`
- Detach an owner: `{projectName:'P', fqn:'CommonAttribute.Author', content:[{op:'remove', metadata:'Catalog.Products'}]}`
- Add an object to an exchange plan's content with auto-record allowed: `{projectName:'P', fqn:'ExchangePlan.Branches', content:[{metadata:'Catalog.Products', autoRecord:'Allow'}]}`
- Add an exchange plan content object, denying auto-record (bilingual FQN): `{projectName:'P', fqn:'ExchangePlan.Branches', content:[{op:'add', metadata:'Документ.Заказ', autoRecord:'Deny'}]}`
- Detach an exchange plan content object: `{projectName:'P', fqn:'ExchangePlan.Branches', content:[{op:'remove', metadata:'Catalog.Products'}]}`
- Add an owner to a subordinate catalog: `{projectName:'P', fqn:'Catalog.Contacts', content:[{metadata:'Catalog.Partners'}]}`
- Detach a catalog owner: `{projectName:'P', fqn:'Catalog.Contacts', content:[{op:'remove', metadata:'Catalog.Partners'}]}`
- Add a register record / движение to a document: `{projectName:'P', fqn:'Document.Order', content:[{metadata:'AccumulationRegister.Goods'}]}`
- Detach a document register record: `{projectName:'P', fqn:'Document.Order', content:[{op:'remove', metadata:'AccumulationRegister.Goods'}]}`

## Result
JSON with `action='modified'`, the normalized `fqn`, the `applied` property names, `persisted`, and (when the ё->е normalization rewrote anything) the list of `normalized` properties. A move additionally returns `destination` (where the moved item ended up, e.g. `group 'Main' at index 1`). For a ROLE rights change `applied` is instead a counts object `{rights, templates, roleProperties}` (how many of each were applied). For a membership `content` change the result carries a `content` counts object: `{added, updated, removed}` for a CommonAttribute / ExchangePlan change (members attached / had their `use` / `autoRecord` flag updated / detached) and `{added, removed}` for a Catalog owners / Document register records change (a plain reference list has no per-entry flag, so nothing is "updated").

## Reverting (no undo)
There is no automatic undo: to revert a change, call modify_metadata again with the previous value (read the current value first with get_metadata_details). modify_metadata is intentionally NOT confirm-gated because it is reversible that way; only the destructive / high-blast-radius writes (delete_metadata, rename_metadata_object, update_database, delete_project) are gated with a confirm-preview.
