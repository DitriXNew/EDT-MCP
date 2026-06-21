Captures a **PNG screenshot** of a 1C **template** (макет) - a `SpreadsheetDocument` (print form) - exactly as EDT renders it. The response type is IMAGE: the tool returns the PNG, not text. This lets an AI *see* a print-form layout (and read the text inside it) so it can review or refine the макет without parsing the binary `.mxlx`.

## When to use
- See what a print-form template looks like rendered, or read the text/cells laid out in it.
- Review a макет visually before/after editing it.
- For the declarative content as DATA, read the `Template.mxlx` model instead; this tool is only the rendered bitmap.

## How it renders (no JVM flag needed)
A `SpreadsheetDocument` is the "moxel" model in EDT. This tool resolves the template object by FQN, opens it in the EDT template editor via the same path the navigator's "Open" uses (so common and object-owned templates open identically), reaches the embedded spreadsheet editor and rasterizes the document **off-screen** via EDT's own print/preview pipeline (`PrintHelper.makeImageToDisplay`), painting into an in-memory image on the UI thread. Unlike `get_form_screenshot` there is **no `-DnativeFormBufferedLayoutRender` dependency** - the render is a straight synchronous call, so a blank result is not expected from a missing flag.

## Parameter details
- `projectName` (required) - EDT project name. Omitting it returns "projectName is required".
- `templatePath` (required) - the template FQN. Omitting it returns "templatePath is required".

### templatePath format
Either a **common template** or an **object-owned template**. Type / kind tokens are bilingual (English or Russian).
- Common template: `CommonTemplate.<Name>` (Russian token `ОбщийМакет` accepted), e.g. `CommonTemplate.PrintForm`, `ОбщийМакет.ПечатнаяФорма`.
- Object-owned template: `<Type>.<Owner>.Template.<Name>` (the `Template` token may be Russian `Макет`), e.g. `DataProcessor.Invoices.Template.Printout`, `Обработка.ПечатныеФормы.Макет.УПД`, `Catalog.Products.Template.Label`.

## Examples
- `{projectName: "MyProj", templatePath: "CommonTemplate.PrintForm"}`
- `{projectName: "MyProj", templatePath: "DataProcessor.Invoices.Template.Printout"}`
- `{projectName: "MyProj", templatePath: "Обработка.ПечатныеФормы.Макет.УПД"}`

## Result
- SUCCESS - a PNG image (the rendered template) on the IMAGE resource channel. The saved file is named after the last FQN segment (e.g. `Printout.png`).
- A multi-page template is stitched vertically into one PNG (one print page below the previous).
- The image is a print page at 1:1 scale, so a small template renders top-left on a mostly-white page - the content is still fully legible.

## Notes & gotchas
- Only `SpreadsheetDocument` templates render to an image. A non-spreadsheet template (BinaryData, DataCompositionSchema, etc.) returns a clear "is not a SpreadsheetDocument template" / "no spreadsheet editor page" error rather than a garbage image.
- An empty template (no content) returns "has no content to render".
- Needs a live workbench Display and runs on the UI thread; not available headless.
- A `templatePath` that does not resolve to an existing template returns "Cannot resolve template '<fqn>'. Expected a template FQN: ...". An FQN that resolves to a non-template object returns "'<fqn>' is not a template (it resolves to a <EClass>)". An unknown project returns "Project not found: <name>". Use `get_metadata_objects` / `get_metadata_details` to find the exact name.
