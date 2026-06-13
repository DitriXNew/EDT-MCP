# create_metadata

Create a metadata node addressed by a 1C full-name FQN: a top-level object (Catalog.Products) or a subordinate member (Catalog.Products.Attribute.Weight, InformationRegister.Prices.Dimension.Product, Enum.Colors.EnumValue.Red). The kind is inferred from the FQN; type and kind tokens may be English or Russian. Full parameters and examples: call get_tool_guide('create_metadata').

## Parameters
| Parameter | Required | Type | Description |
| --- | --- | --- | --- |
| projectName | yes | string | EDT project name (required). |
| fqn | yes | string | Full-name FQN of the node to create (required). Top object: 'Type.Name' (e.g. 'Catalog.Products'). Member: 'Type.Name.Kind.Name' (e.g. 'Catalog.Products.Attribute.Weight'). The trailing Name is the new node's programmatic Name; type / kind tokens may be English or Russian. |
| properties | — | array | Optional properties to apply at creation, as [{name, value, language?}]. This version applies 'synonym' (with optional 'language' code) and 'comment'; other property names are rejected (set them via modify_metadata). |
| expectedNotExists | — | boolean | Optional stale-intent guard (default false): assert the node does not yet exist for a sharper precondition error. A real duplicate is always rejected anyway. |
| normalizeYo | — | boolean | Normalize the Russian letter 'ё'->'е' / 'Ё'->'Е' in the new node's NAME (the trailing FQN segment) and in any synonym / comment value (default true). 'ё' in a Name is flagged by the 1C standard mdo-ru-name-unallowed-letter, so normalizing on input stores a compliant name. Set false to keep 'ё' exactly as supplied. |
| setAsDefault | — | boolean | Form OBJECT create only (FQN 'Type.Object.Form.FormName'). When true, registers the new form as the owner's default object form (default: false). Ignored for other create kinds. |
| callType | — | string (one of: Before, After, Instead) | Form event handler ONLY (item-level '...Form.F.<ItemKind>.Item.Handler.<Event>' or form-level '...Form.F.Handler.<Event>'), in a configuration EXTENSION project. Selects EXTENSION event interception: binds a form:EventHandlerExtension with this call type instead of a plain base handler, so the extension reacts Before / After / Instead of the base element's event (works even when the base element has no handler of its own). Omit for a normal base handler. The BSL handler procedure itself is added separately via write_module_source. Rejected on a base configuration; ignored for non-handler creates. |
| commonModuleKind | — | string (one of: Server, ServerCall, ClientManaged, ClientOrdinary, ClientServer, Global) | CommonModule top-object only. Selects a standards-compliant flag combination the common-module-type validator accepts (no warning), instead of a bare module: 'Server', 'ServerCall', 'ClientManaged', 'ClientOrdinary', 'ClientServer', 'Global'. Defaults to 'Server'. Ignored for other types. Combine with 'serverCall' / 'privileged' / 'returnValuesReuse'. These are create-time-only (the flag set cannot be re-derived post-hoc). |
| serverCall | — | boolean | CommonModule top-object only. When true, the server module is callable from the client (server call). Valid only with a server kind and incompatible with 'Global'. Ignored for other types. |
| privileged | — | boolean | CommonModule top-object only. When true, the module runs with full (privileged) access. Valid only with the 'Server' kind (not a server call). Ignored for other types. |
| returnValuesReuse | — | string (one of: DontUse, DuringRequest, DuringSession) | CommonModule top-object only. Reuse of return values: 'DontUse' (default), 'DuringRequest' or 'DuringSession'. 'DuringSession' yields a cached module accepted by the common-module-type validator. Ignored for other types. |
| targetNamespace | — | string | XDTOPackage top-object only. URI namespace for the new package; a non-empty namespace is required for the package to be valid. Defaults to 'http://example.org/<Name>' when omitted. Create-time-only. Ignored for other types. |

