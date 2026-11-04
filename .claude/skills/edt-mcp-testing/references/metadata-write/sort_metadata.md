# sort_metadata — acceptance procedure

Use a ready fixture project and the current branch's deployed bundle. Run `tests/e2e/tools/test_sort_metadata.py`; it asserts exact disk order with or without optional V8 code style autosort. Restore extension fixtures with the shared harness helper and resync their models.

## Inspect policy first

```json
{"projectName":"TestConfiguration","fqn":"Configuration.Catalogs","mode":"list"}
```

Call `sort_metadata`; its order must match the EList and `.mdo` sequence. Presence of `autosort` reports effective installed-plugin policy; absence means the collection is ungoverned. Validate the six fields `plugin`, `governs`, `setting` (human scope), `order`, `effect`, `userAction`, and the matching plain message. Derive comparator direction from `order`. Presentation listings do not prove stored order.

For a governed list, explicit/move and `create_metadata.insertPosition` must refuse before mutation, name the collection and ask the user to turn autosort off through `Project > Properties > V8 > Metadata objects sort > Auto sort`, with the governing UI section. The message must forbid agent setting changes. Alphabetical mode uses the native comparator and reports the maintained list; unchanged lists require `alreadySorted:true` and no write transaction. Never write autosort preferences.

## Manual ordering

Create `Catalog.E2EOrder` and attributes `Zulu`, `Alpha`, `Beta`. If attributes are governed, use an enum's `EnumValues` collection and `EnumValue` members instead. For an ungoverned attribute collection call `sort_metadata` with:

```json
{"projectName":"TestConfiguration","fqn":"Catalog.E2EOrder.Attributes"}
{"projectName":"TestConfiguration","fqn":"Catalog.E2EOrder.Attributes","mode":"explicit","items":["Zulu","Alpha"]}
{"projectName":"TestConfiguration","fqn":"Catalog.E2EOrder.Attributes","mode":"move","objectName":"Alpha","before":"Zulu"}
{"projectName":"TestConfiguration","fqn":"Catalog.E2EOrder.Attributes","mode":"move","objectName":"Alpha","after":"Zulu"}
{"projectName":"TestConfiguration","fqn":"Catalog.E2EOrder.Attributes","mode":"move","objectName":"Beta","direction":"up","positions":1000}
{"projectName":"TestConfiguration","fqn":"Catalog.E2EOrder.Attributes","mode":"move","objectName":"Beta","direction":"down","positions":1000}
```

Assert parsed `.mdo` order after every call, then read mode=list. Check actual one-based `moved` positions and repeat adjacent before/after calls to require `alreadySorted:true` and no moves. Distances clamp at the ends. For recursive alphabetical sorting, verify nested tabular-section attributes and capped autosort summaries; explicit/move remain single-level. Cover Russian TYPE/KIND addresses, extension-owned/adopted entries, and register-dimension warnings.

## ERP timing calls

Do not run these during fixture acceptance. Confirm that `Document.ЗаказКлиента` exists and is editable in `ERP_XML`; record any replacement large-object FQN. Time these two separate `sort_metadata` calls:

```json
{"projectName":"ERP_XML","fqn":"Document.ЗаказКлиента","recursive":true}
{"projectName":"ERP_XML","fqn":"Configuration"}
```

Record elapsed time, no-op state, descendant count, autosort count and export outcome. The configuration call sorts direct collections only; recursive configuration sorting is a different workload.
