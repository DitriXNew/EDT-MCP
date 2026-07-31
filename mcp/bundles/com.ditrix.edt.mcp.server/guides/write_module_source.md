Writes BSL source to a single 1C metadata object module (a `.bsl` file under `src/`). Three edit modes, a mandatory BSL syntax check, and optional lost-update guards.

## When to use

- Editing existing BSL: prefer `searchReplace` (the default) — surgical and safe.
- Rewriting or creating a whole module: `replace` (the only mode that can create a new file).
- Adding code at the end of a module: `append`.

## Targeting the module (exclusive OR)

Pass EXACTLY ONE of:
- `modulePath` — direct `src/`-relative path, e.g. `Documents/MyDoc/ObjectModule.bsl` or `CommonModules/MyModule/Module.bsl`.
- `objectName` + (optional) `moduleType` — resolves the path for you.

Passing both is rejected; passing neither is rejected. `moduleType` is meaningful ONLY with `objectName` — combined with `modulePath` it is rejected, not silently ignored.

## Parameter details

| Param | When | Notes |
|---|---|---|
| `projectName` | always | EDT project name. |
| `modulePath` | XOR objectName | `src/`-relative `.bsl` path; no `..`. |
| `objectName` | XOR modulePath | `Type.Name`; see Bilingual. |
| `moduleType` | with objectName | default `ObjectModule`. |
| `source` | always | the BSL to write (max 500000 chars). |
| `oldSource` | mode=searchReplace | must match exactly once. |
| `methodName` | replaceMethod / insertBefore / insertAfter | the target/anchor method (case-insensitive). |
| `mode` | optional | `searchReplace` (default), `replaceMethod`, `insertBefore`, `insertAfter`, `replace`, `append`. |
| `formName` | moduleType=FormModule | except CommonForm. |
| `commandName` | moduleType=CommandModule | except CommonCommand. |
| `skipSyntaxCheck` | optional | default false. |
| `expectedSource` | mode=replace | lost-update guard. |
| `overwrite` | mode=replace | force without expectedSource. |
| `expectedHash` | any mode | cheap lost-update guard. |
| `dryRun` | optional | preview only — compute + syntax-check, do NOT write. Any mode. Default false. |

## moduleType to path

`ObjectModule` (default), `ManagerModule`, `RecordSetModule`, `Module` resolve to `<Dir>/<Name>/<moduleType>.bsl`. `FormModule` resolves to `<Dir>/<Name>/Forms/<formName>/Module.bsl` and REQUIRES `formName` — except CommonForm, which has no per-form name and resolves to `CommonForms/<Name>/Module.bsl`. `CommandModule` resolves to `<Dir>/<Name>/Commands/<commandName>/CommandModule.bsl` and REQUIRES `commandName` — except CommonCommand, which resolves to `CommonCommands/<Name>/CommandModule.bsl`.

## Modes

- `searchReplace` (default): finds `oldSource` and replaces it with `source`. `oldSource` is REQUIRED and must match EXACTLY ONE location — zero matches or multiple matches are rejected with a steer to read again / give a larger fragment. The match runs on the raw file content (trailing newline preserved), so a fragment ending at EOF including its final newline is found. The file must already exist.
- `replaceMethod`: swaps a WHOLE method by `methodName` (case-insensitive) — no need to quote the old body as `oldSource`. The replaced span is the method's full definition INCLUDING its leading doc-comment block, so `source` should be the complete new method (add your own doc-comment if you want one). If the method is not found, the error lists the module's available method names. The file must already exist. Ideal for a `code_review` fix: read the method, hand back the corrected method.
- `insertBefore` / `insertAfter`: splice `source` in just BEFORE (ahead of its leading doc-comment) or just AFTER the `methodName` anchor method — the way to add a NEW method next to an existing one. `source` is inserted verbatim, so include your own blank line(s) for separation. Same not-found error as replaceMethod; the file must already exist.
- `replace`: replaces the entire file. The ONLY mode that can CREATE a new module (creates parent folders). Over an EXISTING module it is guarded (see Lost-update guards).
- `append`: adds `source` to the end. The file must already exist.

## Lost-update guards

Concurrent edits between your read and write are caught by:
- `expectedHash` (ANY mode): pass the opaque `contentHash` from your last `read_module_source` / `read_method_source`. If the module changed, the write is rejected. Cheapest (a fixed-size token, not the whole file). Ignored when creating a new module.
- `expectedSource` (mode=replace): pass the exact content you last read. Mismatch is rejected.
- `overwrite=true` (mode=replace): force the overwrite with no content check.
A bare `replace` over an existing module with none of these is rejected and steers you toward expectedSource / overwrite / searchReplace. A matching `expectedHash` already satisfies the replace precondition. All comparisons are `\n`-normalized, so a CRLF/LF-only difference is not a spurious mismatch.

## BSL syntax check

Before writing, the resulting content is checked for balanced block keywords (Procedure/EndProcedure, Function/EndFunction, If/EndIf, While/EndDo, For/EndDo, Try/EndTry). On error the write is BLOCKED and the errors are returned. Pass `skipSyntaxCheck=true` to force.

