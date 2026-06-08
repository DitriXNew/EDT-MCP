The table of contents for ONE BSL module: every procedure and function with its line range, export flag, execution context (&AtServer / &AtClient), and the region it lives in - plus the module's regions and, on request, its module-level variables. This is how you map out a module before reading any code: you get the names and line numbers, then pull individual bodies with `read_method_source`.

## When to use
- You are about to work in a module and need its method list and where each one sits.
- You want to find a method's exact line range to read or edit it precisely.
- You want the region layout, or which methods are exported / run at server vs client.

## Parameter details
- `projectName` (required) - the EDT project.
- `modulePath` (required) - the module from `src/`, e.g. `CommonModules/MyModule/Module.bsl`.
- `includeVariables` - also list module-level variable declarations (default `false`).
- `includeComments` - include each method's doc-comment (only shown in `detailed`; default `false`).
- `responseFormat` - `concise` (default) drops the verbose Parameters and Description columns to save tokens; `detailed` gives the full table with signatures and doc-comments. Use `detailed` when you actually need parameter lists.

## What you get
Markdown: a header with procedure/function counts and total lines, a **Regions** list (with line ranges), an optional **Variables** table, and a **Methods** table (#, Type, Name, Export, Context, Lines, Region - plus Parameters/Description in detailed mode). When the module participates in extension interception, a footer lists those links.

## Notes & gotchas
- This is for a SINGLE module. To discover module paths across a whole project use `list_modules`.
- Method names resolve regardless of ru/en dialect (the structure comes from the parsed model, not text matching).
- Default `concise` mode omits parameter signatures on purpose; switch to `detailed` (and `includeComments=true`) when you need them, or read a single method's full body with `read_method_source`.
- Needs the project open and fully indexed; on a still-building project it returns a clear "BSL model is not available" error - let it settle or `clean_project` first.
