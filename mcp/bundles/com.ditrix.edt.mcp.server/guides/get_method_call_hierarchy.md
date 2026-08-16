Resolves a BSL method's call graph in one direction at a time using the semantic AST (resolved feature references), not plain text. Because matching is by the resolved method (not by literal spelling), it finds calls written in either the ru or en BSL dialect - unlike literal `search_in_code`.

## When to use
- `callers`: find every place that invokes a given procedure/function before renaming, changing its signature, or assessing impact.
- `callers` + `depth`: answer the transitive question - "if I change this method, what breaks 3-5 levels up the chain" - in ONE call instead of re-calling this tool for every method it returns.
- `callees`: list what a method itself calls, to understand its dependencies.
- `outgoing`: get an aggregated overview of the distinct call targets of a method (or of the whole module) - one row per `qualifier.method` with a call-site count, useful for spotting which external/service APIs a module depends on and how heavily.
- Prefer this over `search_in_code` for identifier lookup: text search is literal and not dialect-aware, so it misses the other-language spelling.

## Parameter details
- `projectName` (required) - EDT project name.
- `modulePath` (required) - path from the project's `src/` folder to the module that DEFINES the method, e.g. `CommonModules/MyModule/Module.bsl` or `Documents/SalesOrder/ManagerModule.bsl`.
- `methodName` - the procedure/function name; case-insensitive, matched by programmatic Name (not by synonym). Required for `callers` and `callees`. Optional for `outgoing`: omit it to aggregate the whole module; supply it to aggregate a single method's body.
- `direction` - `callers` (default) = who calls this method; `callees` = what this method calls; `outgoing` = aggregated distinct call targets. An unknown value returns an error.
- `extApiPrefix` - only used by `outgoing`. A literal call-qualifier prefix, compared case-insensitively against each target's qualifier token; a match flags the row as an external service API (`ExtAPI = yes`). This is a plain text match on the call qualifier (`Module` part), NOT a resolved-module lookup. Default: the conventional 1C region name `ПрограммныйИнтерфейсСервиса`.
- `depth` - how many call-chain levels to walk, for `direction=callers` ONLY. Default `1` = the single hop this tool has always done, with byte-for-byte the same output. Max `5`; a larger value is clamped (and the header says so), a value below 1 becomes 1. `depth>1` with `callees` or `outgoing` is an error, not a silent downgrade - see "Why depth is callers-only" below.
- `limit` - max rows returned; default 100, max 500 (clamped). For `outgoing` the limit clamps DISTINCT target rows. The reported total count is exact even when rows are truncated. **With `depth>1` its meaning changes**: it caps the number of UNIQUE callers ACCEPTED, and the walk stops there - so it bounds the WALK, not just the rendering. A walk cut by that budget cannot report a true total; it does not know one.

## Transitive callers (`depth` > 1)
The walk is breadth-first from the method you asked about. Each caller is reported ONCE, at the lowest level it is reachable at, so a caller reached along several paths does not repeat. The depth bound is what ends the walk; reporting once is what keeps a cycle or a re-converging graph to one row per caller instead of one row per path.

A row is a METHOD, or the module body when the call sits outside any method (`no-method`) - both are real callers, which is why the count is labelled callers.

Output is a flat table - `# | Level | Module | Method | Line | Via # | Flags`:
- `Level` - 1 = a direct caller, 2 = a caller of a caller, and so on.
- `Line` - the first call site inside that caller.
- `Via #` - the row number of the method that led here (`-` at level 1, i.e. reached from the analyzed method itself). Rows are in discovery order, so a parent always has a smaller number and you can walk `Via #` upwards to rebuild the whole chain. It is a row number rather than a `Module.Method` label because two metadata objects of different kinds can share a name.
- `Flags` - why a row was NOT expanded further: `depth-limit` (it sits at the requested depth - raise `depth` to look past it), `budget` (the `limit` ran out), `time-limit` (the time budget ran out), `no-method` (the call is in module-level code, so there is no method to ask about), `recursive` (it is the analyzed method itself, reached back through the graph).

The `Call Code` snippet is deliberately absent here: at a hundred rows the snippets are most of the payload, and the chain is what the transitive question needs. Ask again with `depth=1` for the call sites of any single method.

