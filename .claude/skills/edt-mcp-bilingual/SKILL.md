---
name: edt-mcp-bilingual
description: Correctness checklist for 1C's bilingual (Russian/English) model in EDT-MCP — synonym language-code vs name, object resolution by Name vs synonym vs TYPE token, dialect-aware vs literal code search. Use when reading, writing, resolving, formatting or searching metadata objects, synonyms, BSL identifiers or query keywords.
---

# EDT-MCP — корректность двух языков (ru/en)

1С двуязычна на нескольких уровнях. Большинство багов плагина — именно здесь. Перед правкой любого инструмента, который резолвит/читает/пишет/ищет метаданные или код, пройти этот чеклист.

## 1. Синоним метаданных ключуется по КОДУ языка

Синоним — `EMap<String,String>`, ключ = **код языка** (`"ru"`, `"en"`), а НЕ имя объекта `Language`.

- ✅ Писать/читать через `MetadataLanguageUtils` (вводится в `unify-metadata-language-code-resolution`): `resolveLanguageCode(config, explicit)` и `getSynonymForLanguage(map, code)`.
- ✅ Эталон логики резолва языка: `CreateMetadataObjectTool.resolveLanguage` — explicit → `getDefaultLanguage().getLanguageCode()` → код первого языка → null.
- ❌ НЕ `config.getDefaultLanguage().getName()` (вернёт «Russian»/«Русский» — имя, не ключ; на мультиязычной конфигурации молча промахнётся мимо EMap).
- ❌ НЕ хардкодить `"ru"` как fallback — брать код первого настроенного языка.

## 2. Объект резолвится по программному Name; TYPE-токен — двуязычный

- Имя объекта (сегмент после типа) — это **программный идентификатор**, резолв по `getName()` (см. `MetadataTypeUtils.findObject`). Синоним как идентификатор НЕ принимается.
- TYPE-токен (Справочник/Catalog, Документ/Document …) — двуязычный, обрабатывается `MetadataTypeUtils` (`toEnglishSingular`, `normalizeFqn`, `getAllFqnVariants`).
- В описании схемы писать честно: «русским/английским может быть только TYPE-токен; имя объекта — программный идентификатор, не синоним». Не вводить пользователя в заблуждение формулировкой «Russian names supported».

## 3. Запись синонима — симметрично и через общий резолвер

- При создании объекта/реквизита, если задан синоним — писать `getSynonym().put(resolveLanguageCode(...), synonym)`.
- Write-инструмент должен **возвращать** записанный `synonym`+`language` в ответе (симметрия с read-инструментами). См. `metadata-write-synonym-symmetry`.

## 4. BSL: диалекты ru/en

- AST/индексные инструменты (find_references, get_symbol_info, go_to_definition, call_hierarchy) диалект-aware — резолв по имени символа с `equalsIgnoreCase`, нормализация не нужна.
- `search_in_code` — **литеральный** matcher, НЕ диалект-aware: поиск английского ключевого слова не найдёт русский эквивалент. Это должно быть честно отражено в описании инструмента; для поиска идентификаторов направлять на AST-инструменты. См. `search-in-code-dialect-awareness`.
- Регулярки с кириллицей — экранировать `\uXXXX` (как в `BslSyntaxChecker`), не сырыми UTF-8 литералами (риск порчи при не-UTF-8 сборке). См. `bsl-cyrillic-pattern-escaping`.

## 5. Запросы 1С двуязычны

Ключевые слова запроса имеют ru/en диалекты (SELECT/ВЫБРАТЬ, FROM/ИЗ, WHERE/ГДЕ). `validate_query` делегирует парсеру платформы (диалект-aware) — не предполагать единственный диалект; UTF-8 при передаче текста запроса.

## Тест-инвариант

Любая правка резолва/чтения/записи должна иметь тест, проверяющий **оба** языка: один объект адресуется английским Name, русским Name и (где применимо) синонимом — результат идентичен; синоним ключуется по коду языка, не по имени. См. карточки `tests-metadata-bilingual-and-formatters`, `tests-code-navigation-bilingual`, `tests-validate-query-ru-keywords`.
