Reads ONE procedure or function out of a BSL module by name - so you get the 40 lines you care about instead of a 60,000-line file. The body comes back with its doc-comment and a metadata header you can feed straight back into an edit.

## When to use
- You know the method name and just want to read its implementation.
- You are about to edit a method and need its current body plus the `contentHash` for a safe write.
- Cheaper, more focused alternative to reading the whole module.

## Parameter details
- `projectName` (required) - the EDT project.
- `modulePath` (required) - the module from `src/`, e.g. `CommonModules/MyModule/Module.bsl`.
- `methodName` (required) - the procedure/function name, **case-insensitive**.

## What you get
Markdown with a YAML front-matter block (projectName, module, `contentHash`, method, type, export flag, startLine/endLine, totalLines, and region) followed by the source in a ```bsl``` fence. If the method participates in extension interception, that is noted below the code.

## Notes & gotchas
- **Method not found?** It returns the list of available method names in that module - copy the exact one and retry. (Name matching ignores ru/en keyword dialect.)
- The `contentHash` is a whole-MODULE revision token, not a per-method one. Pass it as `expectedHash` to `write_module_source` for an optimistic-lock safe edit; the same token is produced by `read_module_source`, so the two agree.
- To list the methods in a module first, use `get_module_structure`; to read the entire module, use `read_module_source`.