### Reading the header
- `Complete through depth N: yes` means nothing was missed **within the depth you asked for and among static invocations**. Rows flagged `depth-limit` are the boundary of that answer, not a gap in it - that is why reaching the depth limit does not make the result incomplete.
- `Complete through depth N: no` names the reason. The important distinction: when the node or time budget cut the walk, the true number of callers is **unknown**, not merely unshown - the methods that were never expanded may have any number of callers behind them. A file that could not be read, a module that could not be loaded, and a module whose parse could not be VOUCHED FOR (the parser recovered from a syntax error, or there was no parse evidence to consult - either way a call inside it may never have reached the syntax tree this search walks) all make the result incomplete too, because a caller could have been hiding in any of them.
- `Repeat edges collapsed: N` counts edges that pointed at an already-listed caller. It is graph re-convergence (which includes recursion), and nothing was lost.

### Cost
One level is ONE pass over the project's `.bsl` sources testing every method of that level at once, so a `depth=5` walk costs about five passes - not one pass per discovered method, which is what emulating the recursion from the client side costs. That is the whole reason to ask for depth here instead of looping outside.

### Why depth is callers-only
`callees` and `outgoing` report the raw invocation names they find; neither resolves a call to the module that DEFINES it. Recursing them would mean inventing that resolution, and its failure mode is the worst kind - a confident dependency graph that is quietly wrong. So `depth>1` with those directions is refused with an error that points at `callers`.

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
- Callers, `depth=1` (default): # / Module / Method / Line / Call Code.
- Callers, `depth>1`: # / Level / Module / Method / Line / Via # / Flags (one row per unique caller) - see "Transitive callers" above.
- Callees: # / Called Method / Line / Call Code. Long or multi-line call expressions are compacted (comment lines stripped, body collapsed to `Name(...)`).
- Outgoing: Qualifier / Method / Count / First line / ExtAPI (one row per distinct target, first-seen order).

## Examples
- Callers (default): `{projectName, modulePath: "CommonModules/MyModule/Module.bsl", methodName: "DoWork"}`.
- Transitive impact before a signature change: `{projectName, modulePath: "CommonModules/MyModule/Module.bsl", methodName: "DoWork", depth: 4}`.
- Transitive on a hub method, with a wider budget: `{projectName, modulePath: "...", methodName: "DoWork", depth: 3, limit: 300}`.
- Callees: `{projectName, modulePath: "CommonModules/MyModule/Module.bsl", methodName: "DoWork", direction: "callees"}`.
- Outgoing, single method: `{projectName, modulePath: "CommonModules/MyModule/Module.bsl", methodName: "DoWork", direction: "outgoing"}`.
- Outgoing, whole module (methodName omitted): `{projectName, modulePath: "CommonModules/MyModule/Module.bsl", direction: "outgoing"}`.
- Outgoing with a custom ext-API prefix: `{projectName, modulePath: "CommonModules/MyModule/Module.bsl", direction: "outgoing", extApiPrefix: "PublicApi"}`.

## Notes & gotchas
- **STATIC invocations only.** A call made dynamically - `Execute`/`Eval` over a built string, a handler named by string in metadata, platform dispatch - is invisible to this tool in every direction and at every depth. `Complete: yes` therefore means "complete for what a static scan can see", NOT "nothing else calls this method". Treat the result as a lower bound on impact and check dynamic dispatch separately.
- The search covers `<project>/src` of the ONE project named in `projectName`. A caller living in a different project (an extension over this configuration, or a configuration this one is extended by) is not searched.
- For `callers`/`callees`, `modulePath` must point at the module that DEFINES the method; if the method is not found there the tool returns a not-found response listing the module's methods. The same applies to a scoped `outgoing` call (with `methodName`).
- `callees` and `outgoing` list raw invocation names from the scanned body and do not resolve each target to its defining module.
- `extApiPrefix` matching is literal on the call qualifier text (e.g. `ПрограммныйИнтерфейсСервисаТовары` starts with the default prefix); it does not resolve or open the qualifying module.
- Requires a loadable BSL AST (EMF). If the module you ASKED about cannot be loaded the call returns an error pointing at the EDT Error Log. A module met while SEARCHING is different: it is counted, the search continues, and the transitive header reports the result as not complete (a `depth=1` call has no such header and simply skips it).
- The `outgoing` mode is a clean-room implementation inspired by the idea behind edt-bridge's `edt_outgoing_calls` tool (Apache-2.0); no source was copied.
