# export_common_picture

Export a 1C CommonPicture (общая картинка) as PNG and list its picture variants (dpi, theme, interface variant, direction, template flag, glyph size). Resolves the picture by FQN 'CommonPicture.<Name>' (Russian token ОбщаяКартинка accepted). Omit 'variant' to get the inventory only (no image bytes); pass variant='best'/'svg'/an exact variant name to also get that variant decoded to PNG as base64. SVG variants are rasterized to PNG. Full parameters and examples: call get_tool_guide('export_common_picture').

## Parameters
| Parameter | Required | Type | Description |
| --- | --- | --- | --- |
| projectName | yes | string | EDT project name (required). |
| fqn | yes | string | CommonPicture FQN, e.g. 'CommonPicture.МояКартинка' (Russian type token ОбщаяКартинка accepted). Required. |
| variant | — | string | Optional. Which variant to decode to PNG: 'best' (the best raster variant), 'svg' (the vector variant, rasterized), or an exact variant/entry name from the 'variants' list. Omit it to return the inventory only (no image bytes). |

## Guide
Exports a 1C **CommonPicture** (общая картинка) as **PNG** and returns its **picture-variant inventory**. The response type is JSON: `fqn`, a `variants` array and an optional `selected` object. A CommonPicture stores its image content inside a `Picture.zip` container in the model - often several *variants* (different dpi, theme, interface variant, direction, glyph size) plus, frequently, an SVG (vector) variant. This tool lets an AI both *enumerate* those variants and *see* the actual image (as base64 PNG) for review or refinement.

## When to use
- Inspect what a common picture actually looks like (get it decoded to PNG and view it).
- List a picture's variants to understand its theme / dpi / interface-variant coverage.
- Discover the exact variant name to request, before pulling one specific variant's bytes.

For the flat list of every CommonPicture in the configuration use `list_common_pictures` (Markdown, no image bytes); this tool targets one picture by FQN.

## Parameters
- `projectName` (required) - EDT project name. Omitting it returns "projectName is required".
- `fqn` (required) - the CommonPicture FQN. Omitting it returns "fqn is required". Format `CommonPicture.<Name>`; the type token is bilingual, so `ОбщаяКартинка.<Имя>` is also accepted. The `<Name>` is the object's programmatic **Name** (not its synonym).
- `variant` (optional) - which variant to decode to PNG. Omit it to return the **inventory only** (no image bytes). Accepted values:
  - `best` - the best raster variant (highest-fidelity raster the picture provides).
  - `svg` - the vector (SVG) variant, **rasterized to PNG** (see below).
  - an **exact variant/entry name** taken from the `variants[].name` list of a prior call.

## Output shape
```json
{
  "fqn": "CommonPicture.МояКартинка",
  "variants": [
    {
      "name": "<variant/entry name>",
      "dpi": "<density literal or '-'>",
      "theme": "<theme variant literal or '-'>",
      "interfaceVariant": "<interface variant literal or '-'>",
      "pictureDirection": "<direction literal or '-'>",
      "template": false,
      "glyphWidth": 0,
      "glyphHeight": 0,
      "contentType": "image/png",
      "sizeBytes": 1234
    }
  ],
  "selected": {
    "name": "<the chosen variant name>",
    "contentType": "image/png",
    "sizeBytes": 2048,
    "pngBase64": "<base64 PNG>"
  }
}
```
- `variants` is always present (possibly empty). Each entry describes one picture variant; nullable 1C enums (density / theme / interface variant / direction) are rendered as stable literal strings or `-`. An **empty** `variants` array means the picture has no multi-variant `Picture.zip` - typically an ordinary **single-image** CommonPicture (one raster image, no variant container). That is a valid picture, **not** a corrupt one; this slice reads only the multi-variant `Picture.zip`, so a single-image picture yields no variants and no `selected` (single-image export is a documented follow-up, not supported here yet).
- `variants[].contentType` is always `image/png` (it reflects the tool's uniform PNG output format, since a requested variant is *always* emitted as PNG), but **`variants[].sizeBytes` is the RAW size of that variant's source entry inside `Picture.zip`** - for a vector variant it is the **SVG source bytes**, not the size of any rasterized PNG. Do **not** treat `variants[].sizeBytes` as a PNG byte count. Only `selected.sizeBytes` (below) is the byte size of the actually emitted PNG.
- `selected` is present **only** when a `variant` was requested **and** resolved. When you omit `variant`, `selected` is absent and **no base64 image bytes are returned** - the call is a cheap inventory read.
- `selected.contentType` is always `image/png`: every variant (raster or SVG) is emitted as PNG, and `selected.sizeBytes` is that emitted PNG's byte size.

## `best` / `svg` / exact-variant semantics
- `best` picks the best raster variant of the picture and decodes it to PNG.
- `svg` picks the vector variant and rasterizes it to PNG.
- An exact name pulls exactly that entry from `variants` and decodes it to PNG.
- If you request a `variant` that cannot be resolved against a picture that **does** have variants (an unknown exact name, or `svg` on a picture with no vector variant), the call **errors**: `"Unknown variant '<variant>' for CommonPicture '<fqn>'. Available: <names> (or the keywords 'best'/'svg')."`. The message lists the valid entry names so you can pick one; the error carries no `selected`/`variants` payload. A single-image picture (no multi-variant `Picture.zip`) has an empty `variants` inventory and no entry to select, so a `variant` request there is **not** an error - `selected` is simply absent (single-image export is not yet supported).

## SVG -> PNG note
CommonPictures often ship a vector (SVG) variant. This tool never returns raw SVG markup: the SVG variant is **rasterized to a PNG** using the platform's own picture rasterizer, so `selected.pngBase64` is always a viewable bitmap regardless of the source variant's format.

## Examples
- Inventory only (no image bytes): `{projectName: "MyProj", fqn: "CommonPicture.МояКартинка"}`
- Best raster variant as PNG: `{projectName: "MyProj", fqn: "CommonPicture.МояКартинка", variant: "best"}`
- Vector variant rasterized to PNG: `{projectName: "MyProj", fqn: "CommonPicture.МояКартинка", variant: "svg"}`
- A specific variant by exact name: `{projectName: "MyProj", fqn: "CommonPicture.Logo", variant: "<name from variants>"}`
- Russian type token: `{projectName: "MyProj", fqn: "ОбщаяКартинка.Логотип", variant: "best"}`

## Notes & gotchas
- Resolve by programmatic **Name**, not synonym. A wrong name returns "Cannot resolve CommonPicture '<fqn>'. ..."; use `get_metadata_objects` (or `list_common_pictures`) to find the exact name.
- An FQN that resolves to a non-picture object returns "'<fqn>' is not a CommonPicture (it resolves to a <EClass>).".
- The picture content is read strictly inside a read-only model transaction; the model is never modified.
- Needs the project's BM model to be available (open the project in EDT); otherwise returns a "BM model is not available" error.

## Credit
The zip-content decode / rasterize approach is informed by the public, Apache-2.0-licensed `edt_picture_export` in the edt-bridge project. No source was copied; the picture bytes are read exclusively through the public EDT picture API.

---
*Generated from the live MCP server (`get_tool_guide`) by `docs/generate_tool_docs.py`. Do not edit this file. Edit the tool's description/schema in its Java source and its guide body in `mcp/bundles/com.ditrix.edt.mcp.server/guides/<tool>.md`.*
