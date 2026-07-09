# get_method_call_hierarchy

Find a BSL method's call hierarchy: who calls it (callers, default) or what it calls (callees), via semantic AST analysis that resolves ru/en spellings (unlike literal search_in_code). Full parameters and examples: call get_tool_guide('get_method_call_hierarchy').

## Parameters
| Parameter | Required | Type | Description |
| --- | --- | --- | --- |
| projectName | yes | string | EDT project name (required) |
| modulePath | yes | string | Path from src/ folder, e.g. 'CommonModules/MyModule/Module.bsl' (required) |
| methodName | — | string | Name of the procedure/function (case-insensitive). Required for direction 'callers'/'callees'; optional for 'outgoing' (omit to aggregate the whole module). |
| direction | — | string (one of: callers, callees, outgoing) | 'callers' (default) = who calls this method; 'callees' = what this method calls; 'outgoing' = aggregated distinct call targets (module-wide when methodName omitted) |
| extApiPrefix | — | string | For direction 'outgoing': literal call-qualifier prefix (case-insensitive) that flags a target as an external service API. Default: the 1C region name 'ПрограммныйИнтерфейсСервиса'. |
| limit | — | integer | Max results. Default: 100, max: 500 |

## Guide
Resolves a BSL method's call graph in one direction at a time using the semantic AST (resolved feature references), not plain text. Because matching is by the resolved method (not by literal spelling), it finds calls written in either the ru or en BSL dialect - unlike literal `search_in_code`.

## When to use
- `callers`: find every place that invokes a given procedure/function before renaming, changing its signature, or assessing impact.
- `callees`: list what a method itself calls, to understand its dependencies.
- `outgoing`: get an aggregated overview of the distinct call targets of a method (or of the whole module) - one row per `qualifier.method` with a call-site count, useful for spotting which external/service APIs a module depends on and how heavily.
- Prefer this over `search_in_code` for identifier lookup: text search is literal and not dialect-aware, so it misses the other-language spelling.

## Parameter details
- `projectName` (required) - EDT project name.
- `modulePath` (required) - path from the project's `src/` folder to the module that DEFINES the method, e.g. `CommonModules/MyModule/Module.bsl` or `Documents/SalesOrder/ManagerModule.bsl`.
- `methodName` - the procedure/function name; case-insensitive, matched by programmatic Name (not by synonym). Required for `callers` and `callees`. Optional for `outgoing`: omit it to aggregate the whole module; supply it to aggregate a single method's body.
- `direction` - `callers` (default) = who calls this method; `callees` = what this method calls; `outgoing` = aggregated distinct call targets. An unknown value returns an error.
- `extApiPrefix` - only used by `outgoing`. A literal call-qualifier prefix, compared case-insensitively against each target's qualifier token; a match flags the row as an external service API (`ExtAPI = yes`). This is a plain text match on the call qualifier (`Module` part), NOT a resolved-module lookup. Default: the conventional 1C region name `ПрограммныйИнтерфейсСервиса`.
- `limit` - max rows returned; default 100, max 500 (clamped). For `outgoing` the limit clamps DISTINCT target rows. The reported total count is exact even when rows are truncated.

## How callers are found
BSL invocations are linked by name through scoping and are NOT stored as ordinary cross-references in the index, so the generic Xtext reference finder cannot see them. This tool mirrors EDT's own strategy: text-prefilter the `.bsl` modules whose source mentions the method name, parse only those, and match each invocation to this exact method by its resolved feature entry. When the resolver left entries empty it falls back to the call qualifier (`Module.Method`) or an unqualified call inside the defining module itself.

## How outgoing calls are aggregated
`outgoing` walks the AST of the chosen scope (a single method's body when `methodName` is given, otherwise the whole module) and groups every invocation by its `qualifier.method` pair. The qualifier token is derived from the call shape:
- an unqualified local call (`DoWork(...)`) -> `(local)`;
- a qualified module call (`MyModule.DoWork(...)`) -> the module name (`MyModule`);
- a chained or expression call (`Foo().Bar()`, `a.b.Method()`) -> `(expr)`.

Each distinct pair reports the number of call sites (`Count`) and the smallest source line where it first appears (`First line`). The `ExtAPI` column is `yes` when the qualifier literally starts with `extApiPrefix` (case-insensitive); the synthetic `(local)` and `(expr)` tokens are always `-`.

## Output
Markdown table.
- Callers: # / Module / Method / Line / Call Code.
- Callees: # / Called Method / Line / Call Code. Long or multi-line call expressions are compacted (comment lines stripped, body collapsed to `Name(...)`).
- Outgoing: Qualifier / Method / Count / First line / ExtAPI (one row per distinct target, first-seen order).

## Examples
- Callers (default): `{projectName, modulePath: "CommonModules/MyModule/Module.bsl", methodName: "DoWork"}`.
- Callees: `{projectName, modulePath: "CommonModules/MyModule/Module.bsl", methodName: "DoWork", direction: "callees"}`.
- Outgoing, single method: `{projectName, modulePath: "CommonModules/MyModule/Module.bsl", methodName: "DoWork", direction: "outgoing"}`.
- Outgoing, whole module (methodName omitted): `{projectName, modulePath: "CommonModules/MyModule/Module.bsl", direction: "outgoing"}`.
- Outgoing with a custom ext-API prefix: `{projectName, modulePath: "CommonModules/MyModule/Module.bsl", direction: "outgoing", extApiPrefix: "PublicApi"}`.

## Notes & gotchas
- For `callers`/`callees`, `modulePath` must point at the module that DEFINES the method; if the method is not found there the tool returns a not-found response listing the module's methods. The same applies to a scoped `outgoing` call (with `methodName`).
- `callees` and `outgoing` list raw invocation names from the scanned body and do not resolve each target to its defining module.
- `extApiPrefix` matching is literal on the call qualifier text (e.g. `ПрограммныйИнтерфейсСервисаТовары` starts with the default prefix); it does not resolve or open the qualifying module.
- Requires a loadable BSL AST (EMF); a module that fails to parse returns an error pointing at the EDT Error Log.
- The `outgoing` mode is a clean-room implementation inspired by the idea behind edt-bridge's `edt_outgoing_calls` tool (Apache-2.0); no source was copied.

---
*Generated from the live MCP server (`get_tool_guide`) by `docs/generate_tool_docs.py`. Do not edit this file. Edit the tool's description/schema in its Java source and its guide body in `mcp/bundles/com.ditrix.edt.mcp.server/guides/<tool>.md`.*
