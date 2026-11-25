# template_content

Read a SpreadsheetDocument template's areas, cells, parameters, merges and drawings, or edit spreadsheet or text template content. Use method='read', 'modify' or 'help'. Use metadata tools for the template object itself. Full parameters and examples: call get_tool_guide('template_content').

## Parameters
| Parameter | Required | Type | Description |
| --- | --- | --- | --- |
| method | yes | string (one of: help, modify, read) | help: show usage; modify: edit an existing template; read: inspect one spreadsheet template. |
| projectName | — | string | Required for read and modify: EDT project name. |
| fqn | — | string | Required for read and modify: CommonTemplate.Name or Type.Owner.Template.Name. |
| areaName | — | string | Read only: named cell area (case-insensitive). |
| offset | — | integer | Read only: per-collection offset (default 0). |
| limit | — | integer | Read only: items per collection (default 100, max 200). |
| template | — | object | Required for modify. TextDocument: {text, mode?, oldText?, expectedHash?, expectedSource?, overwrite?}; mode = 'searchReplace' (default, needs exactly one literal oldText match) / 'append' / 'replace' (the only mode that creates a missing file); replacing an existing file needs expectedHash or expectedSource from get_metadata_details, or overwrite=true. SpreadsheetDocument: {cells?, merges?, areas?, columnWidths?, rowHeights?, unmerges?, removeAreas?, renameAreas?}. cells [{row, col (both 0-based, required), replace?, text? \| parameter?, bold?, fontSize?, hAlign? (Left/Center/Right/Auto/Width), vAlign? (Top/Center/Bottom), wrap?, textOrientation? (TENTHS of a degree: 90 degrees = 900), autoIndent? (0..100), autoMarkIncomplete?, borders? ('Solid' or {style, width? 1..3}, or {left?, top?, right?, bottom?} of those), borderColor? / backColor? / textColor? ({red, green, blue}, 'Web.Red', 'Style.<Name>', 'auto')}]; merges [{fromRow, fromCol, toRow, toCol}]; areas [{name, type?, fromRow?, fromCol?, toRow?, toCol?}]: Rectangle (default) needs all four indices; Rows needs only fromRow/toRow; Columns needs only fromCol/toCol; columnWidths [{col, width?, autoWidthCalculation?, widthWeightFactor? (only with autoWidthCalculation:true)}]; rowHeights [{row, height}]; unmerges [{fromRow, fromCol, toRow, toCol}] drops every merge touching the range; removeAreas [name]; renameAreas [{name, newName}]; these three run first. Omitted or null cell keys keep their values; replace:true resets them. Non-empty text clears parameter and vice versa; '' clears the named one. false / 0 on a format key overrides inheritance, but wrap:false restores inherited placement. Other cells, merges and areas are kept. |

## Guide
## Methods

- `help`: return this guide. No project, FQN or payload is needed.
- `read`: inspect one SpreadsheetDocument without changing it. Supply `projectName` and `fqn`.
- `modify`: edit an existing SpreadsheetDocument or TextDocument. Supply `projectName`, `fqn` and the `template` object. Use the English or Russian type/kind tokens and the programmatic object name, not its synonym. The former `modify_metadata` singular `template` payload now belongs here.

Create and delete the template object with `create_metadata` and `delete_metadata`; rename it with `rename_metadata_object`. Edit its content here. Binary, graphical and DCS content are not supported by this tool; use `dcs` for data composition schemas. Vendor locks and extension restrictions still apply.

## Reading a spreadsheet template

For print-form review, call
`{method:'read', projectName:'P', fqn:'DataProcessor.Invoices.Template.Printout', areaName:'Header'}`.
Omit `areaName` to list the whole template page by page. An exact area name wins; otherwise the
case-insensitive match must be unique. A missing area or a drawing name is refused. `get_template_screenshot` shows rendering, not these stored values.
Read text templates instead with `get_metadata_details`.

