Captures a **PNG screenshot** of a 1C **template** (макет) - a `SpreadsheetDocument` (print form) - exactly as EDT renders it. The response type is IMAGE: the tool returns the PNG, not text. This lets an AI *see* a print-form layout (and read the text inside it) so it can review or refine the макет without parsing the binary `.mxlx`.

## When to use
- See what a print-form template looks like rendered, or read the text/cells laid out in it.
- Review a макет visually before/after editing it.
- For the declarative content as DATA, read the `Template.mxlx` model instead; this tool is only the rendered bitmap.

## How it renders (no JVM flag needed)
A `SpreadsheetDocument` is the "moxel" model in EDT. This tool opens the common-template editor, reaches its embedded spreadsheet editor and rasterizes the document **off-screen** via EDT's own print/preview pipeline (`PrintHelper.makeImageToDisplay`), painting into an in-memory image on the UI thread. Unlike `get_form_screenshot` there is **no `-DnativeFormBufferedLayoutRender` dependency** - the render is a straight synchronous call, so a blank result is not expected from a missing flag.

## Parameter details
- `projectName` (required) - EDT project name. Omitting it returns "projectName is required".
- `templatePath` (required) - the template FQN. Omitting it returns "templatePath is required".

### templatePath format
A **common template** FQN: `CommonTemplate.<Name>`. The Russian type token `ОбщийМакет` is accepted too (it resolves to the same object). Examples:
- `CommonTemplate.PrintForm`
- `CommonTemplate.ПечатнаяФорма`
- `ОбщийМакет.ПечатнаяФорма`

Only **common** templates are supported. An owned object template (`Catalog.X.Template.Y`) is rejected with a clear error - those are serialized inline in the owner and have no own editor input here.

## Examples
- `{projectName: "MyProj", templatePath: "CommonTemplate.PrintForm"}`
- `{projectName: "MyProj", templatePath: "CommonTemplate.ПечатнаяФорма"}`

## Result
- SUCCESS - a PNG image (the rendered template) on the IMAGE resource channel. The saved file is named after the last FQN segment (e.g. `PrintForm.png`).
- A multi-page template is stitched vertically into one PNG (one print page below the previous).
- The image is a print page at 1:1 scale, so a small template renders top-left on a mostly-white page - the content is still fully legible.

## Notes & gotchas
- Only `SpreadsheetDocument` templates render to an image. A non-spreadsheet template (BinaryData, DataCompositionSchema, etc.) returns a clear "no SpreadsheetDocument editor page" / "is not a SpreadsheetDocument template" error rather than a garbage image.
- An empty template (no content) returns "has no content to render".
- Needs a live workbench Display and runs on the UI thread; not available headless.
- `templatePath` that does not resolve to a `CommonTemplate.<Name>` shape returns "Cannot resolve template path: ... Expected 'CommonTemplate.<Name>'".
- A missing file returns "Template file not found: <path> in project <projectName>"; an unknown project returns "Project not found: <name>".
