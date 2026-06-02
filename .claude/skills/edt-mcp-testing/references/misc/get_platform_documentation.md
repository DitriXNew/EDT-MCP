# get_platform_documentation — how to test

**Purpose.** Returns 1C:Enterprise *platform* documentation (not your configuration) as Markdown: for a platform **type** (`Array`/`Массив`, `ValueTable`/`ТаблицаЗначений`, `Structure`) it lists Type Info flags, collection element types, constructors, methods, properties and events; for a **builtin** global function (`Message`/`Сообщить`, `Format`) it lists category, return-value flag, parameter sets (overloads) and return type. Source: `GetPlatformDocumentationTool.java`. Data comes from EDT's platform type model (`IEObjectProvider.Registry` over `McorePackage.Literals.TYPE`/`TYPE_ITEM` for types, `METHOD` for builtins) — independent of your config objects.

**Preconditions.**
- Live EDT (non-elevated copy), MCP on `:8765`, workspace `D:\WS\EDT`, project `TestConfiguration` open and `State=ready` (check `list_projects` first).
- `projectName` is **optional** and only selects the *platform version* used to pick the type provider. If omitted, the tool uses the first available workspace project's version, falling back to `Version.LATEST`. It does **not** read config objects, so any open project (`TestConfiguration` or `IRP`) is fine; you still want the workspace open so the provider registry is populated.
- Runs on the UI thread (`Display.syncExec` via `PlatformUI.getWorkbench().getDisplay()`). After a `-clean` redeploy give the workbench a moment; an empty provider yields "no types found" rather than a crash.
- Read-only. Does **not** mutate the model or disk. No revert needed.

**Call (real, 2026-06-02).** A known type, methods only, capped:
```
get_platform_documentation(
  typeName="Array",
  projectName="TestConfiguration",
  memberType="method",
  limit=8)
```
Required arg is only `typeName`. Others: `category` (`type` default | `builtin`), `memberName` (case-insensitive partial filter on method/property/event names — matches EN name, RU name, or display name), `memberType` (`all` default | `method` | `property` | `constructor` | `event`), `language` (`en` default | `ru` — switches which of EN/RU is the primary `# Header` and member name; both are always shown as `EN / RU`), `limit` (default 50, hard cap 200), `projectName` (version only).

**Result.** `ResponseType.MARKDOWN` — delivered as a resource (`embedded://doc-<typename>.md`, e.g. `doc-array.md`; the name is `getResultFileName`), NOT JSON, NO YAML frontmatter (plain Markdown body). Real output for the call above:
```
# Array / Массив

**Type Info:**
- Iterable: Yes
- Index accessible: Yes
- Created by New: Yes

**Collection element types:** Arbitrary

## Methods

### Add / Добавить

**Parameters:**
- `Value` (Arbitrary) - *optional* - *out*

### Count / Количество

*Returns a value*

**Returns:** Number

### Find / Найти

*Returns a value*

**Parameters:**
- `Value` (Arbitrary) - *out*
**Returns:** Number | Undefined

*Results limited to 8 items.*
```
A **builtin** (`category="builtin"`, real, `typeName="Message"`):
```
# Message / Сообщить

**Category:** Built-in function (global method)

*Procedure (no return value)*

## Parameters

**Parameters:**
- `MessageText` (String) - *out*
- `Status` (MessageStatus) - *optional* - *out*
```
Section order for a type: `# <Name>` → `**Type Info:**` → `**Collection element types:**` (only if any) → `## Constructors` → `## Methods` → `## Properties` → `## Events` (sections appear only when present and when `memberType` allows them). Param lines read `` - `Name` (Type1 | Type2) - *optional* - *out* `` (`*optional*` = has default value, `*out*` = passed by reference). A trailing `*Results limited to N items.*` is appended only when the combined member count hits `limit`.

**Gotchas.**
- **Not-found stays Markdown (informational), not an error.** A misspelled/unknown `typeName` returns a plain-Markdown listing, NOT the `{success:false,error}` error contract:
  - type miss → `Error: Type not found: <name>` followed by `Available types (first N):` and a bulleted list (up to 30, then `... (more available)`).
  - builtin miss → `Error: Built-in function not found: <name>` + `Available global methods (first N):` + bullets.
  Note the literal text begins with `Error:` but it is **markdown output with `isError` absent** — treat it as a "here are valid alternatives" listing, not a failure. Use that listing to discover exact spellings.
- **Genuine error contract.** Real failures arrive as `{success:false, error:"…"}` with `isError:true`. Concrete cases: `typeName is required` (missing/empty required arg); `Unknown category '<x>'. Supported: 'type', 'builtin'`; `Could not get type provider. Make sure EDT workspace is open.` / `Could not get method provider…` (registry empty — e.g. workbench not ready); plus any exception message caught on the UI thread.
- **Bilingual — both names always shown; `language` only reorders.** Headers and members render `EN / RU` (e.g. `Add / Добавить`, `Count / Количество`). `language="ru"` makes RU primary and EN the alternate; if a name has no RU variant only one token shows. `typeName`/`memberName` lookup is **dialect-agnostic**: you can pass `"Массив"` or `"Array"`, `"Сообщить"` or `"Message"` — matching is case-insensitive against EN name, RU name, full QN, and last QN segment. This is the platform type model, so unlike `search_in_code` (literal, other family) it is not language-locked.
- **`memberName` is a partial, multi-field filter.** It substring-matches (case-insensitive) the display name OR the EN name OR the RU name, so `memberName="Add"` finds `Add`/`Добавить` and `memberName="Кол"` finds `Count/Количество`. Combine with `memberType` to narrow further.
- **`limit` cap = 200.** Values above 200 are clamped; a non-numeric `limit` falls back to 50. The count spans all member sections combined (constructors + methods + properties + events), not per-section.
- **`category` only accepts `type` or `builtin`.** `builtin` ignores `memberName`/`memberType`/`limit` (it documents one whole global function). For type-member events, properties etc. use `category="type"` (the default).
- **Flaky output channel.** If the result text drops to a bare `Error`/`Done` or looks garbled/duplicated, do NOT retry-spam — re-verify from the EDT log `D:\WS\EDT\.metadata\.log`, which records the full request/response. After a `-clean` redeploy the provider may be momentarily empty (yields the "no types/methods found" listing) until the platform model loads.
