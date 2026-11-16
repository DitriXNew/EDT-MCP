Read persisted EDT performance measurements as Markdown. The default is a bounded summary, not the old per-module JSON dump. No output schema or structuredContent is emitted.

## Select a measurement

Use `{"mode":"list","limit":50,"offset":0}` for names, UUIDs, session dates, infobases, total time and debug targets, newest first. `measurementId` selects an exact UUID; otherwise the latest session date wins (UUID breaks ties). A missing explicit UUID is an error. An empty store gives an actionable no-results response.

`applicationId` selects only the `profilingActive` flag. It never selects measurement data. Measurements persist across EDT restarts. Every read identifies its measurement and states the time unit.

## Summary and lines

`{"measurementId":"<uuid>","top":10}` returns total and pure time, client/server/other time, module/method/line-record counts, debug targets and infobase. Tops show lines by pure time, call count and server-call signal, plus methods/modules by own time. Line rows include calls, average pure time per call and shortened source. Tops say `showing N of M`; paged lists say `Showing A-B of M.` and offer the next offset only while more rows remain.

`moduleFilter` and `methodFilter` are case-insensitive substrings; `minFrequency` defaults to 1 (0 includes all records). Filters narrow summary tops and line pages; header totals remain for the full measurement. `top` is 1..20, default 10.

`{"mode":"lines","measurementId":"<uuid>","moduleFilter":"tests_SampleTests","limit":50,"offset":0,"responseFormat":"detailed"}` sorts by pure time descending. Pages have `limit` 1..100 (default 50) and nonnegative `offset`; empty pages are allowed. `concise` (default) omits method/source columns. Detailed source is shortened at 100 characters with an explicit notice; export keeps full text. Each record belongs to one debug target; there is no per-module line cap.

Unresolved workspace modules use stable identities: project, module object ID and property ID. Their method/source cells show `n/a`; a header reports how many line records are affected (for example, extensions installed only in the infobase). Filters also match these module identities.

## Units and aggregation

Times use **ms** with three decimals (microsecond precision); percentages use two decimals. EDT's line `getDurability()`/`getPureDurability()` and measurement totals are performance ticks. `LineProfilingResult` converts ticks using the measurement's performance frequency into `Duration`; the tool sums those normalized per-target durations. EDT comparison element `getTime`/`getPureTime` values are nanoseconds, matching the UI's `Duration.of(..., NANOS)` formatting. Raw comparison totals still hold ticks and are not printed as durations.

Own (pure) method/module time is the sum of pure line times, following EDT's `MethodStatistic`. Inclusive line sums can double-count nested calls and recursion; they are labeled accordingly. Rollup line calls are sums of line calls, not method invocations. EDT records lines, not calls, so method rows estimate **Invocations (est.)** as the calls of the method's first recorded line: exact unless that line is a loop header, which counts every iteration. Client types include desktop/web/mobile clients and COMConnector; server types include servers, services, jobs and file-mode server emulation. Unknown targets appear as Other.

## Callers

EDT stores no call graph. `{"mode":"callers","methodFilter":"FillCheckProcessing"}` finds the source lines that call a method: the name followed by `(`, matched case-insensitively on the line text, so a same-named method of another module matches too; an unqualified call inside a same-named method (recursion) is left out, a qualified `Module.Name(` call there is kept. Rows are sorted by **inclusive** time, which includes the callee and everything it calls; pure time is the call line's own. `methodFilter` is required here (a signature such as `Post(Cancel) Export` also works); `moduleFilter` narrows the callers' module, `minFrequency` their calls, and `limit`/`offset` page them.

## Compare

`{"mode":"compare","baseId":"<before-uuid>","comparedId":"<after-uuid>","top":10}` uses EDT's comparison service. `baseId` is required. `comparedId` defaults to `measurementId`, then latest. Headers show base → compared total/pure times and changes in ms and percent, including each side. A zero baseline has an undefined percentage (`n/a`).

The method rankings use absolute **own-time change in ms**, not percentage. Unmatched elements are counted and excluded from rankings. With either filter supplied, compared line records are paged with calls/pure time on both sides. Missing-side getters are never invoked on unmatched elements; their unavailable values are explicit. Methods match by name; lines match by number plus trimmed source text, so edits can shift lines and prevent matching.

## Export and import

Use the separate write tool `manage_profiling_results` for full CSV/native exports or native imports. It requires its own client permission, a writable preset and consent. This read tool only supports `summary`, `list`, `lines`, `callers` and `compare`. Its preset membership follows the Debugging group.

## Workflow

Resolve a debug target, start profiling, execute the bounded scenario, then `stop_profiling` with an optional unique name. Retain its observed `measurementId`; read that exact UUID. Repeat for a controlled comparison, then export if complete line data is needed. Stopping may time out waiting for asynchronous collection; use `list` to check later and do not assume an old measurement belongs to the run.
