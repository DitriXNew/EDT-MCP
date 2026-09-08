# write_module_source

Create or edit BSL source in a metadata module. Parameters and examples: get_tool_guide('write_module_source').

## Parameters
| Parameter | Required | Type | Description |
| --- | --- | --- | --- |
| projectName | yes | string | EDT project name (required) |
| modulePath | — | string | src/-relative module path, e.g. 'CommonModules/MyModule/Module.bsl'. Alternative to objectName (pass exactly one). |
| objectName | — | string | Object name 'Type.Name', e.g. 'Document.MyDoc'. Resolved with moduleType. Alternative to modulePath. |
| moduleType | — | string (one of: ObjectModule, ManagerModule, FormModule, CommandModule, RecordSetModule, Module) | Module type for objectName resolution (default ObjectModule). |
| source | yes | string | BSL source to write (required): full file for replace, new fragment for searchReplace, text to add for append, or exactly one complete method for a method-targeted mode. |
| oldSource | — | string | Fragment to find and replace; required for searchReplace, must match exactly once. |
| mode | — | string (one of: searchReplace, replace, append, replaceMethod, insertBefore, insertAfter) | Write mode (default searchReplace). |
| methodName | — | string | Existing anchor method; required for replaceMethod, insertBefore, and insertAfter. |
| formName | — | string | Form name; required when moduleType=FormModule (e.g. 'ItemForm'). |
| commandName | — | string | Command name; required when moduleType=CommandModule (e.g. 'FillByTemplate'). |
| skipSyntaxCheck | — | boolean | Skip the BSL syntax check (default false). |
| normalizeInvalidCharacters | — | boolean | Replace the characters the 1C standard InvalidCharacterInFile forbids in the SOURCE you supply - en/em/figure dash, horizontal bar and typographic minus become '-', a no-break space becomes a space, a soft hyphen is dropped (default true). They are invisible in a diff and read exactly like their ASCII twins, so they otherwise land in the module as a marker. Set false to write the source byte-for-byte. |
| expectedSource | — | string | Lost-update guard for mode=replace: the module content you last read; mismatch rejects. |
| overwrite | — | boolean | Force mode=replace over an existing module without an expectedSource check (default false). |
| expectedHash | — | string | Lost-update guard: the hash from the read that produced your edit. The write is rejected if the module changed since; required for every method-targeted mode. |

## Guide
Writes BSL source to a single 1C metadata object module (a `.bsl` file under `src/`). Six edit modes, a mandatory BSL syntax check, and lost-update guards.

## When to use

- Editing existing BSL: prefer `searchReplace` (the default) — surgical and safe.
- Replacing one complete procedure/function: `replaceMethod`.
- Adding one complete procedure/function next to an existing anchor: `insertBefore` or `insertAfter`.
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
| `source` | always | the BSL to write (max 500000 chars); exactly one complete method for a method-targeted mode. |
| `oldSource` | mode=searchReplace | must match exactly once. |
| `mode` | optional | `searchReplace` (default), `replace`, `append`, `replaceMethod`, `insertBefore`, `insertAfter`. |
| `methodName` | method-targeted modes | existing unambiguous method anchor; REQUIRED. |
| `formName` | moduleType=FormModule | except CommonForm. |
| `commandName` | moduleType=CommandModule | except CommonCommand. |
| `skipSyntaxCheck` | optional | default false. |
| `expectedSource` | mode=replace | lost-update guard. |
| `overwrite` | mode=replace | force without expectedSource. |
| `expectedHash` | any mode | cheap lost-update guard; REQUIRED for all method-targeted modes. |

## moduleType to path

`ObjectModule` (default), `ManagerModule`, `RecordSetModule`, `Module` resolve to `<Dir>/<Name>/<moduleType>.bsl`. `FormModule` resolves to `<Dir>/<Name>/Forms/<formName>/Module.bsl` and REQUIRES `formName` — except CommonForm, which has no per-form name and resolves to `CommonForms/<Name>/Module.bsl`. `CommandModule` resolves to `<Dir>/<Name>/Commands/<commandName>/CommandModule.bsl` and REQUIRES `commandName` — except CommonCommand, which resolves to `CommonCommands/<Name>/CommandModule.bsl`.

## Modes

