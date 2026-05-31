---
name: edt-mcp-architecture
description: Map of the EDT-MCP plugin's target architecture — where the shared helpers live, the layering rules, and the canonical way to do project/metadata/code resolution. Use when starting work in this plugin, deciding where new code belongs, or before adding/refactoring an MCP tool.
---

# EDT-MCP — целевая архитектура

Плагин 1C:EDT с MCP-сервером (~62 инструмента). Этот скилл — карта **как должно быть** (мы в процессе рефакторинга к общим хелперам). Если хелпер ниже ещё не существует в коде — это задача рефакторинга, см. ссылку на карточку в `.devtool/features/`.

## Раскладка пакетов

Корень: `mcp/bundles/com.ditrix.edt.mcp.server/src/com/ditrix/edt/mcp/server`

| Пакет | Что лежит | Правило |
|---|---|---|
| `tools/impl/` | по одному классу на MCP-инструмент (`implements IMcpTool`) | **только зарегистрированные инструменты**; утилиты/базы здесь не место |
| `tools/` | `IMcpTool`, `McpToolRegistry` | контракт и реестр |
| `tools/metadata/` | форматтеры метаданных | рендер вывода read-инструментов |
| `utils/` | общие хелперы | сюда выносятся переиспользуемые куски |
| `protocol/` | MCP/JSON-RPC слой: `ToolResult`, `JsonUtils`, `JsonSchemaBuilder` | сборка ответа/схемы |
| `tags/`, `groups/` | две Navigator-фичи | должны делить общую базу (см. ниже) |
| `McpServer.java`, `Activator.java` | транспорт и жизненный цикл OSGi | не каталог инструментов |

## Канонические общие API (использовать, не дублировать)

- **Резолв проекта** → `ProjectContext.resolve(projectName)` → `{IProject, IV8Project, Configuration, BM}`. НЕ повторять `ResourcesPlugin.getWorkspace()...` вручную (так делают ~38 инструментов — это и есть главный дубль). _Вводится: `introduce-project-context-resolver`._
- **Доступ к модели BM** → `BmTransactions.readModel(...)` / `writeModel(...)`. Чтения — в read-границе, записи — в write. _Вводится: `introduce-bm-transactions-helper`._
- **Параметры инструмента** → типизированный `ToolParams` (getString/getInt/getBool/getEnum + required). НЕ парсить сырую `Map<String,String>` руками. _Вводится: `introduce-tool-params-accessor`._
- **Резолв объекта/типа метаданных (двуязычно)** → `MetadataTypeUtils` (СУЩЕСТВУЕТ: `findObject`, `normalizeFqn`, `getAllFqnVariants`). Это общий двуязычный резолвер — не писать свой.
- **Язык синонимов** → `MetadataLanguageUtils.resolveLanguageCode(config, explicit)` + `getSynonymForLanguage(map, code)`. Ключ синонима = **код языка** (`getLanguageCode()`), НЕ `getName()`. _Вводится: `unify-metadata-language-code-resolution`._
- **Результат/ошибка** → `ToolResult` (СУЩЕСТВУЕТ). Ошибки только через `ToolResult.error(...)`, не голой строкой.
- **Пагинация** → общий `Pagination` (единый `limit`/`offset`, единый формат «обрезано»). _Вводится: `standardize-pagination`._
- **Markdown-таблицы** → общий билдер, не StringBuilder в каждом инструменте. _Вводится: `shared-markdown-and-result-builders`._
- **Регистрация инструментов** → `BuiltInToolRegistrar`, не список внутри `McpServer`. _Вводится: `extract-builtin-tool-registrar`._

## Чего НЕ делать (анти-паттерны, выявленные ревью)

- Не копировать резолв проекта/модуля/BM в каждый инструмент — звать общий хелпер.
- Не класть утилиты и абстрактные базы в `tools/impl/` — туда только `IMcpTool`-классы (`move-nontool-helpers-out-of-impl`).
- Не наращивать god-классы: `RenameMetadataObjectTool`/`FindReferencesTool` делят `MetadataReferenceLocator` (`extract-metadata-reference-locator`); `McpServer` разносится (`decompose-mcpserver`).
- `tags/*` и `groups/*` — НЕ копировать третий такой стек; обе фичи должны наследовать общую базу association-storage/service/refactoring (`extract-tags-groups-shared-base`).

## Сопутствующее

- Корректность двух языков (ru/en) — скилл `edt-mcp-bilingual`.
- Кросс-инструментальный контракт (нейминг параметров, ошибки, вывод) — скилл `edt-mcp-tool-conventions`.
- Добавление нового инструмента — скилл `edt-mcp-new-tool`.
- Сборка и тесты — скилл `edt-mcp-build-test`.
- Полный список задач рефакторинга — доска `.devtool/features/*.md`.
