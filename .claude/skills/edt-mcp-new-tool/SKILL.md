---
name: edt-mcp-new-tool
description: Canonical step-by-step procedure for adding a new MCP tool to the EDT-MCP plugin the right way — IMcpTool contract, shared resolvers, schema, registration, README and a unit test. Use when asked to add, create or scaffold a new MCP tool / EDT tool.
---

# EDT-MCP — добавление нового MCP-инструмента (канон)

Процедура для добавления инструмента **по целевой архитектуре** (общие хелперы, единый контракт). Сопутствующие скиллы: `edt-mcp-architecture` (где что лежит), `edt-mcp-tool-conventions` (нейминг/ошибки/вывод), `edt-mcp-bilingual` (ru/en).

## Шаги

1. **Класс инструмента** в `tools/impl/XxxTool.java`, `implements IMcpTool` (или `extends AbstractMetadataWriteTool` для write-метаданных). Только сам инструмент — никаких утилит в этом пакете.

2. **Имя и описание**: `getName()` — snake_case (`get_xxx`, `list_xxx`, `create_xxx`). `getDescription()` — содержательное, для AI-клиента (что делает, когда применять).

3. **Схема** `getInputSchema()` через `JsonSchemaBuilder`. Канон-нейминг параметров: `projectName`, `modulePath`, `fqn`, `objectName`, `limit`/`offset` (см. `edt-mcp-tool-conventions`). **Каждый параметр, который читаешь в execute(), обязан быть в схеме.**

4. **execute(params)**:
   - Параметры — типизированно через `ToolParams` (не ручной парсинг Map).
   - Проект/конфигурацию — через `ProjectContext.resolve(projectName)` (НЕ `ResourcesPlugin.getWorkspace()` вручную).
   - Доступ к модели — через `BmTransactions.readModel/writeModel` (read — в read-границе, write — в write).
   - Резолв объекта/типа метаданных — через `MetadataTypeUtils` (двуязычно); язык синонима — через `MetadataLanguageUtils` (ключ = код языка). См. `edt-mcp-bilingual`.
   - Результат — через `ToolResult`; ошибки — только `ToolResult.error(...)`.
   - Если вывод списочный — общий `Pagination`; таблицы — общий markdown-билдер.

   > Часть общих хелперов (`ProjectContext`, `ToolParams`, `BmTransactions`, `MetadataLanguageUtils`, `Pagination`) вводится рефакторингом — карточки в `.devtool/features/`. Если хелпера ещё нет, либо дождись/сделай его, либо заведи TODO со ссылкой на карточку, но НЕ копируй старый дублирующий boilerplate.

5. **Регистрация** — добавить в `BuiltInToolRegistrar` (после рефакторинга реестра; пока — в `McpServer.registerTools()`).

6. **README** — добавить инструмент в каталог README.md (состав и параметры обязаны соответствовать схеме).

7. **Тест** — `XxxToolTest.java` в `mcp/tests/.../tools/impl/`. Минимум: имя, тип ответа, схема содержит required-параметры, валидация отсутствующих параметров (паттерн «Project not found» из `WriteModuleSourceToolTest`). Если инструмент резолвит метаданные/код — добавить двуязычный кейс (англ. Name, рус. Name, синоним) по образцу `WriteModuleSourceToolTest.testResolveRussianObjectName`.

8. **Сборка/прогон** — см. скилл `edt-mcp-build-test`.

## Чеклист готовности
- [ ] `implements IMcpTool`, лежит только в `tools/impl/`
- [ ] Параметры по канон-неймингу; каждый объявлен в схеме
- [ ] Резолв через общие хелперы, без ручного `ResourcesPlugin`
- [ ] Ошибки через `ToolResult.error`
- [ ] ru/en учтены (если применимо), есть двуязычный тест
- [ ] Зарегистрирован, задокументирован в README, покрыт `XxxToolTest`
