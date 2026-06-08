Captures a **PNG screenshot** of a form's WYSIWYG editor as it actually renders (the same visual EDT shows in the form designer). The response type is IMAGE - the tool returns the PNG, not text.

## When to use
- See what a form looks like rendered, verify a layout/visibility change visually, or attach a before/after image.
- If you need element positions/sizes as DATA (bounds, types, properties) rather than a picture, use `get_form_layout_snapshot` instead.
- To inspect the declarative form definition, read the `.form` model; this tool is only the rendered bitmap.

## Required JVM flag (read this first)
EDT must be launched with `-DnativeFormBufferedLayoutRender=true` in the `1cedt.ini` `-vmargs` section. Without it the offscreen layout handler is never constructed and the screenshot comes back **blank/empty**. A blank image almost always means the flag is missing - it is NOT a bad call or a code bug, so do not retry or reshape the arguments to "fix" it; add the flag and relaunch EDT.

## Parameter details
- `projectName` - EDT project name. **Required when `formPath` is specified**; omitting it then returns the error "projectName is required when formPath is specified". Ignored when targeting the active editor.
- `formPath` - metadata FQN of the form. If given, the tool opens and activates that form automatically, waits for the WYSIWYG page, then captures it. If omitted, the currently active form editor is captured.
- `refresh` - force a WYSIWYG refresh before capturing; default `false`. Set `true` if the form was just edited and the rendered image may be stale.

### formPath format
`MetadataType.ObjectName.Forms.FormName`, or `CommonForm.FormName` for a common form. Examples:
- `Catalog.Products.Forms.ItemForm`
- `Document.SalesOrder.Forms.DocumentForm`
- `CommonForm.MyForm`

## Examples
- Active editor, default: `{}`.
- Specific form: `{projectName: "MyProj", formPath: "Catalog.Products.Forms.ItemForm"}`.
- Force refresh first: `{projectName: "MyProj", formPath: "CommonForm.MyForm", refresh: true}`.

## Notes & gotchas
- Blank image => the `-DnativeFormBufferedLayoutRender=true` flag is missing (see above), not a failure of this call.
- `formPath` without `projectName` is rejected before any rendering.
- After opening a form the tool lets the UI settle briefly; if the page is still loading you may get "Form editor opened but WYSIWYG page is not available" - retry.
- Needs a live workbench Display and runs on the UI thread; not available headless.
- The saved file is named after the last FQN segment (e.g. `ItemForm.png`), or `form.png` when capturing the active editor.
