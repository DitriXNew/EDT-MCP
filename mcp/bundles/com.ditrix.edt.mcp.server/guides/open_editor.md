Open an existing object in the EDT workbench for human inspection. This changes editor focus,
but does not modify or save project content. No additional HTTP listener or URL handler is installed.

### Enable navigation

In **Window > Preferences > MCP Server > General**, check **Allow UI navigation** and click
**Apply** or **OK**. The default is off. Uncheck it to refuse subsequent calls immediately.
The Tools tab's normal enablement and progressive disclosure still apply; this tool belongs
to the `code` toolset. Enabling a toolset does not grant UI navigation permission.

### Select a target

Pass `projectName` and exactly one of:

- `fqn`: metadata object/member, e.g. `Catalog.Products`, `Catalog.Products.Attribute.Price`,
  `Catalog.Products.Form.Item`, or `CommonForm.Main`. EDT chooses its native editor and
  handles selection of a member inside its owner. Only type/kind tokens are bilingual;
  names are programmatic identifiers, not synonyms. Managed forms are supported; ordinary
  forms are refused. Use `get_metadata_objects` / `get_metadata_details` to discover names.
- `modulePath`: a BSL path relative to `src/`, e.g. `CommonModules/Utilities/Module.bsl`.
  Absolute paths are accepted only for BSL files belonging to the named project.
  Use `list_modules` to discover paths. Optionally pass either `line` (positive, 1-based)
  or `methodName` (procedure/function name, case-insensitive). Without either, select line 1.
  Method lookup uses the shared BSL text parser and selects the declaration line.

Examples:

```json
{"projectName":"TestConfiguration","fqn":"Catalog.Catalog"}
{"projectName":"TestConfiguration","fqn":"CommonForm.Form"}
{"projectName":"TestConfiguration","modulePath":"CommonModules/OK/Module.bsl","line":1}
{"projectName":"TestConfiguration","modulePath":"CommonModules/Calc/Module.bsl","methodName":"Add"}
```

### Availability and timeouts

Requires a running EDT workbench and an active page; headless calls return an actionable error.
The MCP worker waits at most 5 seconds for the UI operation. A task still queued on timeout
or interruption is cancelled. If EDT already started opening an editor, it can still finish;
check EDT before retrying. A call made on the UI thread is refused to preserve the bounded wait.
No success is returned unless the native editor service reports an opened editor.
