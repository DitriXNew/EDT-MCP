---
name: edt-mcp-yaxunit
description: How to write and run YAXUnit unit tests for a 1C configuration through 1C:EDT + the EDT-MCP run_yaxunit_tests / debug_yaxunit_tests tools. Covers the three-piece setup (EDT plugin = IDE runner, YAxUnit.cfe engine in the infobase, a test extension that holds the tests), the test-module structure (ИсполняемыеСценарии + ЮТест asserts), and the run gotchas for this repo. Use when writing/running YAXUnit tests or setting YAXUnit up.
---

# EDT-MCP — YAXUnit (юнит-тесты 1С)

[YAXUnit](https://github.com/bia-technologies/yaxunit) — фреймворк модульного тестирования для 1С:Предприятие (Apache-2.0, bia-technologies). Тесты пишутся на BSL, гоняются платформой по параметру запуска `RunUnitTests`, на выходе — JUnit-отчёт. В этом репо они запускаются headless через MCP-тулы `run_yaxunit_tests` / `debug_yaxunit_tests`.

## Три части — без всех трёх тесты НЕ бегут

| Часть | Что это | Где живёт |
|---|---|---|
| **EDT-плагин** ([edt-test-runner](https://bia-technologies.github.io/edt-test-runner/)) | раннер в IDE: «Run As… YAXUnit», дерево тестов, зелёная/красная полоса. **Сам тесты не исполняет.** | установка EDT (Help → Install New Software) |
| **Движок `YAxUnit.cfe`** | исполнитель тестов + библиотека ассертов (`ЮТест`/`ЮТТесты`) + обработчик `RunUnitTests` | **загружается как расширение в инфобазу** |
| **Тестовое расширение** | твои модули с тестами | EDT-проект-расширение в воркспейсе, деплоится в ту же инфобазу |

> Частая ошибка: поставить только EDT-плагин и ждать, что `run_yaxunit_tests` заработает. Без `YAxUnit.cfe` в инфобазе движок `ЮТест` не резолвится в рантайме — прогон даёт пустой/ошибочный результат.

## Настройка (однократно)

1. **EDT-плагин** — поставить в EDT.
2. **Движок в инфобазу** — загрузить `YAxUnit.cfe` ([releases](https://github.com/bia-technologies/yaxunit/releases)) как расширение: Конфигуратор/EDT → Расширения → Добавить → выбрать `.cfe`; в свойствах расширения **снять «Безопасный режим» и «Защиту от опасных действий»** (иначе экспортные методы `ЮТест` блокируются). Снимать конфигурацию с поддержки НЕ нужно. У MCP-сервера тула «подключить расширение» НЕТ — это делает человек (или `DESIGNER /LoadCfg -Extension`).
3. **Тестовое расширение** — создать EDT-проект расширения конфигурации (File → New → Configuration Extension Project), расширяющий целевую конфигурацию.
   - **Имя расширения = `tests`** — тогда `run_yaxunit_tests` без фильтра находит его по дефолту `filter.extensions=["tests"]`. Любое другое имя — придётся всегда передавать `extensions=["имя"]`.
   - Префикс (`namePrefix`, напр. `tests_`) — все нативные объекты расширения должны начинаться с него.

## Структура тест-модуля

Тест-модуль — это **нативный CommonModule в тестовом расширении**:
- Флаги: `Сервер=Истина` (для серверных тестов); **НЕ** `Глобальный`, **НЕ** `ВызовСервера`, повторное использование — не использовать. Минимум один контекст-флаг (Сервер или КлиентУправляемоеПриложение).
- Имя начинается с префикса расширения (напр. `tests_SampleTests`).
- Зарегистрирован в `Configuration.mdo` расширения строкой `<commonModules>CommonModule.tests_SampleTests</commonModules>`.

Минимальный модуль:
```bsl
#Region Public

// YAXUnit находит тест-модуль по экспортной ИсполняемыеСценарии() и вызывает её,
// чтобы собрать набор тестов. Каждый .ДобавитьТест("Метод") = экспортная процедура ниже.
Процедура ИсполняемыеСценарии() Экспорт
    ЮТТесты.ДобавитьТестовыйНабор("Arithmetic")
        .ДобавитьТест("TwoPlusTwoIsFour");
    // Параметризация: один метод — несколько наборов входных данных.
    ЮТТесты.ДобавитьТестовыйНабор("Parameterized")
        .ДобавитьТест("SumByParameters")
            .СПараметрами(2, 3, 5)
            .СПараметрами(0, 0, 0);
КонецПроцедуры

// Хук — выполняется перед каждым тестом модуля. Скретч-состояние на один тест —
// в ЮТест.КонтекстТеста() (есть также КонтекстТестовогоНабора / КонтекстМодуля).
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

Прочие хуки (по имени, все экспортные, опциональны): `ПередВсемиТестамиМодуля` / `ПослеВсехТестовМодуля`, `ПередТестовымНабором` / `ПослеТестовогоНабора`, `ПередКаждымТестом` / `ПослеКаждогоТеста`. Регистрация: `.ДобавитьСерверныйТест` / `.ДобавитьКлиентскийТест` для конкретного контекста; `.Тег("...")`, `.ВТранзакции()` — на наборе/тесте.

## Ассерты — `ЮТест.ОжидаетЧто(значение [, сообщение])`

Цепочка (у каждого метода опциональный последний параметр — описание проверки):
- равенство/сравнение: `.Равно` / `.НеРавно` / `.Больше` / `.БольшеИлиРавно` / `.Меньше` / `.МеньшеИлиРавно`;
- булево/существование: `.ЭтоИстина` / `.ЭтоЛожь` / `.Заполнено` / `.НеЗаполнено` / `.ЭтоНеопределено` / `.Существует`;
- тип: `.ИмеетТип("Число")` / `.ИмеетТип(Тип("Строка"))`;
- строки: `.Содержит` / `.НеСодержит` / `.НачинаетсяС` / `.ЗаканчиваетсяНа` / `.ИмеетДлину(N)` / `.СодержитСтрокуПоШаблону("regex")`;
- коллекции: `.ИмеетДлину(N)` / `.Содержит(Элемент)` / `.КаждыйЭлементСодержитСвойство("X")`;
- навигация: `.Свойство("Реквизит")` / `.Свойство("Товары[0].Номенклатура")` / `.Объект()`;
- исключения: `ЮТест.ОжидаетЧто(Модуль).Метод("Имя").Параметр(X).ВыбрасываетИсключение("фрагмент")` (или `.НеВыбрасываетИсключение()`).

Безусловно: `ЮТест.Упал("почему")`, `ЮТест.Пропустить("причина")`. API **только русское** (англоязычных алиасов нет).

## Запуск через MCP

- **`run_yaxunit_tests`** — запускает прогон, поллит до `timeout` сек, возвращает JUnit-Markdown-отчёт (плюс `report.md`/`junit.xml` на диск). Фильтры (массивы, AND): `extensions`, `modules`, `tests` (формат `Модуль.Метод`). Без фильтра — дефолт `filter.extensions=["tests"]`.
- **`debug_yaxunit_tests`** — запуск в debug-режиме (срабатывают точки останова); затем `wait_for_break` → `get_variables` / `evaluate_expression` / `step` / `resume`. Пин к одному тесту (`tests=["Модуль.Метод"]`) для предсказуемости.

### Гочи этого репо (выстрадано)

- **Конфиг «TestConfiguration Thin Client» без `applicationId`-атрибута** → `updateBeforeLaunch=true` падает `Pre-launch preparation failed: Application not found`. **Решение:** сначала вручную `update_database(projectName="TestConfiguration", applicationId=<из get_applications>, confirm=true)`, затем `run_yaxunit_tests(launchConfigurationName="TestConfiguration Thin Client", updateBeforeLaunch=false)`.
- **`update_database` под confirm-preview** → первый вызов даёт превью, нужен `confirm=true` (это деструктив на инфобазе, IRREVERSIBLE — только по явной просьбе).
- **Расширение должно быть задеплоено в инфобазу** (`update_database`) перед прогоном; изменил тест-модуль на диске → `clean_project(projectName="TestConfiguration.tests")` (refresh из disk) → `update_database` (deploy) → `run_yaxunit_tests`.
- **EDT-проект расширения называется `<base>.<extName>`** (напр. `TestConfiguration.tests`) — используй это имя в `clean_project`/`get_project_errors`/`get_module_structure`. А вот в `filter.extensions` идёт имя *конфигурации расширения* (`tests`).
- **Ожидаемые EDT-варнинги** (НЕ ошибки, прогон от них не страдает): `Variable 'ЮТест'/'ЮТТесты' is not defined` — движок не в EDT-воркспейсе, резолвится в рантайме из загруженного `.cfe`; `common-module-type` — БСП-style-мнение про контекст-флаги server-модуля, к тест-модулям неприменимо.
- Клиентский таймаут MCP-вызова может быть короче серверного поллинга — ставь `timeout` поменьше (≈30) и при `Pending` перевызывай теми же аргументами (тул реатачится по run-key, не плодит запуск).

## Пример в репо

`tests/tests` — расширение `tests` (префикс `tests_`), модуль [tests_SampleTests](../../../tests/tests/src/CommonModules/tests_SampleTests/Module.bsl): 4 теста (equality; cross-config вызов `Calc.Add` из расширения; цепочка строковых ассертов; параметризованный ×3 = 6 кейсов). Прогон: `run_yaxunit_tests(launchConfigurationName="TestConfiguration Thin Client", updateBeforeLaunch=false)` → **6/6 PASSED** (предварительно один раз `update_database(...confirm=true)`).

## Источники
- [YAxUnit (движок + docs)](https://bia-technologies.github.io/yaxunit/) · [репозиторий](https://github.com/bia-technologies/yaxunit)
- [edt-test-runner (EDT-плагин)](https://bia-technologies.github.io/edt-test-runner/)
