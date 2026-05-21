# Safe BSL writing via `write_module_source`

> The most dangerous area. Choosing the wrong mode can **overwrite the entire module** or lose part of the code. Read this file **before** the first write.

## Available modes

`write_module_source` supports three modes (`mode`):

| Mode | What it does | When to use |
|---|---|---|
| `searchReplace` (default) | Finds `oldSource` in the file and replaces it with `source`. Safe — only affects what was found | **Default for any targeted edit** |
| `append` | Appends `source` to the end of an existing module | When adding a new method/section at the end |
| `replace` | **Overwrites the entire module file** with `source` | Creating a new module or a full rewrite |

Server-enforced guarantees:
- For `searchReplace`, `oldSource` must occur in the file **exactly once**. Zero matches → operation is rejected (`oldSource not found`); more than one → also rejected with a hint to use a larger, more specific fragment. This check protects you from replacing the wrong location.
- `append` and `searchReplace` require the file to already exist. A new `.bsl` file can be created **only** via `replace`.
- Before writing, `\r\n` is normalized to `\n`. UTF-8 BOM is preserved (and added for new files).
- `source` size limit: 500,000 characters.
- `modulePath` may not contain `..`; only `.bsl` paths are accepted.

## Rule #1: NEVER use `replace` for a single method

`replace` replaces **the entire module file**. If you pass only one method — all other code (hundreds/thousands of lines) will be deleted with no rollback option.

For a single method — use `searchReplace`: put the existing method text into `oldSource` (must match exactly one location in the file), and the new version into `source`. If the method is long — split the edit into logical blocks and make several calls.

## Rule #2: Inspect the state before live writes

1. Read the method via `read_method_source` (or a range via `read_module_source`) — you will see the current state.
2. Via `get_module_structure` estimate the module size (number of procedures/functions, line ranges).
3. Only after that, form the `write_module_source` call.

`oldSource` in `searchReplace` also serves as **proof** that you have read the current file content. If the file has changed, the call will fail — and that is correct behavior.

## Rule #3: When `replace` is acceptable

- The module is being created **for the first time** (the file does not yet exist — only `replace` can create it).
- The module is empty or fewer than ~50 lines, and you are passing its complete content.
- A full rewrite of the module **after explicit agreement with the user**.

## Selecting the module: `modulePath` vs `objectName + moduleType`

You can specify either `modulePath` (path relative to `src/`, e.g. `Documents/MyDoc/ObjectModule.bsl`) or the pair `objectName + moduleType`. If `modulePath` is given, it takes precedence.

`moduleType` values (used with `objectName`):
- `ObjectModule` (default for most types: catalogs, documents, etc.)
- `ManagerModule`
- `RecordSetModule` (for registers)
- `FormModule` — `formName` is **required** (e.g. `ItemForm`); for `CommonForm` `formName` is not needed.
- `CommandModule` — `commandName` is **required**; for `CommonCommand` `commandName` is not needed.

Special `moduleType` defaults (when not specified explicitly):
- `CommonModule`, `CommonForm`, `WebService`, `HTTPService` → internally resolve to `Module.bsl`.
- `CommonCommand` → `CommandModule.bsl`.
- Everything else → `ObjectModule.bsl`.

When editing a common module, it is simpler to pass `modulePath: CommonModules/<Name>/Module.bsl` directly, rather than rely on the default resolution logic.

## What the built-in syntax check verifies

By default `skipSyntaxCheck=false` — the server validates the **complete resulting file** (after the edit is applied) for **balanced BSL block keywords**:

- `Procedure` / `EndProcedure` (`Процедура` / `КонецПроцедуры`)
- `Function` / `EndFunction` (`Функция` / `КонецФункции`)
- `If` / `EndIf` (`Если` / `КонецЕсли`)
- `While` / `EndDo` (`Пока` / `КонецЦикла`)
- `For` / `EndDo` (`Для` / `КонецЦикла`)
- `Try` / `EndTry` (`Попытка` / `КонецПопытки`)

Case-insensitive; Russian and English are treated equally. The checker skips empty lines, whole-line `//` comments, and multiline string continuation lines (starting with `|`). It does **not** look inside expressions or call signatures — it is not a full parser, just a fast block-balance check.

What the check **will not catch**: a typo in a function name, wrong argument count, a call to a non-existent method, a type mismatch, an unescaped quote inside a string literal, a missing `;`. These errors will be reported by `get_project_errors` after the write.

Use `skipSyntaxCheck=true` only when the fast checker produces a false positive (rare) and you have manually verified that the block balance is actually correct.

## Algorithm for safe method edits

1. `read_method_source` — read the method. Note `startLine`/`endLine`.
2. Prepare the corrected full method code (from `Процедура`/`Функция` to `КонецПроцедуры`/`КонецФункции`, including directives `&НаКлиенте`, `&НаСервере`, `&НаСервереБезКонтекста`).
3. Call `write_module_source` with:
   - `mode: "searchReplace"`,
   - `oldSource` — the old method text exactly as it appears in the file (unique match),
   - `source` — your new version.
4. If the server returns `oldSource not found` or `multiple times` — **do not retry blindly**: re-read the file and either update `oldSource` or extend it so that it becomes unique.
5. After a successful write the block-balance check is already done by the server. Additionally call `get_project_errors` with the `objects` filter on the changed module to confirm that no semantic errors appeared.

## Algorithm for adding a new method

1. Via `get_module_structure` determine which region (`#Область ПрограммныйИнтерфейс` / `СлужебныйПрограммныйИнтерфейс` / `СлужебныеПроцедурыИФункции`) the method belongs to.
2. Option A (if the region is at the end of the module or the method is internal): `mode: "append"` — adds to the end of the file.
3. Option B (must insert in the middle or into a specific region): `mode: "searchReplace"`, find the region start marker / closing line — replace it with "marker + new method".

## What to do after writing

- If the server response contains block-balance errors — fix them **before** moving to the next edit.
- Run `get_project_errors` with filter `objects: ["<object FQN>"]` to confirm no new problems appeared.
- If there are multiple edits in a row — after the series call `get_problem_summary`.

## Rolling back a bad write

EDT-MCP has no built-in undo for `write_module_source`. Rollback relies on you remembering the previous content **before** the write.

1. **Before every `searchReplace`, keep both the `oldSource` you are about to send and the `source` (new version) in session context.** Do not discard them until you have confirmed the write is correct.
2. If the response shows the wrong fragment was replaced or the module structure broke — **immediately** issue a reverse `searchReplace`: pass the previous `source` as `oldSource` and the previous `oldSource` as `source`. The faster, the less chance a parallel EDT editor sees the broken file.
3. If a reverse `searchReplace` is no longer possible (the new `source` is not unique in the file, or the module went through `replace`) — **stop and ask the user**. Do not reconstruct the module "from memory"; there may be an open editor copy in EDT, and recovery via the UI is more reliable.
4. For `replace` mode the rule is the same: keep the full old text before sending the new one, otherwise rollback is impossible.

## What you absolutely must not do

- Do not edit `.bsl` files directly via `Edit`/`Write`/`Read+Write`. Only via `write_module_source` — otherwise EDT will not see the changes until the workspace is refreshed, and you lose the automatic check and line counting.
- Do not use `replace` without the full content of the new module.
- Do not pass truncated content to `write_module_source` hoping "the server will figure it out" — it will overwrite exactly what you sent.
- Do not set `skipSyntaxCheck=true` to "bypass" block-balance errors — this is almost always a symptom of a real bug in the code.
- Do not commit changes if `get_project_errors` shows new problems in the changed module.