The result is `{method:'read', fqn, template:{areas, cells, merges, drawings, offset, limit, nextOffset?, areaName?}}`.
All indexes are 0-based, and range bounds are inclusive:
- `areas`: named cell areas with `name`, `type` (`rows`, `columns`, or `rectangle`) and the relevant
  `fromRow`, `toRow`, `fromCol`, `toCol` bounds. A whole-axis area omits the other axis.
- `cells`: cells with text or a parameter, with `row`, `col` and the fill `type` (`text`, `parameter`, or
  `template`). A cell may have `text` or `parameter`, and independently `detailParameter` (drill-down)
  and `pictureParameter`. Cells carrying only a detail or picture parameter are included.
  `template` means stored mixed template text, not parsed or evaluated parameters. Text carries the
  selected `language` (current, default, neutral `#`, then first nonempty translation). Formatting-only
  cells and non-text typed values are not enumerated.
- `merges`: stored rectangle/row/column merge declarations with the same bounds as areas, not a
  computed rendering of inherited merges or unmerge overrides.
- `drawings`: top-level `id`, model `type`, `name` where defined, and bounding cell `anchor` where
  both positions exist. `detailParameter` is included when present; text drawings with PARAMETER fill
  can also have `parameter`. Image bytes, object contents, and anchor offsets are not returned.

A filter narrows cells to the area and returns intersecting merges/drawings with their original bounds.
Use `limit` (default 100, maximum 200) and `offset` (default 0) applied to **each** collection after
filtering. Use the returned `nextOffset` unchanged until it is absent; a shorter collection may
already be empty. A page can contain fewer items than requested to keep both JSON and text-only
clients below the response size limit. Pages are in model order; read them while the template is unchanged.
The tool remains disabled in read-only presets because it also has a write method.
Truncated strings are marked with `<key>Truncated:true`: the limit is 2048 UTF-16 units, or 1024
for detail/picture parameters and drawing parameter names. The read never creates a missing content
resource or exports a write.

## Authoring a spreadsheet template
First create a **SpreadsheetDocument** (the default template type) with `create_metadata`: `CommonTemplate.<Name>` (shared) or `<Type>.<Owner>.Template.<Name>` (owned). Writes use a BM transaction and force-export `.mxlx` before a persisted success; no `clean_project` is needed. Visually verify with `get_template_screenshot`.

