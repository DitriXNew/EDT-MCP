Returns 1C:Enterprise *platform* API documentation (the built-in language and type system), not configuration metadata. Use the metadata tools for catalogs, documents and your own objects; use this tool for platform types like ValueTable / Array / Structure and for global built-in functions.

## When to use

- You need the exact signature, parameters or return value of a platform method or property.
- You are unsure which members a platform type exposes.
- You need a global built-in function's description.

## Parameter details

- **typeName** (required): the type or symbol name. Both the English name and its Russian equivalent are accepted (e.g. the English 'ValueTable' or its Russian name).
- **category**: `type` (platform types, the default) or `builtin` (global built-in functions). For `builtin` only `typeName` and `language` apply; the member filters are ignored.
- **memberName**: filter the returned members by name, partial (substring) match. Example: 'Add', 'Insert', 'Count'.
- **memberType**: one of `method`, `property`, `constructor`, `event`, `all`. Default `all`. An out-of-set value is rejected with an error rather than silently matching nothing.
- **projectName**: an EDT project name used to pin which platform version's documentation to read. Optional; omit to use the default.
- **limit**: maximum number of results. Default 50, clamped to a maximum of 200.
- **language**: `en` (default) or `ru` — the language of the returned documentation text.
- **responseFormat**: `concise` (default) or `detailed`. `concise` keeps the type/function header, the Type Info block and every section and member heading (so you see the full member inventory), but omits the verbose per-member body — parameter lists, overloads, return/property types and access flags. Re-query with `detailed` (optionally narrowed by `memberName`) to get the full signatures.

## Examples

- All members of a type: `typeName='ValueTable'`.
- A specific method: `typeName='Array', memberName='Add'`.
- Only methods: `typeName='ValueTable', memberType='method'`.
- Russian output: `typeName='Structure', language='ru'`.
- A built-in function: `category='builtin', typeName='Message'`.

## Notes

- Resolution is bilingual on `typeName`: an English or Russian platform name resolves to the same type. The `language` parameter controls only the output text, not which name you may pass in.
- Output is Markdown.
