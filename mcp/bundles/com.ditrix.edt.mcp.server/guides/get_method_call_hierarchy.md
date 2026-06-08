Resolves a BSL method's call graph in one direction at a time using the semantic AST (resolved feature references), not plain text. Because matching is by the resolved method (not by literal spelling), it finds calls written in either the ru or en BSL dialect - unlike literal `search_in_code`.

## When to use
- `callers`: find every place that invokes a given procedure/function before renaming, changing its signature, or assessing impact.
- `callees`: list what a method itself calls, to understand its dependencies.
- Prefer this over `search_in_code` for identifier lookup: text search is literal and not dialect-aware, so it misses the other-language spelling.

## Parameter details
- `projectName` (required) - EDT project name.
- `modulePath` (required) - path from the project's `src/` folder to the module that DEFINES the method, e.g. `CommonModules/MyModule/Module.bsl` or `Documents/SalesOrder/ManagerModule.bsl`.
- `methodName` (required) - the procedure/function name; case-insensitive, matched by programmatic Name (not by synonym).
- `direction` - `callers` (default) = who calls this method; `callees` = what this method calls. An unknown value returns an error.
- `limit` - max rows returned; default 100, max 500 (clamped). The reported total count is exact even when rows are truncated.

## How callers are found
BSL invocations are linked by name through scoping and are NOT stored as ordinary cross-references in the index, so the generic Xtext reference finder cannot see them. This tool mirrors EDT's own strategy: text-prefilter the `.bsl` modules whose source mentions the method name, parse only those, and match each invocation to this exact method by its resolved feature entry. When the resolver left entries empty it falls back to the call qualifier (`Module.Method`) or an unqualified call inside the defining module itself.

## Output
Markdown table. Callers: # / Module / Method / Line / Call Code. Callees: # / Called Method / Line / Call Code. Long or multi-line call expressions are compacted (comment lines stripped, body collapsed to `Name(...)`).

## Examples
- Callers (default): `{projectName, modulePath: "CommonModules/MyModule/Module.bsl", methodName: "DoWork"}`.
- Callees: `{projectName, modulePath: "CommonModules/MyModule/Module.bsl", methodName: "DoWork", direction: "callees"}`.

## Notes & gotchas
- `modulePath` must point at the module that DEFINES the method; if the method is not found there the tool returns a not-found response listing the module's methods.
- `callees` lists raw invocation names from the target method's body and does not resolve each callee to its defining module.
- Requires a loadable BSL AST (EMF); a module that fails to parse returns an error pointing at the EDT Error Log.