- `searchReplace` (default): finds `oldSource` and replaces it with `source`. `oldSource` is REQUIRED and must match EXACTLY ONE location — zero matches or multiple matches are rejected with a steer to read again / give a larger fragment. The match runs on the raw file content (trailing newline preserved), so a fragment ending at EOF including its final newline is found. The file must already exist.
- `replace`: replaces the entire file. The ONLY mode that can CREATE a new module (creates parent folders). Over an EXISTING module it is guarded (see Lost-update guards).
- `append`: adds `source` to the end. The file must already exist.
- `replaceMethod`: replaces the complete `methodName` definition. Its span includes the contiguous `//` documentation and `&...` annotations/directives immediately above the declaration. `source` must declare exactly one complete procedure/function with the same name; rename is rejected.
- `insertBefore`: inserts the one complete method in `source` before the anchor's documentation/annotations, never between an annotation and its declaration - a blank line between them does not detach the directive.
- `insertAfter`: inserts the one complete method in `source` after the anchor's complete terminator.

For all three method-targeted modes, `methodName` and `expectedHash` are REQUIRED. The anchor must resolve to exactly one declaration; duplicate declarations in preprocessor branches are rejected as ambiguous. For insert modes, the incoming method name must not already exist anywhere in the module, so repeating an insert is refused instead of creating a duplicate.

The method parser recognizes `Procedure`/`Function` and `Процедура`/`Функция`, with matching `EndProcedure`/`EndFunction` and `КонецПроцедуры`/`КонецФункции` terminators. A terminator must end on a keyword boundary: identifiers such as `EndProcedureResult` and `КонецПроцедурыРезультат` do not close a method, and a terminator must own its line - `EndProcedure; ModuleValue = Call();` is module-level code after the method, not a method end. An unterminated method is reported incomplete rather than borrowing the next method terminator.

## Lost-update guards

Concurrent edits between your read and write are caught by:
- `expectedHash` (ANY mode; REQUIRED for method-targeted modes): pass the opaque `contentHash` from your last `read_module_source` / `read_method_source`. If the module changed, the write is rejected. Cheapest (a fixed-size token, not the whole file). Omit it when creating a new module, because there is no existing content to match.
- `expectedSource` (mode=replace): pass the exact content you last read. Mismatch is rejected.
- `overwrite=true` (mode=replace): force the overwrite with no content check.
A bare `replace` over an existing module with none of these is rejected and steers you toward expectedSource / overwrite / searchReplace. A matching `expectedHash` already satisfies the replace precondition. All comparisons are `\n`-normalized, so a CRLF/LF-only difference is not a spurious mismatch.

## BSL syntax check

Before writing, the resulting content is checked for balanced block keywords (Procedure/EndProcedure, Function/EndFunction, If/EndIf, While/EndDo, For/EndDo, Try/EndTry). On error the write is BLOCKED and the errors are returned. Pass `skipSyntaxCheck=true` to force.

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

Replace one method using the revision token returned by a read:
```
{ "projectName": "MyProj", "modulePath": "CommonModules/MyModule/Module.bsl",
  "mode": "replaceMethod", "methodName": "Calculate",
  "expectedHash": "<contentHash from read_method_source>",
  "source": "Function Calculate() Export\n    Return 2;\nEndFunction\n" }
```

Insert a new method before an existing anchor:
```
{ "projectName": "MyProj", "modulePath": "CommonModules/MyModule/Module.bsl",
  "mode": "insertBefore", "methodName": "Calculate",
  "expectedHash": "<contentHash from read_module_source>",
  "source": "Procedure Prepare() Export\nEndProcedure\n" }
```

## Gotchas

- Only `.bsl` files; `modulePath` may not contain `..`.
- `searchReplace`/`append` and all method-targeted modes need an EXISTING file; only `replace` creates one.
- New BSL files are written with a UTF-8 BOM; existing files keep their BOM state.
- `source` is `\r\n`->`\n` normalized and the file always ends with a newline.

## Addressing the module

`modulePath` and the `objectName` + `moduleType` pair are mutually exclusive - give one shape or the other, never both.

---
*Generated from the live MCP server (`get_tool_guide`) by `docs/generate_tool_docs.py`. Do not edit this file. Edit the tool's description/schema in its Java source and its guide body in `mcp/bundles/com.ditrix.edt.mcp.server/guides/<tool>.md`.*