## Guide
Creates one metadata node addressed by a 1C full-name FQN, then force-exports the affected top object to its `.mdo` on disk so the change survives a refresh / clean_project / EDT restart. Replaces the former create_metadata_object (top-level) and add_metadata_attribute (member) tools.

## Addressing (the FQN tells the tool what to create)
- Top object: `Type.Name`, e.g. `Catalog.Products`.
- Member: `Type.Name.Kind.Name`, e.g. `Catalog.Products.Attribute.Weight`, `InformationRegister.Prices.Dimension.Product`, `InformationRegister.Prices.Resource.Sum`, `Catalog.Products.TabularSection.Lines`, `Enum.Colors.EnumValue.Red`.
- The leading TYPE token and the KIND token may be English or Russian; the Name parts are the programmatic Names, never the synonym.

## Supported kinds
- Top-level types: any configuration object type (Catalog, Document, Information/Accumulation/Accounting/CalculationRegister, Enum, ChartOfAccounts / ChartOfCharacteristicTypes / ChartOfCalculationTypes, ExchangePlan, BusinessProcess, Task, Subsystem, HTTPService, WebService, Constant, CommonForm, CommonCommand, Report, DataProcessor, CommonModule, ...). A type the EDT factory cannot instantiate is rejected with a clear error.
- Member kinds: Attribute, TabularSection, Dimension, Resource, EnumValue, Command, plus the type-specific children AccountingFlag / ExtDimensionAccountingFlag (ChartOfAccounts), AddressingAttribute (Task) and Column (DocumentJournal) - each on the owner types that declare them.
- Template (`Catalog.X.Template.T`) and Recalculation (`CalculationRegister.R.Recalculation.Rc`) are created with their default content wired (a Recalculation's produced types; a Template defaults to the SpreadsheetDocument type).
- Service children: an HTTPService URLTemplate and its Method (`HTTPService.S.URLTemplate.T.Method.M`), and a WebService Operation and its Parameter (`WebService.S.Operation.O.Parameter.P`).
- Members of a NESTED object are supported too, e.g. a tabular-section attribute `Catalog.X.TabularSection.T.Attribute.A` (the owner is re-navigated by name inside the write transaction).
- Form OBJECT: a managed form on a metadata object (`Catalog.X.Form.FormName`, 4 parts, form token at position 2; the form token may be `Form`/`Forms`/`Форма`/`Формы`). Creates the MD-form (a `BasicForm` on the owner's `forms`) AND a renderable, empty content `Form` (its `Form.form`), linked both ways and attached under the canonical FQN so its structure re-resolves. The form gets the render-critical `autoCommandBar` + the standard form defaults, so it opens/renders in the EDT editor. Add structure afterwards with the form-member FQNs below. Optional `synonym` (with `language`) and the top-level `setAsDefault` flag (register the form as the owner's default object form). A `CommonForm` is a top object - create it as `CommonForm.Name` (2 parts) via the normal top-level path.
- Form content: a member of a form (`Catalog.X.Form.F.<Kind>.Name` or `CommonForm.F.<Kind>.Name`) where Kind is Attribute, Command, Group, Decoration, Field or Button. Optional properties: `title` (with `language`); `parent` to nest under an item; a Field binds to a form attribute via `dataPath` (e.g. {name:'dataPath', value:'Price'}); a Button binds to a form command via `command` (the target must exist first); a Group takes an optional `type` (UsualGroup/Popup/Pages/Page/CommandBar/ButtonGroup/ColumnGroup; defaults by container, e.g. Popup inside a command bar). Visual items are created with the full designer defaults: enabled + visible, the field's header/footer/edit-mode and input extInfo flags, and the designer auto-children (extended tooltip; context menu for fields and decorations) named `<Item><Suffix>` per the configuration script variant.
- Button/item parent: `parent` names an existing item (`{name:'parent', value:'MainGroup'}`); the special value `AutoCommandBar` places the item in the form's command bar (`MyTable.AutoCommandBar` for a table's bar). Buttons inside a command bar / context menu / popup automatically get the CommandBarButton type the platform requires there. Placements the designer forbids are rejected (a button in a table / pages / column group; a decoration in a command bar, context menu or popup/pages/button/column group).
- Form event handler: `Catalog.X.Form.F.Handler.EventName` binds a BSL handler to a form event (the leaf is the event name, e.g. OnOpen); an ITEM-level handler uses `Catalog.X.Form.F.Field.Price.Handler.OnChange` (the item's events include its kind, e.g. an input field's OnChange). An unknown event is rejected WITH the list of available events (in the configuration language). The BSL procedure name is the `procedure` property (defaults to the event name). In a configuration EXTENSION, pass `callType` (Before/After/Instead) to intercept the base element's event instead of binding a plain handler - see "Extension event interception" below.
- Form command action: `Catalog.X.Form.F.Command.C.Handler.Action` binds the command's action to a BSL procedure (the designer's "Action" property). `procedure` defaults to the COMMAND name here. Typical flow: create the Command, create this Action handler, create a Button bound to the command, then write the BSL procedure with write_module_source.

