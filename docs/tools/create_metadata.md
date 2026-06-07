# create_metadata

Create a metadata node addressed by a 1C full-name FQN: a top-level object (Catalog.Products) or a subordinate member (Catalog.Products.Attribute.Weight, InformationRegister.Prices.Dimension.Product, Enum.Colors.EnumValue.Red). The kind is inferred from the FQN; type and kind tokens may be English or Russian. Full parameters and examples: call get_tool_guide('create_metadata').

## Parameters
| Parameter | Required | Type | Description |
| --- | --- | --- | --- |
| projectName | yes | string | EDT project name (required). |
| fqn | yes | string | Full-name FQN of the node to create (required). Top object: 'Type.Name' (e.g. 'Catalog.Products'). Member: 'Type.Name.Kind.Name' (e.g. 'Catalog.Products.Attribute.Weight'). The trailing Name is the new node's programmatic Name; type / kind tokens may be English or Russian. |
| properties | — | array | Optional properties to apply at creation, as [{name, value, language?}]. This version applies 'synonym' (with optional 'language' code) and 'comment'; other property names are rejected (set them via modify_metadata). |
| expectedNotExists | — | boolean | Optional stale-intent guard (default false): assert the node does not yet exist for a sharper precondition error. A real duplicate is always rejected anyway. |

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
- Form content: a member of a form (`Catalog.X.Form.F.<Kind>.Name` or `CommonForm.F.<Kind>.Name`) where Kind is Attribute, Command, Group, Decoration, Field or Button. Optional properties: `title` (with `language`); `parent` to nest under an item; a Field binds to a form attribute via `dataPath` (e.g. {name:'dataPath', value:'Price'}); a Button binds to a form command via `command` (the target must exist first).
- Form event handler: `Catalog.X.Form.F.Handler.EventName` binds a BSL handler to a form event (the leaf is the event name, e.g. OnOpen); an ITEM-level handler uses `Catalog.X.Form.F.Field.Price.Handler.OnChange` (the item's events include its kind, e.g. an input field's OnChange). An unknown event is rejected WITH the list of available events (in the configuration language). The BSL procedure name is the `procedure` property (defaults to the event name).

## Parameters
- `projectName` (required) - EDT project name.
- `fqn` (required) - full-name FQN of the node to create.
- `properties` (optional) - array of `{name, value, language?}` applied at creation. This version applies `synonym` (with optional `language` CODE, e.g. 'en'/'ru'; defaults to the configuration default language) and `comment`. Any other property name is rejected - set those via modify_metadata.
- `expectedNotExists` (optional, default false) - assert the node does not yet exist, for a sharper precondition error. A real duplicate is rejected regardless.

## Bilingual (ru/en)
The synonym EMap is keyed by the language CODE (`ru`/`en`), never the language name. Objects are resolved by programmatic Name; only the type / kind tokens are dialect-aware.

## Examples
- Top object: `{projectName: 'P', fqn: 'Catalog.Products'}`
- With synonym: `{projectName: 'P', fqn: 'Document.Invoice', properties: [{name: 'synonym', value: 'Invoice', language: 'en'}]}`
- Attribute: `{projectName: 'P', fqn: 'Catalog.Products.Attribute.Weight'}`
- Register resource: `{projectName: 'P', fqn: 'InformationRegister.Prices.Resource.Sum'}`

## Result
JSON with `action='created'`, the normalized `fqn`, `kind` (the EClass), `name`, `persisted`, and (when a synonym was written) the echoed `synonym` + resolved `language`. After a create run get_project_errors to verify.

## Gotchas
- A node whose FQN already resolves is rejected as a duplicate.
- An unknown type / kind token or a malformed FQN (odd trailing token) is rejected with guidance; a recognized top-type the EDT factory cannot instantiate fails with a clear error (no static allow-list).
- Members are created with DEFAULT properties (e.g. a default type); adjust with modify_metadata.
- `persisted=false` means the in-memory change committed but the `.mdo` export did not confirm - re-check before relying on it on disk.
- No automatic undo: to revert a create, delete the node with delete_metadata (same FQN). create_metadata is intentionally NOT confirm-gated because it is reversible that way; only the destructive / high-blast-radius writes (delete_metadata, rename_metadata_object, update_database, delete_project) are gated with a confirm-preview.

---
*Generated from the live MCP server (`get_tool_guide`) by `docs/generate_tool_docs.py`. Edit the tool's Java source, not this file.*
