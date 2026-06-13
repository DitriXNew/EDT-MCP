# write_module_source

Write BSL source code to a 1C metadata object module. Use to edit a module: searchReplace a fragment (default, needs oldSource), replace the whole file, or append. Target the module by EITHER modulePath OR objectName (mutually exclusive — pass exactly one). Runs a BSL syntax check before writing (skipSyntaxCheck=true to force). Full parameters and examples: call get_tool_guide('write_module_source').

## Parameters
| Parameter | Required | Type | Description |
| --- | --- | --- | --- |
| projectName | yes | string | EDT project name (required) |
| modulePath | — | string | src/-relative module path, e.g. 'CommonModules/MyModule/Module.bsl'. Alternative to objectName (pass exactly one). |
| objectName | — | string | Object name 'Type.Name', e.g. 'Document.MyDoc'. Resolved with moduleType. Alternative to modulePath. |
| moduleType | — | string (one of: ObjectModule, ManagerModule, FormModule, CommandModule, RecordSetModule, Module) | Module type for objectName resolution (default ObjectModule). |
| source | yes | string | BSL source to write (required): full file for replace, new fragment for searchReplace, text to add for append. |
| oldSource | — | string | Fragment to find and replace; required for searchReplace, must match exactly once. |
| mode | — | string (one of: searchReplace, replace, append) | Write mode (default searchReplace). |
| formName | — | string | Form name; required when moduleType=FormModule (e.g. 'ItemForm'). |
| commandName | — | string | Command name; required when moduleType=CommandModule (e.g. 'FillByTemplate'). |
| skipSyntaxCheck | — | boolean | Skip the BSL syntax check (default false). |
| expectedSource | — | string | Lost-update guard for mode=replace: the module content you last read; mismatch rejects. |
| overwrite | — | boolean | Force mode=replace over an existing module without an expectedSource check (default false). |
| expectedHash | — | string | Lost-update guard for any mode: the contentHash from your last read; mismatch rejects. |

## Guide
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
| `mode` | optional | `searchReplace` (default), `replace`, `append`. |
| `formName` | moduleType=FormModule | except CommonForm. |
| `commandName` | moduleType=CommandModule | except CommonCommand. |
| `skipSyntaxCheck` | optional | default false. |
| `expectedSource` | mode=replace | lost-update guard. |
| `overwrite` | mode=replace | force without expectedSource. |
| `expectedHash` | any mode | cheap lost-update guard. |

## moduleType to path

`ObjectModule` (default), `ManagerModule`, `RecordSetModule`, `Module` resolve to `<Dir>/<Name>/<moduleType>.bsl`. `FormModule` resolves to `<Dir>/<Name>/Forms/<formName>/Module.bsl` and REQUIRES `formName` — except CommonForm, which has no per-form name and resolves to `CommonForms/<Name>/Module.bsl`. `CommandModule` resolves to `<Dir>/<Name>/Commands/<commandName>/CommandModule.bsl` and REQUIRES `commandName` — except CommonCommand, which resolves to `CommonCommands/<Name>/CommandModule.bsl`.

## Modes

- `searchReplace` (default): finds `oldSource` and replaces it with `source`. `oldSource` is REQUIRED and must match EXACTLY ONE location — zero matches or multiple matches are rejected with a steer to read again / give a larger fragment. The match runs on the raw file content (trailing newline preserved), so a fragment ending at EOF including its final newline is found. The file must already exist.
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

## Gotchas

- Only `.bsl` files; `modulePath` may not contain `..`.
- `searchReplace`/`append` need an EXISTING file; only `replace` creates one.
- New BSL files are written with a UTF-8 BOM; existing files keep their BOM state.
- `source` is `\r\n`->`\n` normalized and the file always ends with a newline.

---
*Generated from the live MCP server (`get_tool_guide`) by `docs/generate_tool_docs.py`. Do not edit this file. Edit the tool's description/schema in its Java source and its guide body in `mcp/bundles/com.ditrix.edt.mcp.server/guides/<tool>.md`.*
