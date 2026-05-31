---
name: edt-mcp-build-test
description: How to build the EDT-MCP Eclipse plugin (Tycho/Maven) and run its unit and e2e tests, plus the test conventions for this repo. Use when building the plugin, running or writing tests, or verifying a change before committing.
---

# EDT-MCP — сборка и тесты

## Структура

- Maven/Tycho-реактор: `mcp/` (bom, bundles, features, repositories, targets, tests).
- Юнит-тесты: `mcp/tests/com.ditrix.edt.mcp.server.tests/src` (JUnit4, plug-in fragment).
- E2E: `tests/e2e/run_e2e_tests.py` (Python; гоняет MCP-сервер против `TestConfiguration/`).

## Сборка

Tycho-сборка из `mcp/` (Maven). Артефакт — p2 update-site в `repositories/com.ditrix.edt.mcp.server.repository/target`.

> Команды сборки/окружение могут отличаться на машине — уточнить в README.md (секция установки/сборки) и в `mcp/` (pom/target-platform). Не выдумывать версии и пути; брать из репозитория.

## Юнит-тесты — конвенции

- Один `XxxToolTest` на инструмент (`tools/impl/`), JUnit4.
- Базовый паттерн: `tool.execute(params)` + проверка sentinel-сообщения (напр. «Project not found») для валидации аргументов. Образец — `WriteModuleSourceToolTest`.
- **Двуязычный инвариант**: для инструментов, резолвящих метаданные/код, — кейс с русским идентификатором/синонимом (образец `WriteModuleSourceToolTest.testResolveRussianObjectName`). См. скилл `edt-mcp-bilingual`.
- Форматтеры метаданных и debug/profiling-семейство сейчас не покрыты — при правках добавлять тесты (карточки `tests-metadata-bilingual-and-formatters`, `tests-debug-profiling-family`).

## Цель по покрытию (вводится)

Гейт «у каждого зарегистрированного инструмента есть `XxxToolTest`» + реестро-driven проверка e2e-покрытия (карточка `tests-coverage-ci-gate`). При добавлении инструмента без теста сборка должна падать.

## E2E

`tests/e2e/run_e2e_tests.py` — запускает сценарии против живого сервера и `TestConfiguration/`. Включает round-trip кириллических синонимов и проверку ключа синонима по коду языка (`_assert_synonym_language_code`). При добавлении инструмента из непокрытого семейства (debug/profiling, validate_query, навигация) — добавить сценарий (карточка `e2e-single-script-no-coverage-backstop`).

## Перед коммитом
- [ ] Сборка проходит
- [ ] Юнит-тесты зелёные; для нового/изменённого инструмента есть тест
- [ ] Если затронут резолв метаданных/кода — есть двуязычный кейс
- [ ] (если применимо) e2e-сценарий обновлён
