Returns a YAML snapshot of a form's **calculated WYSIWYG layout**: per-element bounds (x/y/width/height), element types, and display-affecting properties, plus the overall form size. The response type is TEXT (the YAML itself).

## When to use
- Inspect or compare what a form actually lays out (positions, sizes, visibility) rather than its declarative `.form` definition.
- Verify a layout change took effect, or diff two states of the same form.
- For a rendered PNG instead of layout data, use `get_form_screenshot`.

## Render-mode flags (read this first)
No buffered-render JVM flag is required for this tool's element tree or form-level size. Per-element bounds are governed by EDT's **native form layout render mode**, exposed as `nativeFormLayoutRender`:
- Native render **on** (`-DnativeFormLayoutRender=true`, also EDT's default when the property is absent): EDT computes the layout in its C++ visualizer. It does not return per-element rectangles to the Java `modelProjection` / `layoutProjection` / `viewProjection` chain used by this tool, so `elementsWithBounds: 0` is structural rather than a rendering delay. Retrying or using `refresh: true` cannot populate those bounds.
- Native render **off** (`-DnativeFormLayoutRender=false`): EDT populates the Java projections. A zero-bounds result can then mean that the form has not finished rendering; retry or use `refresh: true`.

`-DnativeFormBufferedLayoutRender=true` is a different knob. It enables the offscreen buffered image used by `get_form_screenshot`; it does not make `get_form_layout_snapshot` produce per-element bounds. `get_server_status` → `formRenderFlags` reflects the requested system-property values, not EDT's effective render mode: when `-DnativeFormLayoutRender` is absent, that report reads `false` even though EDT defaults to native render. Do not use that field alone to distinguish the two cases. If native rendering is on and you need the form's element hierarchy rather than pixel rectangles, call `get_metadata_details` with the form FQN to inspect the element tree and its nesting.

## Parameter details
- `projectName` - EDT project name. **Required when `formPath` is specified**; omitting it then returns an error. Ignored when targeting the active editor.
- `formPath` - metadata FQN of the form. If given, the tool opens and activates that form automatically. If omitted, the currently active form editor is used.
- `refresh` - force a WYSIWYG refresh before capturing; default `true`. Set `false` to read the last-rendered state without re-laying-out.
- `mode` - `compact` (default) or `full`; an unknown value returns an error.

### formPath format
`MetadataType.ObjectName.Forms.FormName`, or `CommonForm.FormName` for a common form. Examples:
- `Catalog.Products.Forms.ItemForm`
- `Document.SalesOrder.Forms.DocumentForm`
- `CommonForm.MyForm`

## Modes
- `compact` (default) - only visual elements with positive bounds, and only selected display-affecting properties. Best for a readable overview.
- `full` - every layout node (including zero-bounds/structural ones) and all non-containment properties. Verbose; use when you need the complete tree.

## Examples
- Active editor, default compact: `{}`.
- Specific form: `{projectName: "MyProj", formPath: "Catalog.Products.Forms.ItemForm"}`.
- Full tree, no refresh: `{formPath: "CommonForm.MyForm", projectName: "MyProj", mode: "full", refresh: false}`.

## Notes & gotchas
- `formPath` without `projectName` is rejected: "projectName is required when formPath is specified".
- Needs a live workbench Display; runs on the UI thread.
- Read a "No calculated element bounds were found" warning according to its render-mode diagnosis:
  - Native render on: Java-side per-element bounds are not produced. This is structural; do not retry. Relaunching EDT with `-DnativeFormLayoutRender=false` is what produces bounds, but it is a trade-off - native render is the mode `get_form_screenshot`'s image path uses. If you only need the element tree and nesting, use `get_metadata_details` instead.
  - Native render off: the form may not have finished rendering. Retry or ensure `refresh` is `true`.
  - Render mode unknown: the probe could not read EDT's effective render mode, so the warning names both possibilities without choosing one. Call `get_metadata_details` with the form FQN to inspect the element tree regardless of render mode. EDT uses native render by default when `-DnativeFormLayoutRender` is not set explicitly, so an installation with no such line is most likely in the structural case. Do not use `get_server_status` → `formRenderFlags` to choose between retrying and relaunching because it reports requested system-property values, not the effective default.
