Returns 1C:Enterprise *platform* API documentation (the built-in language and type system), not configuration metadata. Use the metadata tools for catalogs, documents and your own objects; use this tool for platform types like ValueTable / Array / Structure and for global built-in functions.

## When to use

- You need the exact signature, parameters or return value of a platform method or property.
- You are unsure which members a platform type exposes.
- You need the VALUES of a system enumeration (e.g. `DateFractions`, `AccessTokenSignAlgorithm`).
- You need the API that EVERY catalog / document / register object shares — the metadata TYPE SETS
  (`CatalogObject`, `CatalogRef`, `DocumentObject`, `EnumRef`, `InformationRegisterRecordSet`, ...).
- You need a global built-in function's description.

## What the output carries

A **metadata TYPE SET** (`CatalogObject` / `СправочникОбъект`, `DocumentRef`, `EnumRef`, the
`*Manager` / `*RecordSet` / `*RecordKey` sets, ...) resolves to the generic platform type behind it —
the members every catalog object, every document reference and so on carries (`Write`, `GetObject`,
`Ref`, `IsNew`, ...). The answer is headed by that generic type and names the set it came from in a
`**Type set:**` line. Ask for the SET, not for a concrete object: `CatalogObject`, not
`CatalogObject.Currencies` — the members are the same, and a concrete object's own attributes come
from `get_metadata_details`. `AnyRef` / `ЛюбаяСсылка` is the one set with nothing to render: it
unions the reference sets and declares no members of its own, and says so.

A **system enumeration** renders a `Values` section listing every value as `<Enum>.<Value>` (with the
Russian equivalent when it differs). Such a type is not constructible, so it has no `Constructors`
section.

`detailed` output is enriched from the platform's own documentation (the syntax helper) when it is
available: the description of the type, of each method and of each property, plus what a method
returns. A documented return is rendered next to the modelled type (`**Returns:** ChartValue - <what
it means>`); when the model records no return at all but the documentation describes one, it is
labelled `**Returns (from the platform documentation):**` so a documented sentence is never mistaken
for a modelled type. A method the documentation describes as a procedure has neither, and nothing is
invented for it. On an EDT where the syntax helper is unavailable the output is exactly the
model-only one.

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
- What every catalog object can do: `typeName='CatalogObject'` (or `typeName='СправочникОбъект'`).
- One member of a type set: `typeName='DocumentObject', memberName='Write'`.
- A built-in function: `category='builtin', typeName='Message'`.

## Notes

- Resolution is bilingual on `typeName`: an English or Russian platform name resolves to the same type. The `language` parameter controls only the output text, not which name you may pass in.
- A name that resolves to nothing is answered with an error that states how many names exist, lists a sample, and offers the closest ones (`Did you mean: ...`). Every name it lists does resolve.
- Output is Markdown.
