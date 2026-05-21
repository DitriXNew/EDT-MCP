# Working with SSL (Standard Subsystems Library / БСП — Библиотека стандартных подсистем)

> This file is applicable **only** if your project uses SSL. If SSL is not used — remove the reference to this file from `INDEX.md`.

## Core principle

**Before writing your own code — check whether SSL already provides a solution.** A custom implementation on top of a ready-made SSL module is technical debt.

## Workflow for new functionality

1. Search in SSL — `search_in_code` over the project filtered by common modules, or `get_metadata_objects` with `metadataType: commonModules` and `nameFilter` by a thematic keyword (`Files`, `Email`, `Print`, `Users`, ...).
2. Study the methods of the found module — `get_module_structure`, `read_method_source` for the ones of interest.
3. If a solution exists — use it.
4. Only if no solution exists — write your own. And in a comment explain why SSL was not suitable.

## Key SSL modules

| Module | Purpose |
|---|---|
| `Пользователи` | Users, roles, access rights |
| `РаботаСФайлами` | Files and attached objects |
| `УправлениеПечатью` | Print forms |
| `ДлительныеОперации` | Background jobs with progress |
| `ВерсионированиеОбъектов` | Object version history |
| `РаботаСПочтовымиСообщениями` | Sending mail |
| `ОбщегоНазначения` / `ОбщегоНазначенияКлиентСервер` | General utilities |
| `СтроковыеФункцииКлиентСервер` | String utilities |
| `РаботаСКурсамиВалют` | Currency exchange rates |

## Frequently used utilities

| Function | Purpose |
|---|---|
| `ОбщегоНазначенияКлиентСервер.СвойствоСтруктуры(Стр, "Ключ", Умолч)` | Safe access to a structure field |
| `ОбщегоНазначения.МенеджерОбъектаПоСсылке(Ссылка)` | Manager by reference (instead of `Выполнить("Справочники." + Имя)`) |
| `ОбщегоНазначения.ПодсистемаСуществует(Имя)` | Check whether a subsystem exists before a conditional module call |
| `ОбщегоНазначения.ОбщийМодуль(Имя)` | Get a module by name for dynamic calls |
| `ОбщегоНазначения.ЭтоСсылка(Значение)` | Type check — is it a reference |
| `ОбщегоНазначения.СсылкаСуществует(Ссылка)` | Whether an object exists in the database |
| `ОбщегоНазначения.ЗначениеРеквизитаОбъекта(Ссылка, "Реквизит")` | Get an attribute by reference without loading the full object |
| `СтроковыеФункцииКлиентСервер.СтрокаСЧисломПредметов(Кол, "документ, документа, документов")` | Word declension by count |

## Module suffix convention

| Suffix | Environment | Cache | Example |
|---|---|---|---|
| (no suffix) | Server | No | `ОбщегоНазначения` |
| `Клиент` | Client | No | `ОбщегоНазначенияКлиент` |
| `КлиентСервер` | Both | No | `ОбщегоНазначенияКлиентСервер` |
| `ПовтИсп` | Server | Per session | `ОбщегоНазначенияПовтИсп` |

- In client code look first for `*КлиентСервер`, then `*Клиент`.
- In server code — the base module (no suffix) or `*Сервер`.
- `*ПовтИсп` — session-scoped cache, for frequently requested reference data.

## SSL modification markers

Any change to an SSL module **must** be wrapped in markers. This is needed so that when SSL is updated, it is clear which places were modified.

### Marker format

- Opening: `// <<МОДИФИКАЦИЯ` (no space between `<<` and the word). Optionally — a short note after a space or ` - `: `// <<МОДИФИКАЦИЯ - Расширен список адресатов`.
- Closing: `// >>`.
- For a single-line change (modified signature, etc.) a trailing marker only is acceptable: `Процедура ИмяПроцедуры(...) Экспорт // <<МОДИФИКАЦИЯ` — without `// >>`.
- Markers are **merge metadata**, not a journal. Do not write task numbers, dates, or author names in them — that is what git is for.

> **Note:** Different companies/teams use their own prefix (e.g. `<<ACME`, `<<КОМПАНИЯ`). Agree on the prefix within the team and use it consistently.

### Preserving the original line

When **replacing** or **deleting** an SSL line — leave the original commented out inside the block. When updating SSL this will show exactly what was replaced and with what.

```bsl
// <<МОДИФИКАЦИЯ
//	ШаблоныЗаданий.Добавить(Метаданные.РегламентныеЗадания.ПолучениеИОтправкаЭлектронныхПисем.Имя);
ШаблоныЗаданий.Добавить("ПолучениеИОтправкаЭлектронныхПисем");
// >>
```

For pure **additions** (no SSL line is replaced) — simply wrap the new code:

```bsl
// <<МОДИФИКАЦИЯ - Заполнение списка доступных для связи документов
СписокДокументов.Добавить("#Задание", НСтр("ru = 'Задание'"));
СписокДокументов.Добавить("#ЗаказКлиента", НСтр("ru = 'Заказ клиента'"));
// >>
```

For pure **deletions** — the deleted SSL line is left commented out inside the markers, without a replacement.

### When to apply

- Any edit to a `.bsl` SSL module (`СтандартныеПодсистемы.*`, `ОбщегоНазначения`, `Пользователи`, `РаботаСФайлами`, etc.).
- Edits in external libraries that you update from outside (e.g. telephony libraries) — the same convention.
- Your own project modules — markers are **not needed**; this is your code and will not be affected when SSL is updated.

## The `Удалить` prefix — deprecated objects

In SSL-based configurations, metadata objects and attributes prefixed with `Удалить` (e.g. `УдалитьКонтрагент`, `УдалитьАдрес`) are deprecated stubs kept for backward compatibility. Treat them as non-existent: do not read, write, reference, or extend them in new code.
