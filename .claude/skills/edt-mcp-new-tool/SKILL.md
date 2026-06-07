---
name: edt-mcp-new-tool
description: Canonical, battle-tested checklist for adding a new MCP tool to the EDT-MCP plugin the right way — the IMcpTool surface, shared resolvers, schema, registration, MANIFEST, the two MANDATORY test ratchets (unit + e2e), the golden snapshot, README, and the full compile→review→redeploy→live contour. Use when asked to add / create / scaffold a new MCP tool / EDT tool.
---

# EDT-MCP — добавление нового MCP-инструмента (канон)

Процедура, которая реально компилируется, проходит все ratchet'ы и валидируется вживую. Опирается на фактический текущий API — **сверяй каждый класс/метод перед использованием** (`grep`/Read, не выдумывай). Сопутствующие скиллы: `edt-mcp-architecture` (где что), `edt-mcp-tool-conventions` (нейминг/ошибки/вывод), `edt-mcp-bilingual` (ru/en), `edt-mcp-e2e-testing` (e2e-сьют), `edt-mcp-build-test` (сборка).

> Новый тул трогает МНОГО мест. Пропустишь одно — либо ratchet краснеет (unit-coverage, e2e-coverage, tool-contract, golden), либо живой `tools/list` дрейфует. Пройди весь список.

## Шаги

1. **Класс** в `tools/impl/XxxTool.java`: `implements IMcpTool` (read/action) или **`extends AbstractMetadataWriteTool`** для тула, который МУТИРУЕТ модель — база гоняет `executeOnUiThread(params)` на UI-потоке (мутация модели безопасна только там), даёт `resolveProjectAndConfig(projectName)` + `unwrapCauseMessage(e)` + дефолт `getResponseType()=JSON`. В `tools/impl/` — только сам тул (утилиты/базы → `utils/`, `tools/base/`). Заведи `public static final String NAME = "xxx";` (snake_case: `get_`/`list_`/`create_`/`adopt_`…).

2. **Поверхность IMcpTool**:
   - `getName()` → `NAME`.
   - `getDescription()` — кратко, для AI-клиента (что делает + когда). **Заканчивай** на `"… Full parameters and examples: call get_tool_guide('<name>')."` (unit-тест это проверяет; описание держи компактным — бюджет `tools/list` общий).
   - `getInputSchema()` через `JsonSchemaBuilder.object().stringProperty(name, desc[, required]).integerProperty(...).enumProperty(name, desc, "a","b").objectArrayProperty(...).build()`. **Required — это булев 3-й аргумент свойства; метода `.required(...)` НЕТ.** Имена параметров — **lowerCamelCase** (`ToolContractConsistencyTest` валит snake_case). Канон: `projectName`, `fqn`, `modulePath`, `limit`/`offset` (см. `edt-mcp-tool-conventions`).
   - `getOutputSchema()` — объяви ключи JSON-результата (для JSON-тулов).
   - `getGuide()` — полное how-to (параметры, примеры, г’отчи, как откатить). Отдаётся on-demand через guide-resource канал — ДЕТАЛИ сюда, не в description.
   - `getResponseType()` — TEXT/JSON/MARKDOWN/YAML/IMAGE (можно опустить при наследовании от AbstractMetadataWriteTool → JSON).
   - **Каждый параметр, читаемый в execute(), обязан быть в схеме, и наоборот.**

3. **execute / executeOnUiThread**:
   - Аргументы — `JsonUtils.extractStringArgument/extractIntArgument/extractBooleanArgument/extractObjectArray`; обязательные — `JsonUtils.requireArguments(params, "projectName", "fqn")` (вернёт готовый error-JSON или null).
   - Проект/конфигурация — `ProjectContext.of(name)` (utils) или `resolveProjectAndConfig(name)` (write-база). НЕ `ResourcesPlugin.getWorkspace()…` вручную.
   - **Модель — только внутри границы транзакции** (hard-rule CLAUDE.md): read — в read-границе, write — через `BmTransactions.write(...)`. Голый write только ставит в очередь async-экспорт `.mdo` — персисти через `BmTransactions.forceExportToDisk(project, [topObjectFqn, configurationFqn])` (передавай **TOP**-FQN; для члена — родительский top; добавь FQN `Configuration` = `((IBmObject)config).bmGetFqn()`, т.к. изменилась его коллекция).
   - Резолв метаданных по FQN — ОБЩИМИ резолверами (не плоди 47-ю копию): `MetadataTypeUtils.normalizeFqn/findObject` (двуязычно), `MetadataNodeResolver.resolveExisting` (существующий) / `resolveForCreate` (новый), `FormStructureReader.resolveMdForm` (формы — ждёт `Type.Name.Forms.FormName` или `CommonForm.Name`). Синоним — по КОДУ языка (см. `edt-mcp-bilingual`).
   - EDT-сервисы: типизированно через `Activator.getDefault().getXxx()` (`getConfigurationProvider`, `getV8ProjectManager`, `getDtProjectManager`, `getBmModelManager`, `getMdRefactoringService`) или `ServiceAccess.get(IFoo.class)` для wired-сервиса (binding `.toService()`) — и **добавь пакет в MANIFEST Import-Package** (шаг 5).
   - **Ошибки — ТОЛЬКО `ToolResult.error(msg).toJson()`** (никаких выброшенных исключений наружу, никаких голых `"Error: …"`). Успех — `ToolResult.success().put(k, v)….toJson()`. Ошибка должна быть actionable (назвать плохое значение + как исправить / sibling-тул).

