List a 1C configuration's CommonPicture objects together with the raster/vector variants each one carries in its `Picture.zip` manifest, rendered as a Markdown overview. For every picture you get its Name, its Synonym in the chosen language, and a table of its variants (DPI bucket, theme, interface variant, template flag, glyph size, picture direction, byte size). No image bytes are returned.

## When to use
- Get a bird's-eye view of every common picture in a configuration and how its variants are shaped.
- Spot mixed-icon mistakes at a glance - e.g. one picture that only ships a legacy 8.2 raster while its siblings ship an 8.5 vector template, or a picture that is missing a theme/DPI variant its neighbours have.
- Decide which picture (and which variant) to pull the PNG for; then call `export_common_picture` for that single picture's image bytes.

## Parameter details
- `projectName` (required) - EDT project name.
- `language` - language code for the Synonym shown next to each picture Name (e.g. `en`, `ru`). Defaults to the configuration's default language. The synonym is keyed by language **code**, not the language's display name; an unconfigured language yields an empty synonym, not an error.
- `limit` - max number of pictures rendered; default from preferences (100), clamped to 1000. A truncation notice is appended when results are capped, while **Total** still reports the full count. All of a rendered picture's variants are always shown (the limit caps pictures, not variant rows).

## Columns (per-picture variant table)
- `Name` - the Picture.zip entry name for this variant (e.g. `Picture.png`, `400.png`, an SVG master).
- `Dpi` - the screen-density bucket (e.g. `ldpi` / `mdpi` / `hdpi`), or `-` when not set.
- `Theme` - the theme name this variant targets, or `-` for the default theme.
- `InterfaceVariant` - the interface version the variant targets (e.g. 8.5 / 8.2 / 8.2 ordinary application), or `-`.
- `Template` - `Yes` when the variant is a recolorable template picture, else `-`.
- `Glyph` - the declared glyph size as `WxH`, or `-` when no glyph size is set.
- `PictureDirection` - the layout direction the variant is for (e.g. left-to-right / right-to-left), or `-`.
- `Size` - the byte size of the variant's entry inside the Picture.zip.

A picture with no multi-variant `Picture.zip` renders a note instead of a table. This is the common case of an ordinary **single-image** CommonPicture (one raster image, no variant container) - it is a valid picture, **not** a corrupt one. This slice reads only the multi-variant `Picture.zip`, so a single-image picture shows no variants here and cannot be exported by `export_common_picture` yet; single-image export is a documented follow-up.

## Examples
- Everything: `{projectName: "MyProject"}`.
- Russian synonyms, first 20 pictures: `{projectName: "MyProject", language: "ru", limit: 20}`.

## Notes & gotchas
- No base64 / image bytes are ever returned here - this is the metadata-only overview. For the PNG of one picture (raster decoded or SVG rasterized) call `export_common_picture` with that picture's FQN (e.g. `CommonPicture.MyIcon`) and an optional `variant`.
- Output is Markdown; every table cell is escaped, so a `|` in a name, synonym or direction cannot break the table.
- The picture content is read read-only inside a BM read transaction; nothing is mutated.

## Attribution
The CommonPicture Picture.zip manifest model this feature reads (variants, DPI / interface variant / theme / template flag / glyph size) was mapped from e1c's edt-bridge `edt_picture_export` tool, licensed under Apache-2.0. No source was copied - the reader is a clean-room implementation over the public EDT picture API.
