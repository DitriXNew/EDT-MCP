Edit one metadata object module (`.bsl` under `src/`) using nine modes, syntax validation and lost-update guards.

## When to use

- `searchReplace` (default): edit existing BSL.
- `replaceAll`: replace every whole-word occurrence of a name or fragment in the module, e.g. a renamed variable or a called procedure.
- `replaceMethod`: replace one complete procedure/function.
- `insertBefore` / `insertAfter`: add a complete procedure/function next to an anchor.
- `replace`: rewrite/create a whole module; only this mode can create a file.
- `append`: add code at the end.
- `deleteMethods`: delete named procedures/functions.
- `deleteModule`: remove a blank object/manager module file, without removing its metadata owner.
- `fromModulePath` + `methodNames`: copy named methods from another module without sending their text (see Moving methods between modules).

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
| `source` | unless fromModulePath, deleteMethods or deleteModule | the BSL to write (max 500000 chars); exactly one complete method for a method-targeted mode. |
| `fromModulePath` | to copy methods | `src/`-relative `.bsl` module of the same project; replaces `source`. |
| `methodNames` | with fromModulePath or deleteMethods | array of up to 100 method names, each once. |
| `oldSource` | mode=searchReplace or replaceAll | searchReplace: must match exactly once; replaceAll: every whole-word occurrence is replaced. |
| `mode` | optional | `searchReplace` (default; `append` with fromModulePath), `replace`, `append`, `replaceMethod`, `insertBefore`, `insertAfter`, `deleteMethods`, `replaceAll`, `deleteModule`. |
| `methodName` | method-targeted modes | existing unambiguous method anchor; REQUIRED. |
| `formName` | moduleType=FormModule | except CommonForm. |
| `commandName` | moduleType=CommandModule | except CommonCommand. |
| `skipSyntaxCheck` | optional | default false. |
| `normalizeInvalidCharacters` | optional | Normalize forbidden characters in supplied source only; default true. |
| `expectedSource` | mode=replace | lost-update guard. |
| `overwrite` | mode=replace | force without expectedSource. |
| `expectedHash` | any mode | cheap lost-update guard; REQUIRED for all method-targeted modes, deleteMethods and deleteModule. |

## moduleType to path

`ObjectModule` (default), `ManagerModule`, `RecordSetModule`, `Module` resolve to `<Dir>/<Name>/<moduleType>.bsl`. `FormModule` resolves to `<Dir>/<Name>/Forms/<formName>/Module.bsl` and REQUIRES `formName` — except CommonForm, which has no per-form name and resolves to `CommonForms/<Name>/Module.bsl`. `CommandModule` resolves to `<Dir>/<Name>/Commands/<commandName>/CommandModule.bsl` and REQUIRES `commandName` — except CommonCommand, which resolves to `CommonCommands/<Name>/CommandModule.bsl`.

## Modes

- `searchReplace` (default): finds `oldSource` and replaces it with `source`. `oldSource` is REQUIRED and must match EXACTLY ONE location — zero matches or multiple matches are rejected with a steer to read again / give a larger fragment. The match runs on the raw file content (trailing newline preserved), so a fragment ending at EOF including its final newline is found. The file must already exist.
- `replaceAll`: replaces EVERY whole-word occurrence of `oldSource` with `source` (an empty `source` deletes them). The match is literal and case-sensitive on the same text `searchReplace` matches, so the spelling `search_in_code` finds is the spelling replaced; left to right, non-overlapping, and the replacement is never scanned again. Whole word: an occurrence counts only when no letter (any script), digit or `_` touches an edge of it that is itself such a character - `OldName` is replaced in `OldName.Run()`, `(OldName)` and `OldName;`, never in `OldNameX`, `XOldName` or `Old_Name1`; a fragment such as `Old.Run(` is checked on its identifier edge only. Comments and string literals are text like any other and ARE replaced. BSL ignores letter case, so a whole-word spelling that differs only by case is REPORTED in the `warning`, never replaced - repeat the call with that spelling when it is the same identifier. The response carries `replacements` (the count) and `replacedLines` (the lines of the text BEFORE the write where a replaced occurrence starts, ranges collapsed, at most 40 entries then `(+K more)` lines). No whole-word occurrence is an error, nothing written, naming occurrences inside longer identifiers and in another case. `oldSource` equal to `source` is refused, and so are `methodName`, `methodNames`, `fromModulePath`, `expectedSource` and `overwrite`; `expectedHash`, the syntax check and the repeated-method-name check apply as in every mode, so a replaceAll that turns one declaration into the name of another is refused. The file must already exist; the result is bounded at 10,000,000 characters.
- `replace`: replaces the entire file. The ONLY mode that can CREATE a new module (creates parent folders). Over an EXISTING module it is guarded (see Lost-update guards).
- `append`: adds `source` to the end. The file must already exist.
- `replaceMethod`: replaces the complete `methodName` definition. Its span includes the contiguous `//` documentation and `&...` annotations/directives immediately above the declaration. `source` must declare exactly one complete procedure/function with the same name AND the same kind; a rename is rejected, and so is turning a Function into a Procedure or back - callers of a Function consume its return value, and the block-balance check cannot see the difference.
- `insertBefore`: inserts the one complete method in `source` before the anchor's documentation/annotations, never between an annotation and its declaration - a blank line between them does not detach the directive.
- `insertAfter`: inserts the one complete method in `source` after the anchor's complete terminator.
- `deleteMethods`: deletes every method named in `methodNames`, each with its documentation/annotations; the blank lines at each seam collapse to one. `expectedHash` is REQUIRED; regions stay, even when they become empty.

