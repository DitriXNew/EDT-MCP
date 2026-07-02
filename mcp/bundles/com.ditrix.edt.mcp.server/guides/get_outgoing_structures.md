Best-effort read tool that, for each OUTGOING qualified call in a module (or one method), reports the top-level LITERAL keys of the `Структура` (`Structure`) passed as that call's argument. It is the key-level companion to the aggregated outgoing-call view: where a call-hierarchy answers "what does this module call", `get_outgoing_structures` answers "what keys did it put into the structure it handed to that call".

## When to use
- Reviewing an integration/API boundary: see which structure keys a module actually forwards to another module's method (`Модуль.Метод(Параметры)`), without reading every line by hand.
- Auditing a caller before you change the callee's expected structure contract.
- Prefer it over a plain text search: it reads the resolved BSL AST (the `.Вставить`/`.Insert` calls on the argument variable), not fragile string matching.

This is a HEURISTIC, best-effort tool. It is flow-insensitive and reports LITERAL keys only. It never claims completeness — see "Honest limits" below. When a key source is anything it cannot read reliably, it marks that record `partial: true` and keeps going rather than guessing.

## Parameters
- `projectName` (required) - EDT project name.
- `modulePath` (required) - path from the project's `src/` folder to the BSL module to scan, e.g. `CommonModules/MyModule/Module.bsl` or `Documents/SalesOrder/ObjectModule.bsl`. Addressing is by module path only (there is no fqn / moduleType parameter — this matches `get_method_call_hierarchy`).
- `method` (optional) - restrict the scan to a single procedure/function (resolved case-insensitively by programmatic Name via the same finder as `get_method_call_hierarchy`). Omit it to scan the whole module. An unknown method name returns a not-found response listing the module's methods.
- `qualifier` (optional) - keep only calls whose qualifier (the variable/module name before the dot, e.g. `Модуль` in `Модуль.Метод(...)`) matches this value (prefix or exact). Use it to narrow to one target module.
- `limit` (optional) - max structure records returned; default 100, max 500 (clamped). When the record cap is hit, `truncated` is `true`.

## How keys are collected
For each outgoing qualified call `<var>.Метод(Аргумент)` the tool identifies the argument variable, then, in the SAME method, gathers every `<Аргумент>.Вставить("ключ", …)` / `<Аргумент>.Insert("key", …)` whose first argument is a clean single-part string literal - those become the reported keys. It additionally expands a same-module seed/template helper ONE level: if the argument was produced by `<Аргумент> = Хелпер(...)` where `Хелпер` is a function IN THIS module that returns a `Структура`, the keys inserted inside that helper are added and the record is flagged `viaHelper: true`.

## Output
`ResponseType.JSON` success envelope (the payload is in `structuredContent`):
```
{
  "success": true,
  "structures": [
    {
      "qualifier": "Модуль",      // omitted for a local (unqualified) call
      "method": "СформироватьЗаказ",
      "line": 42,                  // the call-site line
      "arg": "Параметры",         // the argument variable name (optional)
      "keys": ["Дата", "Сумма"],
      "viaHelper": true,           // only when a one-level helper contributed keys
      "partial": true              // only when some key source was unreliable
    }
  ],
  "structureCount": 1,
  "truncated": false
}
```
On error the envelope is `{"success": false, "error": "<message>"}` and the protocol layer marks the result `isError`.

## Honest limits (read before trusting the output)
This tool trades completeness for cheapness and safety. It deliberately does NOT do data-flow analysis. Concretely:
- **Literal keys only.** A key computed at runtime (`П.Вставить(ИмяКлюча, …)` with a variable/expression name) is skipped and the record is marked `partial: true`.
- **Flow-insensitive.** Every `.Вставить`/`.Insert` on the argument variable ANYWHERE in the method is collected, regardless of branches, loops, or ordering; a key added only on one code path is still reported.
- **One-level helper only.** A same-module seed helper (`<var> = Хелпер(...)` returning a `Структура`) is expanded exactly ONE level. A helper that itself calls another helper is NOT followed; an EXTERNAL (other-module) or unknown helper is not read and the record is marked `partial: true`.
- **`Новый Структура("a,b")` is not parsed.** Keys passed in the `Структура`/`Structure` constructor's property-name string are treated as unreliable and skipped, and the record is marked `partial: true` (only per-key `.Вставить`/`.Insert` literals are reported).
- **Multi-part / multi-line string literals** (concatenated or spanning lines) are treated as unreliable: that key is skipped and the record is marked `partial: true`.
- A record with `partial: true` means "the reported `keys` are a LOWER BOUND — there is at least one key source here I could not read reliably". Absence of `partial` does NOT prove the structure has no other keys added elsewhere by means this tool does not model.

## Notes & gotchas
- Read-only: it never mutates the project or the model, and never blocks on a modal (unattended-safe).
- The only Cyrillic the tool matches is the BSL keyword itself - `Вставить` / `Insert` are compared case-insensitively (both dialects), which is legitimate 1C data, not English prose.
- Requires a loadable BSL AST (EMF); a module that fails to parse returns an error pointing at the EDT Error Log.

## Attribution (clean-room)
The idea for this key-extraction view is inspired by the `edt_outgoing_structures` tool of the `keyfire/edt-bridge` project (Apache License 2.0). This implementation was written clean-room against the public EDT BSL AST model - no source was copied. Credit to keyfire/edt-bridge for the concept.
