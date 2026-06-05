---
name: edt-mcp-build-test
description: How to build the EDT-MCP Eclipse plugin (Tycho/Maven) and run its unit and e2e tests, plus the test conventions for this repo. Use when building the plugin, running or writing tests, or verifying a change before committing.
---

# EDT-MCP — сборка и тесты

## Структура

- Maven/Tycho-реактор: `mcp/` (bom, bundles, features, repositories, targets, tests).
- Юнит-тесты: `mcp/tests/com.ditrix.edt.mcp.server.tests/src` (JUnit4, plug-in fragment).
- E2E: `tests/e2e/run_all.py` + `tools/test_<tool>.py` (Python; гоняет MCP-сервер против `TestConfiguration/`).

## Сборка

Tycho-сборка из `mcp/` (Maven, JDK 17). Артефакт — p2 update-site в `repositories/com.ditrix.edt.mcp.server.repository/target`.

**Локальная сборка доступна — пользуйся ей для валидации правок Java** (не «проверено только ревью/грепом»). Канонический скрипт — `source/compile.sh` (он же воспроизводит CI-флоу `mvn clean verify -T 1C` из `.github/workflows/build.yml`):

```bash
# из корня репо: компиляция + юнит-тесты
bash source/compile.sh
# только компиляция (без Surefire) — быстрее
bash source/compile.sh --skip-tests
```

- Тулчейн (JDK 17 + Maven 3.9+) часто **не на `PATH`** — передавай явно: `--java-home <JDK17 home> --maven-home <maven home>` (или env `JAVA_HOME`/`MAVEN_HOME`). Конкретные пути **зависят от машины — выясняй на месте**, не хардкодь в репозиторные файлы. Точные опции — в README «Building from source».
- **Первая сборка медленная**: Tycho тянет EDT p2-репозиторий (`edt.1c.ru`) + Eclipse SDK (сотни МБ). После прогрева кэшей (`~/.m2/repository/p2`, `.cache/tycho`) — ~1 минута. Если кэшей нет и нет сети — сборка честно не пойдёт; так и сказать, не имитировать «зелёно».
- **Юнит-тестам тоже нужна target-платформа** (Mockito/JUnit идут из p2-таргета, не из обычного Maven Central) — зелёный `compile.sh` и есть настоящее доказательство для Java-правок; греп ловит только проблемы якорей/текста.

## Юнит-тесты — конвенции

- Один `XxxToolTest` на инструмент (`tools/impl/`), JUnit4.
- Базовый паттерн: `tool.execute(params)` + проверка sentinel-сообщения (напр. «Project not found») для валидации аргументов. Образец — `WriteModuleSourceToolTest`.
- **Двуязычный инвариант**: для инструментов, резолвящих метаданные/код, — кейс с русским идентификатором/синонимом (образец `WriteModuleSourceToolTest.testResolveRussianObjectName`). См. скилл `edt-mcp-bilingual`.
- Форматтеры метаданных и debug/profiling-семейство сейчас не покрыты — при правках добавлять тесты (карточки `tests-metadata-bilingual-and-formatters`, `tests-debug-profiling-family`).

## Цель по покрытию (вводится)

Гейт «у каждого зарегистрированного инструмента есть `XxxToolTest`» + реестро-driven проверка e2e-покрытия (карточка `tests-coverage-ci-gate`). При добавлении инструмента без теста сборка должна падать.

## E2E

`tests/e2e/run_all.py` (+ `tools/test_<tool>.py`, по одному на инструмент) — запускает сценарии против живого сервера и `TestConfiguration/` с git-фикстурной изоляцией. Покрытие enforced рэтчетом (`tools/test_coverage_ratchet.py`): инструмент в `tools/list` без теста валит сьют. Round-trip кириллических синонимов и проверка ключа синонима по коду языка — в `test_create_metadata.py` / `test_get_metadata_details.py`. Новый инструмент — добавить `tools/test_<tool>.py`.

## Перед коммитом
- [ ] Сборка проходит
- [ ] Юнит-тесты зелёные; для нового/изменённого инструмента есть тест
- [ ] Если затронут резолв метаданных/кода — есть двуязычный кейс
- [ ] (если применимо) e2e-сценарий обновлён
