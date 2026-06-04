---
name: Form Tooling (generate + edit)
issue: 129
issue_url: https://github.com/DitriXNew/EDT-MCP/issues/129
title: Генерация форм и редактирование формы (и ее элементов)
status: planned
overview: Extend the EDT MCP server with form tooling in two stages. Stage 1 adds `generate_form` (standard 1C forms via IFormGenerator). Stage 2 adds form-editing tools (`add_form_item`, `add_form_attribute`) that mutate the form model via IFormItemManagementService / FormAttributeManagementService. Both follow the existing BM-transaction tool pattern. See git/docs_api-edt for the analyzed API.
todos:
  - id: tool-class
    content: Create GenerateFormTool implementing IMcpTool with params projectName/ownerFqn/formType/name/synonym/setAsDefault, mapping form kinds to FormType (OBJECT/LIST/CHOICE/FOLDER/RECORD/RECORD_SET)
    status: pending
  - id: activator-services
    content: Add Activator.getFormGenerator()/getFormFieldGenerator() via the form plugin Guice injector (mirror getModelObjectFactory)
    status: pending
  - id: bm-transaction
    content: "Implement BM transaction: create Form MD object, add to owner.getForms(), attachTopObject, call IFormGenerator.generateForm, attach form content + set module owner (mirror FormNewWizardRelatedModelsFactory)"
    status: pending
  - id: owner-type-validity
    content: Add owner-type vs form-type validity checks (RECORD_SET for registers, FOLDER/FOLDER_CHOICE for hierarchical catalogs, etc.)
    status: pending
  - id: register-tool
    content: Register tool in McpServer.registerTools() and add generate_form to REFACTORING ToolGroup
    status: pending
  - id: manifest
    content: Add Import-Package entries for com._1c.g5.v8.dt.form.generator, com._1c.g5.v8.dt.form.model, com._1c.g5.v8.dt.internal.form in MANIFEST.MF
    status: pending
  - id: docs
    content: Update edt-mcp-tools.md (ru/en) with the new generate_form tool
    status: pending
  - id: build-test
    content: Build bundle and smoke-test generating item/list/choice forms on a Catalog, verify via get_project_errors
    status: pending
  - id: form-model-access
    content: "Stage 2: add helper to resolve com._1c.g5.v8.dt.form.model.Form from a form FQN (via FormUtil) and obtain form services (IFormItemManagementService, FormAttributeManagementService, IFormItemNamingService) from the form Guice injector"
    status: pending
  - id: datapath-helper
    content: "Stage 2: add helper to build AbstractDataPath from a string (e.g. Object.Description) and to resolve a parent FormItemContainer by name (default = form root)"
    status: pending
  - id: add-form-item-tool
    content: "Stage 2: create AddFormItemTool (add_form_item) supporting itemKind field/group/table/button/decoration, params formFqn/itemKind/parentItemName/dataPath/name/title/index; mutate form model inside a BM transaction"
    status: pending
  - id: add-form-attribute-tool
    content: "Stage 2: create AddFormAttributeTool (add_form_attribute) via FormAttributeManagementService for backing form attributes when a field needs one"
    status: pending
  - id: register-stage2
    content: "Stage 2: register add_form_item/add_form_attribute in McpServer and BSL_CODE (or REFACTORING) ToolGroup; extend MANIFEST Import-Package with form.service.item/attribute/naming packages"
    status: pending
  - id: stage2-version-facade
    content: "Stage 2: wrap volatile form-service calls behind a reflective facade with overload fallback (per git/docs_api-edt/infrastructure/03-edt-version-compatibility.md)"
    status: pending
  - id: stage2-docs-test
    content: "Stage 2: document add_form_item/add_form_attribute in edt-mcp-tools.md (ru/en); smoke-test adding field/group/table to a generated form, verify via get_form_layout_snapshot + get_project_errors"
    status: pending
isProject: false
---

