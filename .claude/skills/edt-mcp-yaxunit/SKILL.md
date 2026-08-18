---
name: edt-mcp-yaxunit
description: How to write and run YAXUnit unit tests for a 1C configuration through 1C:EDT + the EDT-MCP run_yaxunit_tests / debug_yaxunit_tests tools. Covers the three-piece setup (EDT plugin = IDE runner, YAxUnit.cfe engine in the infobase, a test extension that holds the tests), the test-module structure (ИсполняемыеСценарии + ЮТест asserts), and the run gotchas for this repo. Use when writing/running YAXUnit tests or setting YAXUnit up.
---

# EDT-MCP — YAXUnit (1C unit tests)

[YAXUnit](https://github.com/bia-technologies/yaxunit) is a unit-testing framework for 1C:Enterprise (Apache-2.0, bia-technologies). Tests are written in BSL, run by the platform via the `RunUnitTests` launch parameter, and produce a JUnit report. In this repo they run headless through the MCP tools `run_yaxunit_tests` / `debug_yaxunit_tests`.

## Three pieces — without all three the tests do NOT run

| Piece | What it is | Where it lives |
|---|---|---|
| **EDT plugin** ([edt-test-runner](https://bia-technologies.github.io/edt-test-runner/)) | the IDE runner: "Run As… YAXUnit", the test tree, the green/red bar. **It does not execute tests itself.** | the EDT install (Help → Install New Software) |
| **Engine `YAxUnit.cfe`** | the test executor + the assertion library (`ЮТест`/`ЮТТесты`) + the `RunUnitTests` handler | **loaded as an extension into the infobase** |
| **Test extension** | your modules with the tests | an EDT configuration-extension project in the workspace, deployed into the same infobase |

> Common mistake: install only the EDT plugin and expect `run_yaxunit_tests` to work. Without `YAxUnit.cfe` in the infobase the `ЮТест` engine doesn't resolve at runtime — the run gives an empty/error result.

## Setup (one-time)

1. **EDT plugin** — install into EDT.
2. **Engine into the infobase** — load `YAxUnit.cfe` ([releases](https://github.com/bia-technologies/yaxunit/releases)) as an extension: Designer/EDT → Extensions → Add → pick the `.cfe`; in the extension properties **clear "Safe mode" and "Protection from dangerous actions"** (otherwise the `ЮТест` export methods are blocked). The configuration does NOT need to be taken off support. The MCP server has NO "attach extension" tool — a human does this (or `DESIGNER /LoadCfg -Extension`).
3. **Test extension** — create an EDT configuration-extension project (File → New → Configuration Extension Project) extending the target configuration.
   - **Extension name = `tests`** — then `run_yaxunit_tests` with no filter finds it via the default `filter.extensions=["tests"]`. Any other name means always passing `extensions=["name"]`.
   - Prefix (`namePrefix`, e.g. `tests_`) — every native object of the extension must start with it.

## Test-module structure

A test module is a **native CommonModule in the test extension**:
- Flags: `Сервер=Истина` (for server tests); **NOT** `Глобальный`, **NOT** `ВызовСервера`, no re-use. At least one context flag (Сервер or КлиентУправляемоеПриложение).
- The name starts with the extension prefix (e.g. `tests_SampleTests`).
- Registered in the extension's `Configuration.mdo` with `<commonModules>CommonModule.tests_SampleTests</commonModules>`.

Minimal module:
```bsl
#Region Public

// YAXUnit finds the test module by the exported ИсполняемыеСценарии() and calls it
// to collect the test set. Each .ДобавитьТест("Method") = an exported procedure below.
Процедура ИсполняемыеСценарии() Экспорт
    ЮТТесты.ДобавитьТестовыйНабор("Arithmetic")
        .ДобавитьТест("TwoPlusTwoIsFour");
    // Parameterization: one method, several input sets.
    ЮТТесты.ДобавитьТестовыйНабор("Parameterized")
        .ДобавитьТест("SumByParameters")
            .СПараметрами(2, 3, 5)
            .СПараметрами(0, 0, 0);
КонецПроцедуры

// Hook — runs before each test of the module. Per-test scratch state lives in
// ЮТест.КонтекстТеста() (also КонтекстТестовогоНабора / КонтекстМодуля).
Процедура ПередКаждымТестом() Экспорт
    ЮТест.КонтекстТеста().Вставить("Started", Истина);
КонецПроцедуры

Процедура TwoPlusTwoIsFour() Экспорт
    ЮТест.ОжидаетЧто(2 + 2).Равно(4);
КонецПроцедуры

Процедура SumByParameters(First, Second, Expected) Экспорт
    ЮТест.ОжидаетЧто(First + Second).Равно(Expected);
КонецПроцедуры

#EndRegion
```

Other hooks (by name, all exported, optional): `ПередВсемиТестамиМодуля` / `ПослеВсехТестовМодуля`, `ПередТестовымНабором` / `ПослеТестовогоНабора`, `ПередКаждымТестом` / `ПослеКаждогоТеста`. Registration: `.ДобавитьСерверныйТест` / `.ДобавитьКлиентскийТест` for a specific context; `.Тег("...")`, `.ВТранзакции()` on a suite/test.

## Asserts — `ЮТест.ОжидаетЧто(value [, message])`

Chain (each method takes an optional final parameter — the check description):
- equality/comparison: `.Равно` / `.НеРавно` / `.Больше` / `.БольшеИлиРавно` / `.Меньше` / `.МеньшеИлиРавно`;
- boolean/existence: `.ЭтоИстина` / `.ЭтоЛожь` / `.Заполнено` / `.НеЗаполнено` / `.ЭтоНеопределено` / `.Существует`;
- type: `.ИмеетТип("Число")` / `.ИмеетТип(Тип("Строка"))`;
- strings: `.Содержит` / `.НеСодержит` / `.НачинаетсяС` / `.ЗаканчиваетсяНа` / `.ИмеетДлину(N)` / `.СодержитСтрокуПоШаблону("regex")`;
- collections: `.ИмеетДлину(N)` / `.Содержит(Элемент)` / `.КаждыйЭлементСодержитСвойство("X")`;
- navigation: `.Свойство("Реквизит")` / `.Свойство("Товары[0].Номенклатура")` / `.Объект()`;
- exceptions: `ЮТест.ОжидаетЧто(Модуль).Метод("Имя").Параметр(X).ВыбрасываетИсключение("fragment")` (or `.НеВыбрасываетИсключение()`).

Unconditional: `ЮТест.Упал("why")`, `ЮТест.Пропустить("reason")`. The API is **Russian-only** (no English aliases).

## Running through MCP

- **`run_yaxunit_tests`** — starts a run, polls up to `timeout` seconds, returns a JUnit-Markdown report (plus `report.md`/`junit.xml` on disk). Filters (arrays; families AND-combined, values within a family OR-ed): `extensions`, `modules`, `tests` (format `Module.Method`), `tags`. With no filter — default `filter.extensions=["tests"]`. `tags` selects by the YAXUnit tags declared with `ЮТТесты.Тег(...)` — module-, suite- or test-level, case-insensitive, no exclusion syntax.
- **`debug_yaxunit_tests`** — deprecated alias for `run_yaxunit_tests(debug=true)`. It uses the same named-job path: a short start returns the launch handle; otherwise Pending returns `jobId` for `get_job_status`. When the handle arrives, call `wait_for_break` → `get_variables` / `evaluate_expression` / `step` / `resume`. Pin to one test (`tests=["Module.Method"]`) for predictability.

### Repo gotchas

- **A "TestConfiguration Thin Client" config without the `applicationId` attribute** — both `run_yaxunit_tests` and `update_database` now derive the target from the project instead of failing (`Pre-launch preparation failed: Application not found`): the launch tools fall back to the project's default application, `update_database` only to its SINGLE application, refusing when the project has several. **If it is refused:** run `update_database(projectName="TestConfiguration", applicationId=<from get_applications>, confirm=true)` explicitly, then `run_yaxunit_tests(launchConfigurationName="TestConfiguration Thin Client", updateBeforeLaunch=false)`.
- **`update_database` is confirm-preview** → the first call returns a preview, then `confirm=true` is required (this is a destructive infobase operation, IRREVERSIBLE — only on explicit request).
- **The extension must be deployed into the infobase** (`update_database`) before a run; after changing a test module on disk → `clean_project(projectName="TestConfiguration.tests")` (refresh from disk) → `update_database` (deploy) → `run_yaxunit_tests`.
- **The EDT extension project is named `<base>.<extName>`** (e.g. `TestConfiguration.tests`) — use this name in `clean_project`/`get_project_errors`/`get_module_structure`. But `filter.extensions` takes the *extension configuration* name (`tests`).
- **Expected EDT warnings** (NOT errors, the run is unaffected): `Variable 'ЮТест'/'ЮТТесты' is not defined` — the engine isn't in the EDT workspace, it resolves at runtime from the loaded `.cfe`; `common-module-type` — a BSP-style opinion about a server module's context flags, inapplicable to test modules.
- The MCP client call timeout may be shorter than the run — set a small `timeout` (≈30). On `Pending`, keep the returned `jobId` and poll `get_job_status`; do not reconstruct the start arguments to address a known run. A repeated start is only a live duplicate guard, not the polling interface.
- To stop a live run, preview with `cancel_job(jobId=...)` first. Confirmed YAXUnit cancellation kills the client but does **not** roll back the infobase; the report is partial or absent. A committed non-YAXUnit job without an owner-declared cancellation capability remains `alreadyCommitted`.

## Example in the repo

`tests/tests` — the `tests` extension (prefix `tests_`), module [tests_SampleTests](../../../tests/tests/src/CommonModules/tests_SampleTests/Module.bsl): **8 cases** (equality; a cross-config call `Calc.Add` from the extension; a call to a sibling extension module `tests_MathHelper`; subtraction; a string-assert chain; parameterized ×3). **Green run:** `run_yaxunit_tests(launchConfigurationName="TestConfiguration Thin Client", modules="tests_SampleTests")` → **8/8 PASSED**.

A separate module [tests_FailureDemo](../../../tests/tests/src/CommonModules/tests_FailureDemo/Module.bsl) (1 passing + 1 **intentionally failing**) verifies that failures are reflected correctly in the report counters. So a run **with no filter** (the whole `extensions=["tests"]`) includes that 1 expected fail (Total 10 → 9 passed, 1 failed) — that is **by design**, not a bug; for a green run filter `modules="tests_SampleTests"`. The full live round-trip e2e validation (pass/fail counters + debug suspend/resume + launch id + profiling) is `tests/e2e/tools/test_live_roundtrip.py`, gated behind `EDT_MCP_LIVE_INFOBASE=1`.

## Sources
- [YAxUnit (engine + docs)](https://bia-technologies.github.io/yaxunit/) · [repository](https://github.com/bia-technologies/yaxunit)
- [edt-test-runner (EDT plugin)](https://bia-technologies.github.io/edt-test-runner/)