The `template` object takes any of these arrays (all optional; an omitted array leaves that aspect untouched; indices are 0-based, ranges inclusive):
- `cells` - `[{row, col, replace?, text?, parameter?, bold?, fontSize?, hAlign?, vAlign?, wrap?, textOrientation?, autoIndent?, autoMarkIncomplete?, borders?, borderColor?, backColor?, textColor?}]`; `row`, `col` required:
  - `text` - static text (or the text of an existing mixed template cell; a patch keeps its fill kind). The neutral value and the project's language-code values are stored so both EDT and 1C can display the text. For extensions this includes base languages.
  - A text patch updates the active sheet language (or the project default), fills missing languages and refreshes copies of the previous neutral value; other translations are kept. `replace:true` resets them.
  - `parameter` - a print-time PARAMETER name filled by BSL (`ОбластьМакета.Параметры.<Name>`). A cell holds text or a parameter, not both.
  - `bold` (`false` disables), `fontSize` (points, positive), `hAlign` `Left` / `Center` / `Right` / `Auto` / `Width`, `vAlign` `Top` / `Center` / `Bottom`.
  - `wrap` - `true` word-wraps; `false` unsets placement (inherits); also `Auto` / `Cut` / `Block` / `Wrap`.
  - `textOrientation` - rotation in **tenths of a degree**, `0..3600`, unscaled (`900` = 90 degrees; EDT's editor shows degrees). `autoIndent` `0..100` (greyed out while rotated). `autoMarkIncomplete` - `true` marks the cell incomplete while empty.
  - `borders` - one line for all four sides, or `{left?, top?, right?, bottom?}`. A line is a style - `Solid`, `Dotted`, `Double`, `ThinDashed`, `ThickDashed`, `LargeDashed`, or `None` (no line) - or `{style, width?}`, width `1..3` (default 1). On a merge's top-left cell the lines outline the merged range.
  - `borderColor` (all four sides), `backColor`, `textColor` - a colour: `{red, green, blue}`, `'Style.<Name>'` (`Palette.`, `Web.`, `Windows.`) or `{style:'<Name>'}`; `'auto'` unsets it (inherits). A style / palette name the platform also has means the platform colour, as EDT reads the template back. If the platform catalogue cannot be checked, the write is refused rather than guessing a configuration colour.
  - Cells **MERGE by default**: sent properties change, omitted ones keep their values; `replace:true` resets omitted content and formatting. Non-empty `text` / `parameter` clears the other; `text:""` / `parameter:""` clears just that one (`{row:1,col:0,parameter:""}` unbinds, keeping formatting).
- `merges` - `[{fromRow, fromCol, toRow, toCol}]`: a merged cell range.
- `areas` - a named area for BSL `ПолучитьОбласть("<name>")` / `Вывести`:
  - `{name, type:'Rows', fromRow, toRow}` covers whole rows and uses the first row's column layout.
  - `{name, type:'Columns', fromCol, toCol}` covers whole columns in the default column layout.
  - `{name, type:'Rectangle', fromRow, fromCol, toRow, toCol}` covers a cell rectangle. Omit `type` for the existing rectangle behavior.
  - For `Rows` and `Columns`, omit the opposite-axis coordinates. All ranges are inclusive.
- `columnWidths` - `[{col, width?, autoWidthCalculation?, widthWeightFactor?}]`, at least one sizing member: a fixed positive `width`; `autoWidthCalculation:true` lets the platform compute it (a `width` beside it is the minimum); `widthWeightFactor` - the column's share of free width, REFUSED without `autoWidthCalculation:true` (only auto-width columns read it). The two auto members need 1C:Enterprise **8.3.10+** (`Runtime-Version`); below that they are refused, not silently dropped.
- `rowHeights` - `[{row, height}]`.
- `unmerges` - `[{fromRow, fromCol, toRow, toCol}]`: removes every merge touching the range; as in EDT's Unmerge, each freed cell takes the merged cell's format, borders kept only on the outer edge, even when the row, column or document has inherited borders (a covered cell with its own text or parameter keeps its fill kind; with no top-left cell the cells stay as they are).
- `removeAreas` - `['<name>']`; `renameAreas` - `[{name, newName}]`. `newName` uses Latin letters, Russian letters (excluding `ё`/`Ё`), digits and `_`, as in EDT's template editor. Digits can also appear first. An unknown name, a `newName` already used (ignoring case) or a non-cell named item (a drawing) is refused, listing the existing areas.

`unmerges`, `removeAreas` and `renameAreas` run first, so one call can clear an old layout and author the new one; other cells, merges and areas are kept. An explicit `null` counts as omitted. New / replaced cells inherit omitted formatting from row / column / document. Explicit `0` / `false` overrides inheritance (except `wrap:false`). Cell-only keys (`textOrientation`, `autoIndent`, `autoMarkIncomplete`, borders, colours) and column-only keys (`autoWidthCalculation`, `widthWeightFactor`) are refused in the other's array.

A malformed entry (a bad token, a missing `row` / `col`, a non-positive size, an out-of-range value) is a clean error and nothing is written. The result's `template` counts object is `{cells, merges, areas, columnWidths, rowHeights, unmerges, removedAreas, renamedAreas}` (`unmerges` = merges removed).

## Authoring a text template

Address a TextDocument by `projectName` and FQN: `CommonTemplate.Name` or `<Type>.<Owner>.Template.<Name>`. TYPE/KIND tokens accept English/Russian; names are programmatic identifiers, never synonyms. Read exact text and `contentHash` with `get_metadata_details`, e.g. `objectFqns:["DataProcessor.Invoices.Template.Contract"]`.

Pass `method:'modify'` with a `template` object with required string `text` and:

- `searchReplace` (default): `{oldText:"unique literal", text:"replacement", expectedHash:"..."}`. Non-empty `oldText` must match exactly once, counting overlapping matches; zero/multiple matches refuse without changes. Include the exact read line separators in multiline matches.
- `append`: `{mode:"append", text:"suffix", expectedHash:"..."}` adds exact text with no inserted separator.
- `replace`: `{mode:"replace", text:"full text", expectedHash:"..."}` replaces all content; empty `text` clears it. Only this mode creates a missing Template.txt; first create the template object with `create_metadata`.

Replacing any existing file, even empty, requires `expectedHash`, `expectedSource` (the full text last read), or explicit `overwrite:true`; legacy text-only calls must add `mode:"replace"` and one of these. In every mode, supplied guards apply even with overwrite. Mismatches change no file; a hash mismatch returns the current hash. Hash/source comparisons normalize CRLF to LF; writes never normalize text. Hash quotes, surrounding whitespace and case are tolerated as in `write_module_source`. Absent/empty expectedHash disables that check; a missing file compares as empty, so its empty hash permits guarded replace. Guards and match counts are rechecked under the file modification rule immediately before writing. Vendor support is rechecked inside the BM write transaction on both template and owner, using the shared refusal.

EDT's file API writes Template.txt inside that BM boundary. New text uses the inherited charset (normally UTF-8), with no added BOM. Existing encoding/UTF-8 BOM, supplied line endings and final newline are preserved. Malformed existing text or unencodable new text refuses before writing; fix the encoding in EDT or supply representable text. `text` on non-text templates and spreadsheet keys (`cells`, `areas`, etc.) on text templates are refused. The result is `template:{contentHash:"..."}` for the full edited text.

## Examples

- Author a title cell in a common template: `{method:'modify', projectName:'P', fqn:'CommonTemplate.InvoiceForm', template:{cells:[{row:0, col:0, text:'INVOICE', bold:true, fontSize:14, hAlign:'Center'}], merges:[{fromRow:0, fromCol:0, toRow:0, toCol:3}]}}`
- Add a print-time parameter cell and a named area to an object-owned template: `{method:'modify', projectName:'P', fqn:'DataProcessor.Invoices.Template.Printout', template:{cells:[{row:2, col:1, parameter:'CustomerName', wrap:true, vAlign:'Center'}], areas:[{name:'Header', fromRow:0, fromCol:0, toRow:1, toCol:3}]}}`
- Set column widths and a row height: `{method:'modify', projectName:'P', fqn:'CommonTemplate.InvoiceForm', template:{columnWidths:[{col:0, width:30}, {col:1, width:60}], rowHeights:[{row:0, height:24}]}}`
- Rotate a narrow column header 90 degrees (900 tenths) and word-wrap it: `{method:'modify', projectName:'P', fqn:'CommonTemplate.InvoiceForm', template:{cells:[{row:0, col:5, text:'Quantity', textOrientation:900, wrap:true}]}}`
- Let two columns share the free width 1:2: `{method:'modify', projectName:'P', fqn:'CommonTemplate.InvoiceForm', template:{columnWidths:[{col:0, autoWidthCalculation:true, widthWeightFactor:1}, {col:1, autoWidthCalculation:true, widthWeightFactor:2}]}}`

## Result

A successful edit returns `action:'modified'`, canonical `fqn`, `persisted`, and either the spreadsheet counts or the text `contentHash` inside `template`. When validation markers are included, `markersIncomplete:true` means an empty marker list must not be interpreted as clean.

## Migration

Replace `modify_metadata({projectName, fqn, template})` with `template_content({method:'modify', projectName, fqn, template})`. The payload and edit guards are unchanged.

---
*Generated from the live MCP server (`get_tool_guide`) by `docs/generate_tool_docs.py`. Do not edit this file. Edit the tool's description/schema in its Java source and its guide body in `mcp/bundles/com.ditrix.edt.mcp.server/guides/<tool>.md`.*
