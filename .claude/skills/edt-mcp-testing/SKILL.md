---
name: edt-mcp-testing
description: How to manually e2e-test each EDT-MCP server tool against a live EDT workbench + TestConfiguration. One reference per tool under references/<family>/, each with the exact MCP call, the real expected result, and gotchas. Use when validating an MCP tool end-to-end by actually calling it (for build/unit tests see edt-mcp-build-test).
---

# EDT-MCP — ручное e2e-тестирование инструментов

Как **вживую прогнать каждый MCP-инструмент против работающего EDT** и убедиться, что он реально работает (а не только компилируется / проходит юнит-тест). Сборка и юнит-тесты — в скилле `edt-mcp-build-test`; здесь — живой e2e через настоящие MCP-вызовы.

## Структура
- `references/<семейство>/<tool>.md` — **один референс на инструмент**: назначение, точный вызов, ожидаемый результат (по факту прогона), подводные камни.
- `references/<семейство>/SETUP.md` — общая предусловная подготовка семейства (то, без чего инструменты семейства не протестировать).
- Начато с **debug** → `references/debug/`. Остальные семейства (metadata-write, navigation, query, profiling, …) добавляются тем же шаблоном.

## Тестовый стенд (harness)
- **EDT-копия (не elevated)** + воркспейс `D:\WS\EDT`; MCP слушает `:8765`. Деплой правок — `pwsh D:\Soft\edt-redeploy.ps1` (build → kill EDT → swap bundle → `-clean` relaunch → ждёт `:8765`). Скрипт иногда отдаёт **exit 1**, но печатает `MCP server UP on 8765` — это и есть успех; **проверяй порт / `get_edt_version`, не верь exit-коду**.
- **База для тестов** — проект `TestConfiguration` (в репо `TestConfiguration/src`, в воркспейсе открыт). Деструктив/мутации гоняем только на ней; после — откат: `git checkout HEAD -- TestConfiguration && git clean -fd -- TestConfiguration`.
- **Воркфлоу-инвариант**: правка → `bash source/compile.sh` → redeploy → live MCP-вызов → `git diff` → revert. После `-clean` redeploy EDT теряет несохранённые in-memory изменения — мутируй через MCP (`write_module_source` и т.п.), чтобы модель и диск были в синхроне.
- **Flaky tool output**: канал иногда роняет текст (видишь голое `Error` / `Done` вместо JSON). **Перепроверяй состояние независимо**: `debug_status`, `git status`, `Get-Process`, и особенно EDT-лог `D:\WS\EDT\.metadata\.log` (там логируется полный request/response). Не доверяй echo.

## Как добавить референс на инструмент
1. Прогнать инструмент **вживую** реальными аргументами на `TestConfiguration`.
2. Записать **точный вызов + фактический вывод + подводные камни** в `references/<family>/<tool>.md`.
3. Слинковать общий `SETUP.md` семейства.
4. Если для прогона нужна мутация базы — описать и откат.

## Индекс — debug (`references/debug/`)
- [SETUP](references/debug/SETUP.md) — поднять отлаживаемый сценарий (исполняемый код + триггер + останов).
- Конфиги/запуск: `list_configurations`, `debug_launch`, `debug_status`, `terminate_launch`.
- Точки останова: `set_breakpoint`, `list_breakpoints`, `remove_breakpoint`.
- Останов и инспекция: `wait_for_break`, `get_variables`, `evaluate_expression`, `step`, `resume`.

> Полный валидированный проход (2026-06-02): set_breakpoint → debug_launch(update) → wait_for_break(hit) → get_variables → step over → get_variables → evaluate_expression → resume → remove_breakpoint. Детали по каждому — в его референсе.

## Индекс — navigation / read (`references/navigation/`) — English

Read-only инструменты навигации и чтения. Доки на английском (полный гайд для другого ИИ), каждый с реальным live-вызовом на `TestConfiguration`.
- [SETUP](references/navigation/SETUP.md) — project ready (не `building`), индекс BSL построен, modulePath-адресация, UI-thread tools.
- Workspace/listing: [list_projects](references/navigation/list_projects.md), [list_modules](references/navigation/list_modules.md).
- Source: [read_module_source](references/navigation/read_module_source.md), [read_method_source](references/navigation/read_method_source.md), [get_module_structure](references/navigation/get_module_structure.md).
- AST/refs: [go_to_definition](references/navigation/go_to_definition.md), [find_references](references/navigation/find_references.md), [get_method_call_hierarchy](references/navigation/get_method_call_hierarchy.md), [get_symbol_info](references/navigation/get_symbol_info.md), [get_content_assist](references/navigation/get_content_assist.md).

