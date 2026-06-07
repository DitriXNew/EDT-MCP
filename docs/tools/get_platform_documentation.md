# get_platform_documentation

Look up 1C:Enterprise platform documentation for built-in types (ValueTable, Array, Structure) and global built-in functions, including their methods, properties, constructors and events. Use when you need the exact platform API signature rather than configuration metadata. Full parameters and examples: call get_tool_guide('get_platform_documentation').

## Parameters
| Parameter | Required | Type | Description |
| --- | --- | --- | --- |
| typeName | yes | string | Type or symbol name, English or Russian (e.g. 'ValueTable'). |
| category | — | string (one of: type, builtin) | 'type' (platform types) or 'builtin' (global functions). Default: 'type'. |
| memberName | — | string | Filter members by name, partial match (e.g. 'Add'). |
| memberType | — | string (one of: method, property, constructor, event, all) | Filter by member kind. Default: 'all'. |
| projectName | — | string | EDT project name to pin the platform version. Optional. |
| limit | — | integer | Max results. Default: 50, max 200. |
| language | — | string (one of: en, ru) | Output language. Default: 'en'. |
| responseFormat | — | string (one of: concise, detailed) | 'concise' (default) = leaner: headers + member names only; 'detailed' = full member signatures, parameters, types and flags. |

## Guide
Returns 1C:Enterprise *platform* API documentation (the built-in language and type system), not configuration metadata. Use the metadata tools for catalogs, documents and your own objects; use this tool for platform types like ValueTable / Array / Structure and for global built-in functions.

## When to use

- You need the exact signature, parameters or return value of a platform method or property.
- You are unsure which members a platform type exposes.
- You need a global built-in function's description.

## Parameters

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

---
*Generated from the live MCP server (`get_tool_guide`) by `docs/generate_tool_docs.py`. Edit the tool's Java source, not this file.*