4. **Регистрация** — в `tools/BuiltInToolRegistrar`: `import …impl.XxxTool;` + `registry.register(new XxxTool());` (рядом с сиблингами).

5. **MANIFEST.MF Import-Package** — добавь каждый НОВЫЙ импортируемый пакет `com._1c.g5.*` (напр. `com._1c.g5.v8.dt.md.extension.adopt`). Пропуск = `ClassNotFound` в рантайме, не на компиляции.

6. **Unit-тест — ОБЯЗАТЕЛЕН** (`mcp/tests/.../tools/impl/XxxToolTest.java`). `BuiltInToolTestCoverageTest` валит сборку, если у зарегистрированного тула нет `XxxToolTest`. Контракт (без живого рантайма): NAME, `getResponseType()`, description содержит `get_tool_guide('<name>')`, схема содержит все параметры, required-массив верный (и НЕ содержит optional), ключи output-схемы. Более глубокое поведение — e2e.

7. **e2e-тест — ОБЯЗАТЕЛЕН** (`tests/e2e/tools/test_<tool>.py`). e2e coverage-ratchet валит, если у тула из `tools/list` его нет. Читай `edt-mcp-e2e-testing`. happy + негатив + error-quality; anti-cheat («упал бы тест, будь тул сломан?»). Мутирующий тул: headless предпочитай не-мутирующие кейсы (harness ресетит только БАЗОВУЮ фикстуру per-test, не расширение) + мутирующий happy валидируй ЖИВЬЁМ; для write-metadata, что гоняется headless, проверяй read-back модели И структуру на диске (`poll_diff_contains`).

8. **Golden** — новый тул меняет `tools/list`, регенерируй и коммить: `EDT_MCP_UPDATE_GOLDEN=1 python tests/e2e/run_all.py --project TestConfiguration --filter test_tools_list_matches_committed_golden_snapshot`, затем `git add tests/e2e/tools_list.golden.json`.

9. **README** — подними СЧЁТЧИК тулов (два места), добавь тул в таблицу группы, в плоскую таблицу тулов и в детальную секцию (параметры обязаны совпасть со схемой).

10. **Полный контур** (без него не говори «готово»): `bash source/compile.sh` (компиляция + unit-тесты + все ratchet'ы) → adversarial **Opus-ревью** → redeploy на dev-копию EDT (`edt-redeploy.ps1`; сигнал — лог `MCP server UP on 8765`) → **живая валидация** против `:8765` → коммит. Свежеразвёрнутый тул НЕ попадает в deferred-список MCP этой сессии — для живой проверки зови его через e2e-клиент harness (`python` → `harness.initialize(); harness.call("<name>", {...})`) или `Invoke-RestMethod`, а не через MCP-обёртку тула. См. `edt-mcp-build-test` и память про dev-loop.

## Г’отчи (выстраданные)
- **`((IBmObject)x).bmGetFqn()` валиден ТОЛЬКО на TOP-объекте** — на члене (реквизит/форма/…) бросает «may be called on top objects only». Для top бери `bmGetTopObject().bmGetFqn()`; для члена репорти входной FQN.
- Запись модели — на UI-потоке (write-база делает это; голый `IMcpTool` должен сам обернуть в `Display.syncExec`).
- `forceExportToDisk` хочет TOP-FQN; изменение члена — экспорт родительского top + (для нового top) FQN `Configuration`.
- Кириллица в строковых литералах/регексах — через `\uXXXX` (Tycho non-UTF-8 safety); surface-текст — только английский.

## Чеклист готовности
- [ ] Класс в `tools/impl/`, NAME snake_case, параметры lowerCamelCase, каждый параметр в схеме
- [ ] description → `get_tool_guide('<name>')`; есть getGuide/getOutputSchema
- [ ] Общие резолверы + `ToolResult.error`; модель только в tx-границе; персист (forceExport) если write
- [ ] Зарегистрирован в `BuiltInToolRegistrar`; новые пакеты в MANIFEST Import-Package
- [ ] `XxxToolTest` (unit-ratchet) + `test_<tool>.py` (e2e-ratchet)
- [ ] Golden регенерирован; README счётчик + группа + таблица + деталь
- [ ] `compile.sh` зелёный → Opus-ревью → redeploy → живая валидация → коммит
