# get_form_screenshot

Visually inspect an EDT form as rendered by the designer. Requires EDT launched with -DnativeFormBufferedLayoutRender=true on Windows; a blank or gray image can also mean the offscreen buffer is unavailable. Parameters and examples: get_tool_guide('get_form_screenshot').

## Parameters
| Parameter | Required | Type | Description |
| --- | --- | --- | --- |
| projectName | — | string | EDT project name. Required when formPath is specified. |
| formPath | — | string | Form FQN (e.g. 'Catalog.Products.Forms.ItemForm' or 'CommonForm.MyForm'); if omitted, captures the active form editor. |
| showElement | — | string | Name of a form element (e.g. a page) to bring into view before capture: every enclosing Pages group switches to the page holding it. |
| refresh | — | boolean | Force a real WYSIWYG re-render before capture; fails with an explicit error instead of returning a stale image when the re-render cannot be completed (default: false) |

## Guide
Captures a **PNG screenshot** of a form's WYSIWYG editor as it actually renders (the same visual EDT shows in the form designer). The response type is IMAGE - the tool returns the PNG, not text.

## When to use
- See what a form looks like rendered, verify a layout/visibility change visually, or attach a before/after image.
- If you need element positions/sizes as DATA (bounds, types, properties) rather than a picture, use `get_form_layout_snapshot` instead.
- To inspect the declarative form definition, read the `.form` model; this tool is only the rendered bitmap.

## Buffered rendering (read this first)
On **Windows**, launch EDT with `-DnativeFormBufferedLayoutRender=true` after `-vmargs` in `1cedt.ini`, then fully restart EDT. Linux and macOS already use buffered native rendering; this flag changes nothing there. `get_form_layout_snapshot` does not need it.

On Windows, buffered mode also switches the human designer to offscreen images: if EDT cannot create `<java.io.tmpdir>dtgraphics-form.bin`, it paints nothing and the designer turns **gray**. Blank or gray output can mean either a missing flag or an unusable buffer path. Check the EDT log for `AccessDeniedException` on `dtgraphics-form.bin`, possibly followed by `NoClassDefFoundError: org/eclipse/core/runtime/Plugin`. EDT concatenates the directory and filename without a separator: change `-Djava.io.tmpdir=C:\edt_tmp` to `-Djava.io.tmpdir=C:\edt_tmp\` and restart EDT. If nothing helps, remove the buffered-render flag and restart EDT. Even with a usable buffer path, buffered mode can distort the human designer on a scaled Windows display (larger elements, clicks selecting a different element), so tell the user the flag is for screenshot sessions, not for everyday designer work.

The screenshot path attempts to force buffered mode at runtime. This can affect the human designer too and does not guarantee a usable buffer; it is not a substitute for the launch configuration.

## Parameter details
- `projectName` - EDT project name. **Required when `formPath` is specified**; omitting it then returns the error "projectName is required when formPath is specified". Ignored when targeting the active editor.
- `formPath` - metadata FQN of the form. If given, the tool opens and activates that form automatically, waits for the WYSIWYG page, then captures it. If omitted, the currently active form editor is captured.
- `showElement` - programmatic name of a form element to bring into view before capturing, typically a page of a Pages group (case-insensitive; the name, not the title). Every Pages group enclosing the element switches to the page holding it, nested groups included - the same as selecting the element in the form designer. An element outside every Pages group is visible on the current pages, so nothing is re-rendered for it and the capture shows the editor's current pages. Without `showElement` the capture shows the designer's current page, which is the first one after the form opens or after `refresh: true`. The switch is done after the refresh render, so the two can be combined, and it applies to this capture only. Afterwards the editor is put back as far as EDT allows: one render selects again the element the editor had selected, which re-opens the pages enclosing it (a clicked tab included); with nothing selected the default pages come back. EDT keeps no other record of which page a Pages group shows, so other Pages groups return to their default pages, and the earlier selection frame is not guaranteed. If that render fails the call fails, because the editor and a later capture without `showElement` would keep the switched page; `refresh: true` then re-renders it. An unknown name fails fast, before any render and in every render mode, with an error that names the form and lists its pages (at most 20); `get_metadata_details` with the form FQN lists every element. Requires the native form render: with `-DnativeFormLayoutRender=false` a known name is refused rather than returning the default page.
- `refresh` - force a **real re-render** before capturing; default `false`. Set `true` if the form was just edited and the rendered image may be stale. With `refresh: true` the previously rendered buffer is never returned: if the re-render cannot be completed in time the tool fails with an explicit error ("refresh=true was requested but the form could not be re-rendered in time...") instead of silently returning the pre-edit image; retry, or call with `refresh: false` to accept the last rendered image.

### formPath format
`MetadataType.ObjectName.Forms.FormName`, or `CommonForm.FormName` for a common form. Examples:
- `Catalog.Products.Forms.ItemForm`
- `Document.SalesOrder.Forms.DocumentForm`
- `CommonForm.MyForm`

## Examples
- Active editor, default: `{}`.
- Specific form: `{projectName: "MyProj", formPath: "Catalog.Products.Forms.ItemForm"}`.
- A specific page: `{projectName: "MyProj", formPath: "Document.SalesOrder.Forms.DocumentForm", showElement: "DeliveryPage"}`.
- Force refresh first: `{projectName: "MyProj", formPath: "CommonForm.MyForm", refresh: true}`.

## Notes & gotchas
- Blank or gray image: check both the Windows launch flag and the buffer path (see above).
- `formPath` without `projectName` is rejected before any rendering.
- After opening a form the tool lets the UI settle briefly; if the page is still loading you may get "Form editor opened but WYSIWYG page is not available" - retry.
- Needs a live workbench Display and runs on the UI thread; not available headless.
- The saved file is named after the last FQN segment (e.g. `ItemForm.png`), or `form.png` when capturing the active editor.

---
*Generated from the live MCP server (`get_tool_guide`) by `docs/generate_tool_docs.py`. Do not edit this file. Edit the tool's description/schema in its Java source and its guide body in `mcp/bundles/com.ditrix.edt.mcp.server/guides/<tool>.md`.*
