---
name: edt-mcp-tool-conventions
description: Cross-tool consistency contract for EDT-MCP tools — parameter naming, error shape, output format, pagination, not-found semantics, schema/README alignment. Use when editing any class under tools/impl, adding a parameter, changing a tool's output, or reviewing a tool for consistency.
---

# EDT-MCP — контракт инструментов (консистентность)

Один и тот же концепт должен вести себя одинаково во всех инструментах. Ревью выявило системные расхождения — этот скилл фиксирует канон, чтобы их не плодить.

## Нейминг параметров

| Концепт | Канон | Анти-пример |
|---|---|---|
| Проект | `projectName` | `project` — только `DebugStatusTool`; привести к `projectName` (см. `standardize-project-param-name`) |
| Модуль | `modulePath` (от `src/`, резолвер принимает и абсолютный путь) | `module` (debug) и разложенный `objectName+moduleType+formName+commandName` (write) — три формы, см. `standardize-project-param-name` |
| Полное имя объекта | `fqn` | — |
| Имя объекта | `objectName` | — |
| Синоним / язык | `synonym` / `language` | — |
| Лимит / смещение | `limit` / `offset` | `maxResults`, `maxDepth` вразнобой |

Старые имена при миграции — принимать как документированные алиасы один релиз, затем депрекейт.

## Параметры читать типизированно

Через `ToolParams` (вводится в `introduce-tool-params-accessor`), не парся сырую `Map<String,String>` руками. Единые сообщения о missing/invalid параметре.

**Каждый читаемый параметр обязан быть объявлен в `getInputSchema`** (иначе он невидим schema-driven клиентам) и наоборот. См. `tool-contract-consistency-tests`.

## Контракт ошибок

- Только `ToolResult.error(...)` (машиночитаемо: success=false + message/code). НЕ возвращать голую строку «Error: …»/«Project not found» и не кидать исключение наружу. См. `unify-error-contract`.
- «project not found» — единый текст/форма через общий резолвер `ProjectContext`.

## Формат вывода

- Аналогичные инструменты — одинаковый формат (не один JSON, другой markdown без причины). По умолчанию для плагина — markdown (см. `IMcpTool.ResponseType`).
- Поля JSON — единый стиль (не мешать camelCase/snake_case в аналогичных ответах).
- not-found — единая семантика: пустой список для «список», ошибка для «получить конкретный». Не вперемешку null/пусто/ошибка.

### Response format policy (Markdown vs JSON)

`structuredContent`/JSON is for the client to consume (UI rendering, verbatim round-trip of identifiers, a future `outputSchema`), NOT for the agent to read. **MARKDOWN is the default** (token-efficient, readable).

A tool returns JSON only when its result carries:

- **(a)** round-trip IDs another tool consumes;
- **(b)** machine-structured positions (e.g. error line/column);
- **(c)** a declared `outputSchema`;
- **(d)** UI-rendered data.

Action/confirmation/status results with none of these return MARKDOWN. `write_module_source` is the reference MARKDOWN action tool; `AbstractMetadataWriteTool` subclasses stay JSON because they return the created object's round-trip FQN.

Tool families that stay JSON, and why:

- metadata-writes (`create_metadata`, `modify_metadata`, `delete_metadata`) → addressed **FQN** *(a)*;
- debug / profiling tools → launch / application / breakpoint **IDs** + live session state *(a)*;
- `validate_query` → error **line/col** *(b)*;
- `list_configurations` → config **identities** *(a)*;
- `clean_project`, `update_database` → destructive status whose JSON shape is consumed by e2e *(d-like)*.

Markdown action tools (`revalidate_objects`, `export_configuration_to_xml`, `import_configuration_from_xml`, `write_module_source`) emit status + paths/counts only — no round-trip data. Build the body with `FrontMatter` + a markdown summary; any table goes through `MarkdownUtils` (escapes every cell); errors still via `ToolResult.error(...)`.

## Пагинация

Общий `Pagination` (единый дефолтный лимит, единый формат уведомления об обрезке). См. `standardize-pagination`. Не изобретать свою схему на инструмент.

## Сборка результата / markdown

Через `ToolResult`/`JsonUtils` и общий markdown-table билдер, не hand-rolled StringBuilder. См. `shared-markdown-and-result-builders`.

## Схема ↔ код ↔ README

- Реестр (`BuiltInToolRegistrar` / `McpServer.registerTools`) — источник истины по набору; README-каталог должен ему соответствовать. См. `readme-tool-catalogue-drift`.
- Каждый инструмент: непустое осмысленное описание и описания всех параметров.

## Расположение классов

`tools/impl/` — только `IMcpTool`-классы. Утилиты/абстрактные базы — в `utils/`/`tools/base/` (см. `move-nontool-helpers-out-of-impl`). Резолв проекта/модели — через общие хелперы (скилл `edt-mcp-architecture`). Корректность ru/en — скилл `edt-mcp-bilingual`.