> Issue: [#129 — Генерация форм и редактирование формы (и ее элементов)](https://github.com/DitriXNew/EDT-MCP/issues/129)
> Analyzed API reference: `git/docs_api-edt/`

# Form Tooling — Feasibility Assessment

Delivered in two stages:
- Stage 1 — generate standard forms (`generate_form`).
- Stage 2 — edit forms / add elements (`add_form_item`, `add_form_attribute`).

Full analyzed API reference lives in `git/docs_api-edt/` (see `forms/` and `infrastructure/`).

# Stage 1 — Generate Form

## Verdict: feasible, medium complexity

EDT exposes a clean public API for exactly this, and the MCP server already has a near-identical pattern (`create_metadata_object`). The bulk of the work is one new tool class plus small wiring changes. The only genuinely tricky part is reproducing the exact form persistence (two `attachTopObject` calls + module owner) that the EDT wizard does internally.

## Why it is not hard

- The platform API exists and is public/exported. `com._1c.g5.v8.dt.form.generator.IFormGenerator` (exported package, `version=4.0.0`) does all the heavy lifting:

```java
Form generateForm(MdObject owner, BasicForm form, FormType type, ScriptVariant sv,
    String name, Version version, FormFieldInfo fields, Integer columnCount,
    InterfaceCompatibilityMode mode);
```

- `FormType` enum already covers every requested kind: `OBJECT` (форма элемента), `LIST` (форма списка), `CHOICE` (форма выбора), plus `FOLDER`, `FOLDER_CHOICE`, `RECORD`, `RECORD_SET`, `REPORT`, etc.
- The exact create/persist sequence used by the "New Form" wizard is known. The orchestrator `com._1c.g5.v8.dt.form.ui.wizard.FormNewWizardRelatedModelsFactory` does, inside the active BM transaction:
  1. `IFormGenerator.generateForm(...)` -> `form.model.Form`
  2. `tx.attachTopObject((IBmObject) basicForm, fqn)` — the `Form` MD object
  3. `tx.attachTopObject((IBmObject) formModel, ...)` — the form content
  4. `module.setOwner(...)` — the form `Module.bsl`
- The MCP server already implements this exact transaction style in [CreateMetadataObjectTool.java](../mcp/bundles/com.ditrix.edt.mcp.server/src/com/ditrix/edt/mcp/server/tools/impl/CreateMetadataObjectTool.java) (`bmModel.execute(new AbstractBmTask...)`, `factory.create(eClass, version)`, `tx.attachTopObject`, add to owner collection). The new tool reuses all of it.
- Services are obtained via the established Guice-injector pattern. `Activator.getModelObjectFactory()` already does `MdPlugin.getDefault().getInjector().getInstance(...)`; `IFormGenerator`/`IFormFieldGenerator` are bound the same way in `FormRuntimeModule` and retrievable from the form plugin injector.
- `IMcpTool` is a tiny interface (name/description/schema/execute) — adding a tool is mechanical.

## Where the effort/risk is

- Reproducing the precise persistence: building the `Form` MD object, adding it to the owner's `forms` collection, computing the correct FQNs for the two `attachTopObject` calls, and wiring the module owner. This must mirror `FormNewWizardRelatedModelsFactory` carefully; getting the FQN/top-object wrong produces broken or duplicate `.form` files.
- Owner-type vs form-type validity (e.g. `RECORD_SET` only for registers, `FOLDER`/`FOLDER_CHOICE` only for hierarchical catalogs). Needs a small validity map.
- Bundle visibility: `META-INF/MANIFEST.MF` must add `Import-Package` for `com._1c.g5.v8.dt.form.generator`, `com._1c.g5.v8.dt.form.model`, and the form plugin's injector package (`com._1c.g5.v8.dt.internal.form`). `com._1c.g5.v8.dt.bsl.model` is already imported.
- `FormFieldInfo`: for a default form, build the field tree via `IFormFieldGenerator` (as the wizard does) or pass a minimal/empty value. Empty produces a generic form; field generation gives wizard-equivalent output.

## Implementation steps

- Add `GenerateFormTool` in `tools/impl/` implementing `IMcpTool`:
  - params: `projectName`, `ownerFqn` (e.g. `Catalog.Products`), `formType` (item/list/choice/folder/record/recordset + RU aliases), `name` (optional, default per type), `synonym`, `setAsDefault` (optional).
  - resolve owner `MdObject`, `Configuration.getScriptVariant()`, `IV8Project.getVersion()`.
  - run a `bmModel.execute(AbstractBmTask)` that creates the `Form` MD object, adds it to `owner.getForms()`, attaches it as a top object, calls `generateForm(...)`, attaches the form content + sets module owner — mirroring `FormNewWizardRelatedModelsFactory`.
  - JSON result with form FQN + message (same style as `create_metadata_object`).
- Add `Activator.getFormGenerator()` / `getFormFieldGenerator()` using the form plugin Guice injector (mirror `getModelObjectFactory()` in [Activator.java](../mcp/bundles/com.ditrix.edt.mcp.server/src/com/ditrix/edt/mcp/server/Activator.java)).
- Register the tool in [McpServer.java](../mcp/bundles/com.ditrix.edt.mcp.server/src/com/ditrix/edt/mcp/server/McpServer.java) `registerTools()` and add `generate_form` to the `REFACTORING` group in [ToolGroup.java](../mcp/bundles/com.ditrix.edt.mcp.server/src/com/ditrix/edt/mcp/server/preferences/ToolGroup.java).
- Add the `Import-Package` entries in [MANIFEST.MF](../mcp/bundles/com.ditrix.edt.mcp.server/META-INF/MANIFEST.MF).
- Update tool docs in `git/rules/ru/edt-mcp-tools.md` and `git/rules/en/edt-mcp-tools.md`.
- Build and smoke-test: generate item/list/choice forms on a `Catalog`, run `get_project_errors` / `revalidate_objects` to confirm valid output.

## Effort estimate (Stage 1)

- One new tool class (~300-400 lines), ~30 lines across Activator/McpServer/ToolGroup/MANIFEST, plus docs.
- Rough size comparable to `create_metadata_object`, with extra time spent matching the form persistence exactly.

# Stage 2 — Edit Form / Add Elements

## Verdict: feasible, higher complexity than Stage 1

Adds tools that mutate an existing form's content model (`com._1c.g5.v8.dt.form.model.Form`) — adding fields, groups, tables, buttons, decorations, and backing form attributes. The platform offers a high-level service so a single call produces a fully-wired element, but the surface is larger than Stage 1 and requires correct data-path construction and container resolution.

Reference: `git/docs_api-edt/forms/02-form-items.md` and `forms/03-form-persistence.md`.

## Why it is feasible

- High-level service exists: `com._1c.g5.v8.dt.form.service.item.IFormItemManagementService` exposes `addField` / `addGroup` / `addTable` / `addButton` / `addDecoration` / `addAddition` / `addTableFieldsByDataPath` (one call = one fully-configured element, with index/`LAST` positioning).
- Form attributes via `com._1c.g5.v8.dt.form.service.attribute.FormAttributeManagementService` (`addAttribute`, `addAttributeColumn`).
- Same BM-write-transaction pattern as `AddMetadataAttributeTool` (`bmModel.execute` + `tx.getObjectById`), but the transaction object is the form model `Form` instead of an MD object.
- Services come from the same form Guice injector as Stage 1's `IFormGenerator`.

## Where the effort/risk is

- Resolving the form model `Form` from a form FQN (`Catalog.Products.Form.ItemForm`) — needs a helper (via `FormUtil` / BM lookup) since the editable object is the form content, not the MD object.
- Building `AbstractDataPath` from a user string (e.g. `Object.Description`) and resolving the target `FormItemContainer` by name (default = form root).
- Field-vs-attribute dependency: a field usually needs a backing form attribute; the tool must either reuse an existing attribute path or create one first (`add_form_attribute`).
- Unique element naming (`IFormItemNamingService` / `IFormItemNameGeneratorFactory`).
- API volatility: form-service signatures can drift across EDT releases — wrap calls behind a reflective facade with overload fallback (see `git/docs_api-edt/infrastructure/03-edt-version-compatibility.md`).

## Implementation steps (Stage 2)

- Helpers:
  - resolve `Form` model from `formFqn` (via `FormUtil` / BM), and obtain `IFormItemManagementService`, `FormAttributeManagementService`, `IFormItemNamingService` from the form injector (extend the Stage 1 Activator getters).
  - build `AbstractDataPath` from a dotted string; resolve parent `FormItemContainer` by name (default form root).
- `AddFormItemTool` (`add_form_item`):
  - params: `projectName`, `formFqn`, `itemKind` (`field`/`group`/`table`/`button`/`decoration`), `parentItemName` (optional, default root), `dataPath` (for field/table), `name` (optional -> auto), `title` (+`language`), `index` (optional).
  - inside `bmModel.execute(AbstractBmTask)`: re-fetch the form model by bmId, build `FormNewItemDescriptor(name, titles, false)`, call the matching `IFormItemManagementService.addX(...)`.
  - JSON result with created item name + form FQN.
- `AddFormAttributeTool` (`add_form_attribute`): create/attach a form attribute via `FormAttributeManagementService.addAttribute(...)` for cases where a field needs a backing attribute.
- Reflective facade for the form-service calls (overload fallback) per the compatibility chapter.
- Register both tools in [McpServer.java](../mcp/bundles/com.ditrix.edt.mcp.server/src/com/ditrix/edt/mcp/server/McpServer.java) and add them to a `ToolGroup` (`BSL_CODE` alongside the existing form snapshot/screenshot tools, or `REFACTORING`).
- Extend `Import-Package` in [MANIFEST.MF](../mcp/bundles/com.ditrix.edt.mcp.server/META-INF/MANIFEST.MF): `com._1c.g5.v8.dt.form.service.item`, `com._1c.g5.v8.dt.form.service.attribute`, `com._1c.g5.v8.dt.form.service.naming` (in addition to Stage 1's `com._1c.g5.v8.dt.form.model`).
- Docs in `edt-mcp-tools.md` (ru/en); smoke-test: generate a form (Stage 1), then add a field/group/table, verify with `get_form_layout_snapshot` + `get_project_errors`.

## Effort estimate (Stage 2)

- Two new tool classes + helpers (~400-600 lines total), plus the reflective facade and MANIFEST/registration/docs.
- Larger and trickier than Stage 1 mainly due to data-path/container resolution and the field/attribute dependency; individual operations stay one-call thanks to the high-level service.
