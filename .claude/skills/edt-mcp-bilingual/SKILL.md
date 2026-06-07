---
name: edt-mcp-bilingual
description: Correctness checklist for 1C's bilingual (Russian/English) model in EDT-MCP — synonym language-code vs name, object resolution by Name vs synonym vs TYPE token, dialect-aware vs literal code search. Use when reading, writing, resolving, formatting or searching metadata objects, synonyms, BSL identifiers or query keywords.
---

# EDT-MCP — two-language (ru/en) correctness

1C is bilingual at several layers, and most plugin bugs live here. Before editing any tool that resolves/reads/writes/searches metadata or code, walk this checklist.

## 1. The metadata synonym is keyed by the language CODE

The synonym is an `EMap<String,String>` whose key is the **language code** (`"ru"`, `"en"`), NOT the `Language` object's name.

- ✅ Write/read through `MetadataLanguageUtils`: `resolveLanguageCode(config, explicit)` and `getSynonymForLanguage(map, code)`.
- ✅ Reference language-resolution logic: `CreateMetadataObjectTool.resolveLanguage` — explicit → `getDefaultLanguage().getLanguageCode()` → the first language's code → null.
- ❌ NOT `config.getDefaultLanguage().getName()` (returns "Russian"/"Русский" — the name, not the key; on a multi-language config it silently misses the EMap).
- ❌ Don't hardcode `"ru"` as the fallback — use the first configured language's code.

## 2. An object resolves by its programmatic Name; the TYPE token is bilingual

- The object name (the segment after the type) is a **programmatic identifier**, resolved by `getName()` (see `MetadataTypeUtils.findObject`). A synonym is NOT accepted as an identifier.
- The TYPE token (Справочник/Catalog, Документ/Document …) is bilingual, handled by `MetadataTypeUtils` (`toEnglishSingular`, `normalizeFqn`, `getAllFqnVariants`).
- Say it honestly in the schema description: "only the TYPE token may be Russian/English; the object name is a programmatic identifier, not a synonym." Don't mislead with "Russian names supported".

## 3. Writing a synonym — symmetric, through the shared resolver

- When creating an object/attribute, if a synonym is given, write `getSynonym().put(resolveLanguageCode(...), synonym)`.
- A write tool must **return** the written `synonym`+`language` in its response (symmetry with read tools).

## 4. BSL: ru/en dialects

- AST/index tools (find_references, get_symbol_info, go_to_definition, call_hierarchy) are dialect-aware — they resolve by symbol name with `equalsIgnoreCase`, no normalization needed.
- `search_in_code` is a **literal** matcher, NOT dialect-aware: searching an English keyword won't find its Russian equivalent. Reflect this honestly in the tool description; for identifier search, direct the user to the AST tools.
- Cyrillic in regexes — escape via `\uXXXX` (as in `BslSyntaxChecker`), not raw UTF-8 literals (corruption risk under a non-UTF-8 build).

## 5. 1C queries are bilingual

Query keywords have ru/en dialects (SELECT/ВЫБРАТЬ, FROM/ИЗ, WHERE/ГДЕ). `validate_query` delegates to the platform parser (dialect-aware) — don't assume a single dialect; keep UTF-8 when passing query text.

## Test invariant

Any change to resolution/reading/writing must have a test covering **both** languages: one object addressed by its English Name, Russian Name, and (where applicable) synonym — identical result; the synonym keyed by language code, not by name. Reference: `WriteModuleSourceToolTest.testResolveRussianObjectName`.