## Parameter details
- `projectName` (required) - EDT project name.
- `fqn` (required) - full-name FQN of the node to create.
- `properties` (optional) - array of `{name, value, language?}` applied at creation. This version applies `synonym` (with optional `language` CODE, e.g. 'en'/'ru'; defaults to the configuration default language) and `comment`. Any other property name is rejected - set those via modify_metadata.
- `expectedNotExists` (optional, default false) - assert the node does not yet exist, for a sharper precondition error. A real duplicate is rejected regardless.
- `normalizeYo` (optional, default true) - normalize the Russian letter `ё`->`е` / `Ё`->`Е` in the NAME (the trailing FQN segment) and in any `synonym` / `comment` value, applied at the parse step before identifier validation. `ё` in a Name is flagged by the 1C standard `mdo-ru-name-unallowed-letter`, so this stores a compliant Name; set `false` to keep `ё` exactly as supplied. The result lists the rewritten fields under `normalized`.
- `setAsDefault` (optional, default false) - FORM OBJECT create only (`Type.Object.Form.FormName`). When true, registers the new form as the owner's default object form. Ignored for other create kinds.
- `callType` (optional) - FORM EVENT HANDLER only, in a configuration EXTENSION (`Before`/`After`/`Instead`). Selects extension event interception (see below). Omit for a plain base handler; rejected on a base configuration; ignored for non-handler creates.

### Extension event interception (configuration extensions)
In a configuration EXTENSION you intercept a base form element's event rather than replacing its handler. Address the item handler FQN and pass `callType`:
- `Before` - the extension procedure runs BEFORE the base handler.
- `After` - the extension procedure runs AFTER the base handler (the common case).
- `Instead` - the extension procedure runs INSTEAD of the base handler (1C "Вместо"; serialized as the `Override` call type on disk).

This writes a `form:EventHandlerExtension` (`<event>`, `<name>`, `<callType>`) onto the adopted form item. It COEXISTS with the base element's own handler (that is the whole point) and works even when the base element has no handler of its own. `ChangeAndValidate` is a METHOD-only call type and is rejected for an event.

Preconditions and flow:
1. The form must already be ADOPTED into the extension (`adopt_metadata_object`); otherwise the form does not resolve.
2. `create_metadata` with `fqn='Document.X.Form.F.Field.Date.Handler.OnChange'`, `callType='After'`, `properties:[{name:'procedure', value:'ext_DateOnChangeAfter'}]` writes only the `.form` handler.
3. Add the matching BSL procedure to the extension form module with `write_module_source` (e.g. `&AtClient Procedure ext_DateOnChangeAfter(Item) ... EndProcedure`). For METHOD interception (annotations like `&After("BaseMethod")`) use `write_module_source` directly - see its guide.

