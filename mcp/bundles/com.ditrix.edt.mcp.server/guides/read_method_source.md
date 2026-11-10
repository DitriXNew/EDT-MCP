Reads procedures or functions out of a BSL module by name - so you get the 40 lines you care about instead of a 60,000-line file. Each body comes back with its doc-comment and a metadata header you can feed straight back into an edit.

## When to use
- You know the method name and just want to read its implementation.
- You need several methods of the same module: pass them all in `methodNames` - one call, one module read, one `contentHash`.
- You are about to edit a method and need its current body plus the `contentHash` for a safe write.
- Cheaper, more focused alternative to reading the whole module.

## Parameter details
- `projectName` (required) - the EDT project.
- `modulePath` (required) - the module from `src/`, e.g. `CommonModules/MyModule/Module.bsl`.
- `methodName` - one procedure/function name, **case-insensitive**.
- `methodNames` - an array of up to 50 names read in one call (more is refused - split the list or use `read_module_source`). Case-insensitive duplicates are read once.
- One of `methodName` / `methodNames` is required. Both may be given: `methodName` is read first, then the list, in request order.

## What you get
- `methodName` alone: Markdown with a YAML front-matter block (projectName, module, `contentHash`, method, type, export flag, startLine/endLine, totalLines, and region) followed by the source in a ```bsl``` fence.
- `methodNames`: ONE front-matter block for the module (projectName, module, `contentHash`, totalLines, `methodsFound`, and `methodsNotFound` listing the names that do not exist, when any), then one `### <Name>` section per found method in request order: a summary line (`Function · export · lines 12-40 · region X`) and the ```bsl``` fence.
- If a method participates in extension interception, that is noted below its code.

## Notes & gotchas
- **Method not found?** When none of the requested names exists it returns the list of available method names in that module - copy the exact one and retry. With `methodNames`, names that exist are still returned and the rest are listed in `methodsNotFound`. (Name matching ignores ru/en keyword dialect.)
- The `contentHash` is a whole-MODULE revision token, not a per-method one. Pass it as `expectedHash` to `write_module_source` for an optimistic-lock safe edit; the same token is produced by `read_module_source`, so the two agree.
- To list the methods in a module first, use `get_module_structure`; to read the entire module, use `read_module_source`.
