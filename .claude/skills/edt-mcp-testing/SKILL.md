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