### CommonModule presets (top-object `CommonModule` only; create-time-only)
A bare CommonModule has no flags set, which the platform `common-module-type` validator flags. Pass `commonModuleKind` to get a standards-compliant flag combination it accepts:
- `commonModuleKind`: `'Server'` (default), `'ServerCall'`, `'ClientManaged'`, `'ClientOrdinary'`, `'ClientServer'`, `'Global'`.
- `serverCall` (boolean): make a server module callable from the client. Valid only with a server kind; incompatible with `Global`.
- `privileged` (boolean): run with full access. Valid only with the `Server` kind (not a server call).
- `returnValuesReuse`: `'DontUse'` (default), `'DuringRequest'`, `'DuringSession'`. `DuringSession` yields a cached module on `Server`/`ServerCall`/`ClientManaged`/`ClientOrdinary`; `DuringRequest` has no validator-accepted combo.

The kind + modifiers map to ONE canonical 8-flag combination; an illegal mix (e.g. `serverCall` on a client kind, `privileged` with `Global`) is rejected up front with an actionable error. These flags cannot be re-derived post-hoc, so they are create-time args (not `properties`). The success payload echoes the resolved `commonModuleKind`.

### XDTOPackage namespace (top-object `XDTOPackage` only; create-time-only)
- `targetNamespace`: the package URI namespace. A non-empty namespace is required for the package to be valid; defaults to `http://example.org/<Name>` when omitted. The success payload echoes the written `targetNamespace`.

### Edition-gated top types
`Bot`, `WebSocketClient` and `IntegrationService` are created only when the loaded platform version exposes their Configuration collection. On a build that lacks the collection feature the create returns a clear "Could not resolve configuration collection" error rather than crashing (the feature is probed on the live `Configuration` EClass, never assumed). On the 2026.1 target platform all three resolve and create.

## Bilingual (ru/en)
The synonym EMap is keyed by the language CODE (`ru`/`en`), never the language name. Objects are resolved by programmatic Name; only the type / kind tokens are dialect-aware.

## Examples
- Top object: `{projectName: 'P', fqn: 'Catalog.Products'}`
- With synonym: `{projectName: 'P', fqn: 'Document.Invoice', properties: [{name: 'synonym', value: 'Invoice', language: 'en'}]}`
- Attribute: `{projectName: 'P', fqn: 'Catalog.Products.Attribute.Weight'}`
- Register resource: `{projectName: 'P', fqn: 'InformationRegister.Prices.Resource.Sum'}`
- Extension event interception (After): `{projectName: 'Ext', fqn: 'Document.Order.Form.DocumentForm.Field.Date.Handler.OnChange', callType: 'After', properties: [{name: 'procedure', value: 'ext_DateOnChangeAfter'}]}`

## Result
JSON with `action='created'`, the normalized `fqn`, `kind` (the EClass - `EventHandlerExtension` for an extension event handler), `name`, `persisted`, and (when a synonym was written) the echoed `synonym` + resolved `language`. An extension event handler also echoes the written `callType`. After a create run get_project_errors to verify.

## Gotchas
- A node whose FQN already resolves is rejected as a duplicate.
- An unknown type / kind token or a malformed FQN (odd trailing token) is rejected with guidance; a recognized top-type the EDT factory cannot instantiate fails with a clear error (no static allow-list).
- Members are created with DEFAULT properties (e.g. a default type); adjust with modify_metadata.
- `persisted=false` means the in-memory change committed but the `.mdo` export did not confirm - re-check before relying on it on disk.
- No automatic undo: to revert a create, delete the node with delete_metadata (same FQN). create_metadata is intentionally NOT confirm-gated because it is reversible that way; only the destructive / high-blast-radius writes (delete_metadata, rename_metadata_object, update_database, delete_project) are gated with a confirm-preview.

---
*Generated from the live MCP server (`get_tool_guide`) by `docs/generate_tool_docs.py`. Do not edit this file. Edit the tool's description/schema in its Java source and its guide body in `mcp/bundles/com.ditrix.edt.mcp.server/guides/<tool>.md`.*
