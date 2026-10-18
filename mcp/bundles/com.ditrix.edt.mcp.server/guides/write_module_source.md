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
- `replaceMethod`: replaces the complete `methodName` definition. Its span includes the contiguous `//` documentation and `&...` annotations/directives immediately above the declaration. `source` must declare exactly one complete procedure/function with the same name AND the same kind; a rename is rejected, and so is turning a Function into a Procedure or back - callers of a Function consume its return value, and the block-balance check cannot see the difference.
- `insertBefore`: inserts the one complete method in `source` before the anchor's documentation/annotations, never between an annotation and its declaration - a blank line between them does not detach the directive.
- `insertAfter`: inserts the one complete method in `source` after the anchor's complete terminator.

For all three method-targeted modes, `methodName` and `expectedHash` are REQUIRED. The anchor must resolve to exactly one declaration; duplicate declarations in preprocessor branches are rejected as ambiguous. For insert modes, the incoming method name must not already exist anywhere in the module, so repeating an insert is refused instead of creating a duplicate.

The method parser recognizes `Procedure`/`Function` and `Процедура`/`Функция`, with matching `EndProcedure`/`EndFunction` and `КонецПроцедуры`/`КонецФункции` terminators. A terminator must end on a keyword boundary: identifiers such as `EndProcedureResult` and `КонецПроцедурыРезультат` do not close a method, and a terminator must own its line - `EndProcedure; ModuleValue = Call();` is module-level code after the method, not a method end. An unterminated method is reported incomplete rather than borrowing the next method terminator. Some shapes cannot be addressed by a whole-line scanner at all. The module is REFUSED, naming the line and what to change, rather than edited around the shape: a declaration split across lines, or holding only the keyword with its name on the next line; a parameter list that does not close before the method ends (one WRAPPED onto continuation lines is ordinary formatting and is addressed normally); a method named after a block keyword; a terminator sharing its line with other code; a declaration written after something else on its line; a pragma sharing the declaration line; and a pragma whose arguments do not close on its own line. A whole method written on ONE line (declaration and terminator together) is refused for the same reason. The shapes this cost was measured for do not occur in real configurations: zero next-line parentheses and zero one-line methods in 1C:ERP 2.5.16.41, and zero multi-line pragmas across its 22,786 modules.

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
- In an extension, writing the module of an ADOPTED object also switches that module's state from `None` to `Extended` and exports the object's `.mdo`; `Checked`, `Notify` and a missing extension block are left as they are (see Modules of adopted objects).

## Addressing the module

`modulePath` and the `objectName` + `moduleType` pair are mutually exclusive - give one shape or the other, never both.

## Vendor support
A configuration on vendor support can lock objects. Writing a module is refused when the object that owns it (a common module, a catalog for its object module, a form for its form module; the configuration for its own modules) or the module itself is locked by its support rule. The file is not touched. The module must belong to `projectName`: an absolute `modulePath` that resolves into another project is refused. The error names the object and says nothing was changed. The way out is a configuration extension (adopt the object with adopt_metadata_object and change the adopted copy) or the user allowing changes in EDT's support settings; this server never changes support settings. If EDT's support check cannot be answered, the write is refused too. A configuration extension and an external data processor/report project are never under vendor support: EDT applies support only to a configuration's own (native) root, never inside an extension, which is why an extension is the way to change a supported configuration.