- `deleteModule`: remove an existing `ObjectModule.bsl` or `ManagerModule.bsl` owned by a configuration or extension object. Pass only the module address and `expectedHash` from `read_module_source`; objectName also accepts moduleType. It accepts empty or whitespace-only files, including a UTF-8 BOM. It refuses comments, directives, code, adopted and external objects, and other module types (including forms). Close the module and its owning editor in EDT first. EDT's destructive-operation confirmation applies in the workbench. Success has `deletedFile: true`, not a new content hash.

Both insert modes ensure exactly one blank line between the inserted method and each neighbour, including comments and preprocessor boundaries. Blank lines at the edges of `source` and at the insertion point are collapsed, with no padding at a module edge. Method bodies, attached documentation/annotations and preprocessor lines stay unchanged. `replaceMethod` does not normalize spacing.

For all three method-targeted modes, `methodName` and `expectedHash` are REQUIRED. The anchor must resolve to exactly one declaration; duplicate declarations in preprocessor branches are rejected as ambiguous. For insert modes, the incoming method name must not already exist anywhere in the module, so repeating an insert is refused instead of creating a duplicate.

The method parser recognizes `Procedure`/`Function` and `Процедура`/`Функция`, with matching `EndProcedure`/`EndFunction` and `КонецПроцедуры`/`КонецФункции` terminators. A terminator must end on a keyword boundary: identifiers such as `EndProcedureResult` and `КонецПроцедурыРезультат` do not close a method, and a terminator must own its line - `EndProcedure; ModuleValue = Call();` is module-level code after the method, not a method end. An unterminated method is reported incomplete rather than borrowing the next method terminator. Some shapes cannot be addressed by a whole-line scanner at all. The module is REFUSED, naming the line and what to change, rather than edited around the shape: a declaration split across lines, or holding only the keyword with its name on the next line; a parameter list that does not close before the method ends (one WRAPPED onto continuation lines is ordinary formatting and is addressed normally); a method named after a block keyword; a terminator sharing its line with other code; a declaration written after something else on its line; a pragma sharing the declaration line; and a pragma whose arguments do not close on its own line. A whole method written on ONE line (declaration and terminator together) is refused for the same reason. The shapes this cost was measured for do not occur in real configurations: zero next-line parentheses and zero one-line methods in 1C:ERP 2.5.16.41, and zero multi-line pragmas across its 22,786 modules.

## Moving methods between modules

