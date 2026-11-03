Clone one Catalog, Document, Report or DataProcessor inside a plain configuration project, including its owned forms, commands, modules, templates and predefined items. Extensions, external-objects projects, Configuration and nested members are refused. Only TYPE tokens are bilingual; names are programmatic identifiers, never synonyms.

## Preview and confirm

1. Call without `confirm` (or with `confirm:false`). Supply the intended `newName` and `transfer` selection in this preview too.
2. Inspect `resources`, `incomingRelations`, `notCopied`, `referenceScanComplete` and the warning. Incoming entries identify their `kind`, `owner` and `transferSupport`. The indexed reference scan is best effort; an incomplete scan never means there are no incoming relations. Dynamically constructed code names are not enumerable.
3. Repeat the same project, source, name and selection with `confirm:true` and `expectedHash` equal to preview's `contentHash`. Missing/stale hashes refuse before copying. Changing the transfer selection requires another preview. A name is not reserved by preview.

```json
{"projectName":"MyConfiguration","fqn":"Catalog.Products","newName":"ProductsCopy"}
```

After reviewing incoming owners, preview the selected scope:

```json
{"projectName":"MyConfiguration","fqn":"Catalog.Products","newName":"ProductsCopy","transfer":[{"kind":"subsystem","owner":"Subsystem.Sales"},{"kind":"roleRights","owner":"Role.Sales"}]}
```

```json
{"projectName":"MyConfiguration","fqn":"Catalog.Products","newName":"ProductsCopy","transfer":[{"kind":"subsystem","owner":"Subsystem.Sales"},{"kind":"roleRights","owner":"Role.Sales"}],"confirm":true,"expectedHash":"<contentHash from the selected-scope preview>"}
```

## Names and native semantics

`newName` is optional. When present it must be an unused 1C identifier in the source's metadata collection, compared without case. It is assigned before attachment: there is no temporary auto-named object and no rename/refactoring of the source. Omit it to use EDT's free-name algorithm; always use the returned actual `fqn` in later calls.

The operation uses the exported native paste copier behind `IModelObjectCopySupport`, retaining its graph, external-resource, type-reference and identity handling. The service's private factory accepts no name and opens its own transaction, so the tool configures its public `AttachingModelObjectCopier` inside one global BM write. It retains the plain-configuration filters and additional-index identity handling. Predefined item ids follow native paste unchanged; they are not metadata UUIDs. Compare them with navigator Copy/Paste during live acceptance.

## Incoming relations

The default `transfer:[]` changes no external consumers. Subsystem membership and an existing role's authored **object-rights row** can be replicated only by explicitly selecting an eligible owner from preview. The role row includes its values, denies and RLS; role flags/templates stay in that existing role. Member-rights rows are separate relations and are reported only. Effective rights can still come from platform defaults for new objects. The source's original memberships and rights remain.

Other indexed relations (event subscriptions, exchange plans, common commands, metadata and code uses) are reported only. Selecting an unsupported kind is refused with instructions. No consumer is redirected from the source to the clone.

## Text and persistence

BSL, query, DCS and HTML text is preserved. `codeReferences` reports literal source-name occurrences in copied UTF-8 text files with project-relative `file` and 1-based `line`. It includes comments/string literals and is not semantic refactoring. Binary formats and dynamically constructed names may hide additional uses. Check them in EDT.

Unsaved editors that may affect the project and unconfirmed source export block copying. The tool never saves user editors. The hash covers the owned model, binary file hashes, destination collection names and selected owners. It is rechecked inside the write boundary under the workspace scheduling rule.

The clone, its external model roots, Configuration and selected role/subsystem owners are force-exported. A successful apply waits for the export barrier, checks expected file presence and unmodeled resource bytes, then loads modeled files detached and compares their persisted properties, containment and symbolic references against BM. It also checks root identity/name and Configuration registration before returning `persisted:true`. This does not establish runtime business-logic correctness. Inspect `get_project_errors`, validate forms and inspect the EDT log before using the clone.

## Partial errors and recovery

Native file copying and BM are not one atomic filesystem transaction. A failed copy/export verification reports the created `fqn` when known, with `mutationCommitted` or `mutationOutcomeUnknown`. External files can remain after a BM rollback. Inspect the model and project files first; never automatically repeat confirm, since a retry may create another copy. Use `resync_to_disk` for a committed model with pending export. To remove a verified clone, preview and confirm `delete_metadata` on its returned FQN. Selected incoming transfers may require reconciliation as well.