## Preview (dryRun)

Pass `dryRun: true` to run the whole pipeline — resolve the module, apply the lost-update guards, compute the resulting content, run the BSL syntax check — but STOP before writing. The response carries `status: preview`, `written: false`, the `linesBefore`/`linesAfter` counts, the `syntaxCheck` result, and the would-be module content (capped at 400 lines). Nothing on disk or in the model changes. Use it to review a fix (e.g. a `code_review` remediation) before committing it, or to confirm a `searchReplace` matches and stays syntactically valid. Works with every mode. A dry run that fails a guard or the syntax check returns the SAME error a real write would — so a green preview means the real write will succeed.

## Bilingual (ru/en)

`objectName` resolves by the object's programmatic `Name`, NOT by its synonym. Only the TYPE token may be bilingual: the English `Document.MyDoc` and its Russian equivalent (the Cyrillic type token plus the SAME programmatic Name) resolve to the same module. Resolve by Name, never by synonym.

## Extension method interception (annotations)

In a configuration EXTENSION you intercept a base module METHOD by writing an annotated procedure. This is plain BSL, so `write_module_source` handles it directly - the annotation passes through verbatim (the syntax check only balances block keywords, it does not touch annotations). This is the METHOD counterpart of the form-EVENT interception that `create_metadata`'s `callType` produces; methods use annotations, events use `form:EventHandlerExtension`.

Annotation over the extending procedure, naming the BASE method in quotes:
- `&Before("BaseMethod")` - run before the base method.
- `&After("BaseMethod")` - run after the base method.
- `&Around("BaseMethod")` - run instead of / wrapping the base method (1C "Вместо"; can call `ПродолжитьВызов`).
- `&ChangeAndValidate("BaseMethod")` - 1C "ИзменениеИКонтроль".

The keywords serialize in ENGLISH on disk (`&Before`/`&After`/`&Around`/`&ChangeAndValidate`); the Russian `&Перед`/`&После`/`&Вместо`/`&ИзменениеИКонтроль` are editor display aliases for the same annotations. Note: the METHOD "Вместо" annotation is `&Around` (a method wrapper), which is distinct from the form-EVENT "Instead" call type (`create_metadata` `callType=Instead`, serialized as the `Override` call type) - methods and events use different mechanisms.

Preconditions for a clean `get_project_errors`:
1. The host extension module must EXIST - adopt the base object/form into the extension first (`adopt_metadata_object`), or create the extension common module via `create_metadata`. `write_module_source` writes `.bsl` text only; it does not adopt the module object. `mode=replace` can create a missing file, `searchReplace`/`append` need it to exist.
2. The BASE method must exist in the parent configuration with a matching signature.
3. Run `get_project_errors` after writing to confirm the extension method resolved.

## Examples

Surgical edit (default mode):
```
{ "projectName": "MyProj", "modulePath": "CommonModules/MyModule/Module.bsl",
  "oldSource": "Return 1;", "source": "Return 2;" }
```

Replace a whole method by name (e.g. a code_review remediation):
```
{ "projectName": "MyProj", "modulePath": "CommonModules/Calc/Module.bsl",
  "mode": "replaceMethod", "methodName": "Test",
  "source": "Процедура Test() Экспорт\n\tAddend = 2;\n\tAdd(1, Addend);\nКонецПроцедуры" }
```

Add a new method after an existing one:
```
{ "projectName": "MyProj", "modulePath": "CommonModules/Calc/Module.bsl",
  "mode": "insertAfter", "methodName": "Add",
  "source": "\nФункция Sub(A, B) Экспорт\n\tВозврат A - B;\nКонецФункции\n" }
```

Form module via objectName:
```
{ "projectName": "MyProj", "objectName": "Document.MyDoc",
  "moduleType": "FormModule", "formName": "ItemForm",
  "mode": "replace", "source": "...", "overwrite": true }
```

Extension method interception (append an annotated procedure to an adopted extension common module):
```
{ "projectName": "MyExt", "objectName": "CommonModule.Calc", "moduleType": "Module",
  "mode": "append",
  "source": "\n&After(\"Add\")\nProcedure ext_AddAfter(A, B, Result) Export\n\t// runs after CommonModule.Calc.Add\nEndProcedure\n" }
```

Preview a fix before committing (no write):
```
{ "projectName": "MyProj", "modulePath": "CommonModules/MyModule/Module.bsl",
  "oldSource": "Result = Add(1, 2);", "source": "Addend = 2;\n\tAdd(1, Addend);",
  "dryRun": true }
```

## Gotchas

- Only `.bsl` files; `modulePath` may not contain `..`.
- `dryRun: true` writes nothing (any mode); the response is `status: preview` with the would-be content. Drop it (or set false) to actually write.
- `searchReplace`/`append` need an EXISTING file; only `replace` creates one.
- New BSL files are written with a UTF-8 BOM; existing files keep their BOM state.
- `source` is `\r\n`->`\n` normalized and the file always ends with a newline.