A move takes two calls; the method text never passes through you.
1. Copy into the target: `modulePath` = target, `fromModulePath` = source (same project), `methodNames` = the methods. The default mode is `append`; `insertBefore`/`insertAfter` take an anchor `methodName` and the target's `expectedHash`; `replace` only creates a module that does not exist yet. The methods arrive in request order, one blank line apart, line for line as in the source (in the target's line delimiter, never character-normalized). The response carries `copiedMethods`, `fromModulePath`, `fromContentHash` (the source's token for the text copied) and the target's `contentHash`.
2. Delete from the source: `modulePath` = source, `mode=deleteMethods`, the same `methodNames`, `expectedHash=<fromContentHash>`. If the source changed after the copy, the deletion is refused, so it removes exactly what was copied. The response carries `deletedMethods` and a `warning` when a deleted method is still called in the source (a text check of unqualified `Name(` calls outside comments and strings).

Both calls are all-or-nothing and refuse, before writing: a name not found, declared more than once (preprocessor branches) or without its terminator; a name given twice; `source` together with `fromModulePath`; and, for a copy, the source module itself as the target, a name the target already declares (so a repeated copy is refused), a method inside or crossing an `#If` block (the copy would drop the condition) and a method with an interception annotation (`&Before`, `&After`, `&Around`, `&ChangeAndValidate`; it binds the method to its own module). Calls to the moved methods elsewhere are not rewritten: find them with `find_references`, qualify them (`NewModule.Method(`), then check `get_project_errors`.

## Lost-update guards

Concurrent edits between your read and write are caught by:
- `expectedHash` (ANY mode; REQUIRED for method-targeted modes, deleteMethods and deleteModule): pass the opaque `contentHash` from your last `read_module_source` / `read_method_source` / `write_module_source`. If the module changed, the write is rejected. Cheapest (a fixed-size token, not the whole file). Omit it when creating a new module, because there is no existing content to match.
- `expectedSource` (mode=replace): pass the exact content you last read. Mismatch is rejected.
- `overwrite=true` (mode=replace): force the overwrite with no content check.
A bare `replace` over an existing module with none of these is rejected and steers you toward expectedSource / overwrite / searchReplace. A matching `expectedHash` already satisfies the replace precondition. All comparisons are `\n`-normalized, so a CRLF/LF-only difference is not a spurious mismatch.

Every successful write of module text answers `contentHash` in its frontmatter: the token of the text it just wrote, equal to what `read_module_source` reports for the module right after. Chain the next guarded edit on it (`expectedHash`) without reading the module again; it goes stale as soon as anything else changes the module. An error with `mutationCommitted:true` carries it too, since that text is written.

## BSL syntax check

Before writing, the resulting content is checked for balanced block keywords (Procedure/EndProcedure, Function/EndFunction, If/EndIf, While/EndDo, For/EndDo, Try/EndTry). On error the write is BLOCKED and the errors are returned. Pass `skipSyntaxCheck=true` to force.

In every mode it is also checked for procedure and function NAMES: the 1C platform refuses to load a module that declares one name twice (any case, a Procedure and a Function alike) - every method of the module stops working - and EDT marks it `method-already-defined`. A write that CREATES such a duplicate is BLOCKED, nothing written; the error names the method and its lines. Rename the new method, or change the existing one with `replaceMethod`. Two declarations of one name are allowed in different branches of one `#If` block (`#If Server Then ... #Else ... #EndIf`) - written through the text modes; `insertBefore`/`insertAfter` and a copy refuse any name the module already declares, because the method-targeted modes address a method by its name. What does not block, but comes back in the `warning`: copies in separate `#If` blocks or under different compilation directives (`&AtClient` / `&AtServer`), which may or may not be compiled together; a duplicate the module already had before the write, so an unrelated edit of such a module still goes through; and, with `skipSyntaxCheck=true`, a duplicate written anyway.

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
1. The host extension module must EXIST - adopt the base object/form into the extension first (`adopt_metadata_object`), or create the extension common module via `create_metadata`. `write_module_source` does not adopt the object; writing the module of an adopted object also records that module's state `Extended` when it is `None` (see Modules of adopted objects). `mode=replace` can create a missing file, `searchReplace`/`append` need it to exist.
2. The BASE method must exist in the parent configuration with a matching signature.
3. Run `get_project_errors` after writing to confirm the extension method resolved.

## Modules of adopted objects

In a configuration extension, an ADOPTED object's `.mdo` can record a state for each of its modules - `<module>` for a common module, `<objectModule>`, `<managerModule>`, `<recordSetModule>`, `<valueManagerModule>`, `<commandModule>`, `<sessionModule>` and so on for the others - and has that line only once the state is set. When code is added to an adopted module in EDT's UI, EDT records `Extended` there, e.g. `<module>Extended</module>`. EDT's own listener also switches the state from `None` to `Extended` after a module file is written, but only in its model, a few seconds later, and it never saves the `.mdo`.

This tool records the state at once: after writing such a module it switches the state from `None` to `Extended` with EDT's own call, exports the owner's `.mdo`, and reads that `.mdo` back until it carries the state line, for up to 60s counted from the export request - EDT's export queue drains even when EDT fails to write the file, so only the file confirms the export. The export request itself is EDT's own call, the one `create_metadata`, `modify_metadata` and `delete_metadata` make too: it counts toward the 60s but is not cut short, so while EDT keeps it waiting the call waits too. While an earlier `.mdo` read of the project is still running or waiting to run, no other starts - for up to 3 minutes, after which one more may - and the `.mdo` counts as not read back. The response's frontmatter then carries `moduleExtensionState`:
- `switchedToExtended` - the state was `None` and is now `Extended`; the body says so.
- `alreadyExtended` - the state already was `Extended` (EDT's listener may have set it first); the `.mdo` is exported again to match the model and read back the same way. Which of the two values a write reports is a race with that listener, so an export that is not confirmed fails the write either way (below).
- `keptChecked` or `keptNotify` - the state is `Checked` or `Notify`; like EDT's own listener, this tool switches only `None`, so it is left exactly as it is, and the body says so.
- `noExtensionBlock` - the adopted object has no extension block in its `.mdo` (for the extension root, `Configuration.mdo`), so the state cannot be recorded; the body names the object. A root created by EDT's own new-extension wizard, or by `create_project` (which adopts the base's root as that wizard does), has the block; a root made by an earlier version of `create_project` has none, and the body says how to get one.

Each of these is a success: the module text is written. The state is never switched back - emptying the module keeps `Extended`, as in EDT - `Checked` or `Notify` is never overwritten, and a missing extension block is never created. Modules of the extension's own objects and of a base configuration are untouched: their responses carry no key - except for the transient error below, while EDT's model of the extension is not available.

When the step cannot finish, the call returns an error with `mutationCommitted:true`: the module text IS written - do not re-send the original edit. The error names the cause and the fix: EDT's model of the extension was not available - a transient window in which an adopted object cannot be told from an own one, so this error can come for any module of the extension, its own objects included; the step failed; or the state is `Extended` in EDT's model - switched or already - but its `.mdo` export was not confirmed: the `.mdo`, read back, did not carry its line within 60s or could not be read back, or the export could not be scheduled and the `.mdo`, read once if time was left and no earlier read of the project's files was out, was not seen carrying it. The root's ordinary-application module is the exception: EDT does not list it among the properties an extension can extend, so none of these fails a write into it - the body notes it instead. To run the step again, call `write_module_source` on the same module with `mode=append` and an empty `source`: it rewrites the module's current text, line ends normalized (add `skipSyntaxCheck=true` if the module was written with it).

Not covered - these modules are written as text only, so set their state to `Extended` in EDT: the modules of an external data source's tables, cubes and dimension tables, and `Bot`, `IntegrationService` and `WebSocketClient` modules. A recalculation's record-set module has no extension state at all. A form module needs nothing: the form's own state (`<form>Extended</form>`) is set when the form is adopted.

## Examples

Surgical edit (default mode):
```
{ "projectName": "MyProj", "modulePath": "CommonModules/MyModule/Module.bsl",
  "oldSource": "Return 1;", "source": "Return 2;" }
```

Replace every whole-word occurrence of a name (comments and strings included):
```
{ "projectName": "MyProj", "modulePath": "CommonModules/MyModule/Module.bsl",
  "mode": "replaceAll", "oldSource": "OldName", "source": "NewName",
  "expectedHash": "<contentHash from read_module_source>" }
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
- `searchReplace`/`append`/`replaceAll`, `deleteMethods` and all method-targeted modes need an EXISTING file; only `replace` creates one.
- To rename an identifier across several modules: find the modules with `search_in_code`, then one `replaceAll` per module; each answer names its count and lines.
- New BSL files are written with a UTF-8 BOM; existing files keep their BOM state.
- `source` is `\r\n`->`\n` normalized and a module with code always ends with a newline; a module left with no lines (every method deleted) is written empty.
- In an extension, writing the module of an ADOPTED object also switches that module's state from `None` to `Extended` and exports the object's `.mdo`; `Checked`, `Notify` and a missing extension block are left as they are (see Modules of adopted objects).

## Addressing the module

`modulePath` and the `objectName` + `moduleType` pair are mutually exclusive - give one shape or the other, never both.

## Vendor support
A configuration on vendor support can lock objects. Writing a module is refused when the object that owns it (a common module, a catalog for its object module, a form for its form module; the configuration for its own modules) or the module itself is locked by its support rule. The file is not touched. The module must belong to `projectName`: an absolute `modulePath` that resolves into another project is refused. The error names the object and says nothing was changed. The way out is a configuration extension (adopt the object with adopt_metadata_object and change the adopted copy) or the user allowing changes in EDT's support settings; this server never changes support settings. If EDT's support check cannot be answered, the write is refused too. A configuration extension and an external data processor/report project are never under vendor support: EDT applies support only to a configuration's own (native) root, never inside an extension, which is why an extension is the way to change a supported configuration.