## Индекс — metadata-read (`references/metadata-read/`) — English

Read-only чтение модели конфигурации (объекты, детали, свойства, подсистемы, теги). Английский, каждый с реальным live-вызовом на `TestConfiguration`.
- Объекты: [get_metadata_objects](references/metadata-read/get_metadata_objects.md), [get_metadata_details](references/metadata-read/get_metadata_details.md).
- Конфигурация: [get_configuration_properties](references/metadata-read/get_configuration_properties.md) (теперь YAML).
- Подсистемы: [list_subsystems](references/metadata-read/list_subsystems.md), [get_subsystem_content](references/metadata-read/get_subsystem_content.md).
- Теги: [get_tags](references/metadata-read/get_tags.md), [get_objects_by_tags](references/metadata-read/get_objects_by_tags.md).

## Индекс — query / problems / misc (read-only) — English

- **query** (`references/query/`): [validate_query](references/query/validate_query.md) (диалект-aware, `valid:false` ≠ failure).
- **problems** (`references/problems/`): [get_project_errors](references/problems/get_project_errors.md), [get_problem_summary](references/problems/get_problem_summary.md), [get_check_description](references/problems/get_check_description.md) (читает `<checkId>.md` из настраиваемой папки; фича OFF по умолчанию; checkId санитизируется — dotted-коды не резолвятся).
- **misc** (`references/misc/`): [get_edt_version](references/misc/get_edt_version.md) (TEXT, liveness-проба), [get_platform_documentation](references/misc/get_platform_documentation.md), [get_markers](references/misc/get_markers.md), [get_applications](references/misc/get_applications.md).

## Индекс — profiling / forms / xml / translation / launch / metadata-write — English

Семейства с write/destructive/спец-настройкой. Для мутирующих/деструктивных доки описывают **процедуру теста из source** (mutate-then-revert / preview-then-confirm), без живого деструктива.
- **profiling** (`references/profiling/`): [start_profiling](references/profiling/start_profiling.md) (это TOGGLE; applicationId = debug-session id, не infobase GUID), [get_profiling_results](references/profiling/get_profiling_results.md) (read-only).
- **forms** (`references/forms/`): [get_form_screenshot](references/forms/get_form_screenshot.md), [get_form_layout_snapshot](references/forms/get_form_layout_snapshot.md) — нужен JVM-флаг `-DnativeFormBufferedLayoutRender=true`; blank ≠ баг; native vs buffered render.
- **xml** (`references/xml/`): [export_configuration_to_xml](references/xml/export_configuration_to_xml.md), [import_configuration_from_xml](references/xml/import_configuration_from_xml.md) (MUTATES → revert).
- **translation** (`references/translation/`): [get_translation_project_info](references/translation/get_translation_project_info.md) (read-only), [generate_translation_strings](references/translation/generate_translation_strings.md), [translate_configuration](references/translation/translate_configuration.md) (MUTATES + внешние провайдеры).
- **launch** (`references/launch/`): [update_database](references/launch/update_database.md) (DESTRUCTIVE, эксклюзивный доступ к ИБ), [run_yaxunit_tests](references/launch/run_yaxunit_tests.md), [debug_yaxunit_tests](references/launch/debug_yaxunit_tests.md).
- **metadata-write** (`references/metadata-write/`): [create_metadata](references/metadata-write/create_metadata.md) (FQN-addressed; folds the former create_metadata_object + add_metadata_attribute), [modify_metadata](references/metadata-write/modify_metadata.md) (set properties=[{name,value,language?}] by FQN; folds the former set_metadata_property), [write_module_source](references/metadata-write/write_module_source.md) (mutate→verify→revert), [rename_metadata_object](references/metadata-write/rename_metadata_object.md), [delete_metadata](references/metadata-write/delete_metadata.md) (FQN-addressed; CASCADE; preview→confirm→revert; explicit-request-only).

> **Покрытие:** debug (RU) + navigation, metadata-read, query, problems, misc, profiling, forms, xml, translation, launch, metadata-write (EN) = все ~58 MCP-инструментов имеют пер-tool e2e-референс. Write/destructive — документированная процедура из source (mutate-then-revert / preview-then-confirm), не живой деструктив.
