# Карта инструментов MCP-сервера EDT-MCP

> Источник истины — раздел «Available Tools» в `README.md` репозитория EDT-MCP. Если расходится с этим файлом — верь README.
>
> Всего инструментов: **57**, разбиты на 9 групп.

## Префиксы имён в разных клиентах

MCP-клиенты именуют инструменты по-разному. Реальное имя в своём клиенте определяй по ответу `tools/list`, а не угадывай:

- Claude Code: `mcp__EDT-MCP-Server__list_projects`
- Cursor: `mcp_EDT-MCP-Server_list_projects`
- Прочие: см. документацию клиента

В этом документе используются **короткие имена**: `list_projects`.

## Алгоритм выбора инструмента (как ИИ — читай первым)

Эта секция важнее любой таблицы ниже. Сначала смотри сюда, потом за деталями параметров — в соответствующую группу.

| Задача пользователя | Стартовый инструмент | Почему |
|---|---|---|
| «Что это за проект?» | `list_projects` → `get_configuration_properties` | Базовый онбординг |
| «Какие ошибки в проекте?» | `get_problem_summary` **первым**, потом `get_project_errors` с фильтрами | Сводка дёшева, детали дороги |
| «Где реализована/используется X?» | объект метаданных → `find_references`; метод → `get_method_call_hierarchy`; произвольный текст → `search_in_code` с `outputMode: count` для оценки | Семантический поиск точнее, чем grep |
| «Прочитай метод X модуля Y» | `read_method_source` | **Никогда** не загружай весь модуль ради одного метода |
| «Покажи код модуля целиком» | сначала `get_module_structure` (карта), потом `read_module_source` только если действительно нужен весь файл | Экономия токенов |
| «Измени код в методе» | `read_method_source` → `write_module_source` с `mode: searchReplace` | См. `edt-mcp-write-safety.md` |
| «Переименуй объект метаданных» | `rename_metadata_object` **без** `confirm` (preview) → тот же вызов с `confirm: true` | Ручная правка XML каскадно ломает ссылки |
| «Удали объект / член метаданных» | `delete_metadata` **без** `confirm` (preview) → тот же вызов с `confirm: true` | Ручная правка XML каскадно ломает ссылки |
| «Создай новый объект» | `create_metadata` с FQN верхнего уровня (`Catalog.Products`) | Дефолтное наполнение EDT + корректный UUID; не собирай `.mdo` руками |
| «Добавь реквизит / табличную часть / измерение / ресурс» | `create_metadata` с FQN члена (`Catalog.Products.Attribute.Weight`) | Не редактируй `.mdo` руками; вид выводится из FQN |
| «Установи тип реквизита / синоним / другое свойство» | `modify_metadata` (структурная спецификация `type`); какие свойства назначаемы — `get_metadata_details(assignable: true)` | Не редактируй `.mdo` руками ради назначаемых свойств |
| «Какие свойства можно задать у объекта/члена?» | `get_metadata_details(assignable: true)` | Список назначаемых свойств + допустимые значения |
| «Проверь запрос 1С» | `validate_query` (для СКД — `dcsMode: true`) | Перед вставкой текста запроса в код |
| «Что в этой форме?» | `get_form_layout_snapshot` с `mode: compact` (YAML); `get_form_screenshot` если нужен визуал | YAML дешевле PNG |
| «Что показывает платформа для типа X?» | `get_platform_documentation` | Не угадывай сигнатуры |
| «Запусти / отладь / обнови ИБ» | `list_configurations` → `debug_launch` / `update_database` | Сперва узнай имя launch-конфигурации |
| «Прогон тестов» | `run_yaxunit_tests`; для отладки падающих — `debug_yaxunit_tests` + `set_breakpoint` | |
| «Что значит ошибка с checkId Z?» | `get_check_description` | |

Если инструмент возвращает `tool is disabled` — текущий пресет (см. ниже) его скрывает. **Не пытайся обходить**, сообщи пользователю и предложи переключить пресет.

## Профили доступа (пресеты)

В настройках плагина (`Window → Preferences → MCP Server → Tools`) пользователь выбирает пресет. Точные наборы — из `ToolPreset.java`:

| Пресет | Что выключено |
|---|---|
| **All Tools** | Ничего (все 57 инструментов) |
| **Analysis Only** | Группы Applications & Testing, Debugging, BSL Code, Refactoring, Translation + `export_configuration_to_xml` + `import_configuration_from_xml`. Доступны: Core/Project (кроме export/import), Errors & Problems, Code Intelligence, Tags |
| **Code Review** | То же, что Analysis Only, **минус** добавляются доступными все инструменты BSL Code **кроме** `write_module_source`. То есть доступны `read_method_source`, `read_module_source`, `get_module_structure`, `list_modules`, `search_in_code`, `get_method_call_hierarchy`, `go_to_definition`, `get_symbol_info`, `get_form_layout_snapshot`, `get_form_screenshot`, `validate_query` |
| **Development** | Только группа Debugging (включая `debug_yaxunit_tests`, `start_profiling`, `get_profiling_results`). Refactoring, Translation, BSL Code, Applications — доступны |

## Настраиваемые дефолты параметров

В UI настроек у некоторых инструментов задаются дефолты лимитов (применяются, когда клиент не передал параметр):

| Инструмент | Параметр | Default | Диапазон |
|---|---|---|---|
| `get_project_errors` | `limit` | 100 | 1–1000 |
| `get_markers` | `limit` | 100 | 1–1000 |
| `get_metadata_objects` | `limit` | 100 | 1–1000 |
| `get_content_assist` | (limit) | 100 | 1–1000 |
| `search_in_code` | `maxResults` | 100 | 1–500 |
| `search_in_code` | `contextLines` | 2 | 0–5 |

## 9 групп инструментов

### 1. Core / Project — проект и конфигурация (8)

| Инструмент | Назначение | Когда использовать |
|---|---|---|
| `get_edt_version` | Версия EDT | Один раз при старте сессии |
| `list_projects` | Список проектов workspace | При первом контакте с workspace |
| `get_configuration_properties` | Свойства конфигурации 1С (имя, версия, подсистемы) | При онбординге проекта |
| `clean_project` | Очистка маркеров и полная ревалидация | Когда `get_project_errors` зависает или показывает устаревшие данные |
| `revalidate_objects` | Ревалидация по массиву FQN (параметр `objects: ["Document.Foo"]`); пустой массив = весь проект | Точечно после правок |
| `get_check_description` | Описание EDT-проверки по `checkId` | Когда нужно разобраться, что значит конкретная ошибка |
| `export_configuration_to_xml` | Экспорт конфигурации в XML (EDT menu: Export → Configuration to XML Files) | По запросу пользователя |
| `import_configuration_from_xml` | Импорт конфигурации из XML (обратный к экспорту) | По запросу пользователя |

### 2. Errors & Problems — ошибки и задачи (3)

| Инструмент | Назначение | Когда использовать |
|---|---|---|
| `get_problem_summary` | Сводка по числу проблем по проектам и severity | **Первым** — даёт картину одним вызовом |
| `get_project_errors` | Детальные ошибки. Фильтры: `projectName`, `severity` (ERRORS/BLOCKER/CRITICAL/MAJOR/MINOR/TRIVIAL), `checkId` (подстрока), `objects` (массив FQN), `limit` (default 100, max 1000) | После сводки — для целевого изучения |
| `get_markers` | Маркеры workspace — закладки и/или TODO/FIXME-маркеры. Фильтры: `markerKind` (`bookmark`/`task`; без него — оба), `projectName`, `filePath`, `priority` (только для задач) | По запросу или при аудите технического долга |

### 3. Code Intelligence — навигация и подсказки (7)

| Инструмент | Назначение | Когда использовать |
|---|---|---|
| `get_content_assist` | Подсказки в точке кода (типы, методы) | При работе в конкретной позиции BSL |
| `get_platform_documentation` | Документация платформы (типы, методы, свойства, конструкторы) | При сомнениях в сигнатуре платформы 1С |
| `get_metadata_objects` | Список объектов конфигурации с фильтрами `metadataType`, `nameFilter`, `limit` (default 100, max 1000), `language` | Обзор объектов; **всегда** с фильтром |
| `get_metadata_details` | Детальные свойства объектов по массиву FQN (`objectFqns: [...]`); FQN могут адресовать члены (`Catalog.Products.Attribute.Weight`). С `assignable: true` возвращает схему **назначаемых** свойств (вид значения, текущее значение, допустимые литералы перечисления) — то, что умеет ставить `modify_metadata` | После того как нашёл нужные объекты; перед `modify_metadata` — с `assignable: true` |
| `list_subsystems` | Дерево подсистем (плоская таблица, рекурсивно по умолчанию) | Знакомство со структурой конфигурации |
| `get_subsystem_content` | Содержимое подсистемы по FQN: свойства, объекты, вложенные подсистемы | Углубление в конкретную подсистему |
| `find_references` | Все ссылки на объект метаданных в коде, формах, ролях. **Только top-level объекты** (`Catalog.X`, `Document.Y`, `CommonModule.Z`); для вложенных (реквизиты, табличные части, формы) вернёт ошибку — используй сразу `rename_metadata_object`/`delete_metadata` | Перед переименованием/удалением top-level объекта |

### 4. Tags — теги (2)

| Инструмент | Назначение |
|---|---|
| `get_tags` | Список всех тегов проекта с описанием и счётчиком объектов |
| `get_objects_by_tags` | Объекты с фильтром по тегам, с FQN и описаниями тегов |

### 5. Applications & Testing — приложения и тесты (5)

| Инструмент | Назначение | Когда использовать |
|---|---|---|
| `get_applications` | Список информационных баз проекта со статусом обновления | Для отладки/обновления |
| `list_configurations` | Launch-конфигурации (runtime-client + Attach) с текущим running/suspended | Перед `debug_launch` |
| `update_database` | Обновление ИБ. Идентификация двумя способами: `launchConfigurationName` **или** `projectName + applicationId`. Режим full/incremental | По запросу |
| `debug_launch` | Запуск в режиме отладки. Аналогично: `launchConfigurationName` (включая Attach to 1C:Enterprise Debug Server) **или** `projectName + applicationId` | По запросу |
| `run_yaxunit_tests` | Запуск тестов YAxUnit, парсинг JUnit XML, Markdown-отчёт | После правок, если в проекте есть тесты |

### 6. Debugging — отладчик (12)

| Инструмент | Назначение |
|---|---|
| `set_breakpoint` | Поставить брейкпойнт (EDT module path или абсолютный путь + строка) |
| `remove_breakpoint` | Снять брейкпойнт по id или по project+module+line |
| `list_breakpoints` | Список активных, опционально с фильтром по проекту |
| `wait_for_break` | Блокирующее ожидание suspend (например, попадание в брейкпойнт) |
| `get_variables` | Прочитать переменные стек-фрейма (lazy expand для вложенных) |
| `step` | Step over/into/out, возвращает новый снимок |
| `resume` | Снять с паузы поток или все потоки таргета |
| `evaluate_expression` | Выполнить BSL-выражение в контексте кадра |
| `debug_yaxunit_tests` | Запуск YAxUnit-тестов в DEBUG-режиме, чтобы срабатывали брейкпойнты |
| `debug_status` | Статус активных отладочных запусков: mode, suspend, потоки, top frame |
| `start_profiling` | Toggle замера производительности на активном debug target |
| `get_profiling_results` | Результаты замера: per-module / per-line, счётчики вызовов, тайминги, покрытие |

Типовой цикл — см. `edt-mcp-workflows.md`, раздел «Отладка».

### 7. BSL Code — работа с кодом (12)

| Инструмент | Назначение | Замечания |
|---|---|---|
| `list_modules` | Список BSL-модулей с типом и parent-объектом | Используй фильтры, не запрашивай всё |
| `get_module_structure` | Структура: процедуры/функции с сигнатурами, регионы, параметры | **Перед** чтением кода — смотри карту |
| `read_module_source` | Прочитать модуль целиком или диапазон строк (с YAML-frontmatter) | Только если контекст метода завязан на области/глобальные переменные |
| `write_module_source` | Запись модуля. Режимы: `searchReplace` (default), `replace`, `append`. Идентификация по `modulePath` **или** `objectName + moduleType` (`ObjectModule`/`ManagerModule`/`FormModule`/`CommandModule`/`RecordSetModule`; для FormModule нужен `formName`, для CommandModule — `commandName`). Перед записью проверяется баланс блочных ключевых слов; `skipSyntaxCheck=true` форсирует запись. **См. `edt-mcp-write-safety.md` — самая опасная зона** | |
| `read_method_source` | Прочитать **конкретный** метод по имени (нечувствительно к регистру) | **Предпочтительный** способ чтения, экономит токены |
| `search_in_code` | Полнотекстовый/regex поиск по BSL. Параметры: `query`, `isRegex`, `caseSensitive`, `fileMask`, `metadataType`, `outputMode: full/count/files`, `maxResults` (default 100, max 500), `contextLines` (default 2, max 5) | Используй вместо `Grep` по `.bsl` файлам. `count` или `files` — сперва для оценки масштаба |
| `get_method_call_hierarchy` | Кто вызывает метод (callers) / кого вызывает он (callees) | Перед модификацией метода — оцени влияние |
| `go_to_definition` | Переход к определению (метод по имени, объект метаданных по FQN) | Как F12 в IDE |
| `get_symbol_info` | Type/hover-инфо в позиции BSL: выведенные типы, сигнатуры, документация | Для проверки выведенных типов |
| `get_form_layout_snapshot` | YAML-снимок WYSIWYG формы: bounds, types, display props. `mode: compact` (default) — только видимые элементы; `full` — всё | Перед правкой формы — снимок структуры |
| `get_form_screenshot` | PNG формы из WYSIWYG (embedded image resource) | Когда нужен визуальный контекст |
| `validate_query` | Проверка текста запроса 1С (синтаксис + семантика). Параметр `dcsMode: true` — для запросов СКД | **Перед** вставкой нового запроса в код |

### 8. Refactoring — рефакторинг метаданных (4)

| Инструмент | Назначение | Когда использовать |
|---|---|---|
| `rename_metadata_object` | Переименование с каскадным обновлением (BSL-код, формы, метаданные). Workflow: 1) вызов без `confirm` — preview всех change points с индексами; 2) (опционально) `disableIndices: "2,3,5"` для пропуска отдельных изменений; 3) `confirm: true`. Параметр `maxResults` (default 20, 0 = без лимита) ограничивает preview. Поддерживает русские FQN. **Единственный** инструмент переименования — `modify_metadata` имя не меняет | **Только** так переименовывать; ручная правка XML опасна |
| `delete_metadata` | Удаление объекта **или** члена по FQN с каскадной очисткой ссылок в BSL/формах/других метаданных. Параметры: `projectName`, `fqn`, опц. `confirm`. Две фазы: вызов без `confirm` — preview затронутых ссылок; `confirm: true` — применение | **Только** так удалять; ручная правка XML опасна |
| `create_metadata` | Создать узел метаданных по 1C full-name FQN: объект верхнего уровня (`Catalog.Products` / `Справочник.Товары`) **или** подчинённый член (`Catalog.Products.Attribute.Weight`, `InformationRegister.Prices.Dimension.Product`, `Document.Order.TabularSection.Goods`, `Enum.Colors.EnumValue.Red`). Вид выводится из FQN; токены типа и вида допускаются на русском и английском. Параметры: `projectName`, `fqn`, опц. `properties` (массив `{name, value, language?}`), опц. `expectedNotExists`. При создании в `properties` принимаются **только** `synonym` и `comment` — остальные свойства задаёт `modify_metadata`. Дубликаты отклоняются. Поддерживаемые типы верхнего уровня: `Catalog`, `Document`, `InformationRegister`, `AccumulationRegister`, `Enum`, `CommonModule`, `Report`, `DataProcessor`. Пока поддерживается один уровень вложенности члена (поле табличной части пока отклоняется) | Вместо ручной сборки нового `.mdo`; после — прогнать `get_project_errors` |
| `modify_metadata` | Установить свойства объекта **или** члена по FQN. Параметры: `projectName`, `fqn`, `properties` (массив `{name, value, language?}`). Каждое свойство валидируется: должно быть назначаемым (иначе ошибка перечисляет назначаемые и указывает на `get_metadata_details(assignable: true)`), значение перечисления — одним из допустимых литералов. Умеет `synonym` (по коду языка), `comment` и `type` (структурная спецификация). Для переименования — `rename_metadata_object` (свойство `name` отклоняется) | Задать тип реквизита, синоним и прочие назначаемые свойства без ручной правки `.mdo` |

### 9. Translation (LanguageTool) — перевод (3)

| Инструмент | Назначение |
|---|---|
| `generate_translation_strings` | Сгенерировать строки перевода (`.lstr`/`.trans`/`.dict`) для конфигурации, с опциями translation storage и collection. EDT menu: Translation → Generate translation strings |
| `translate_configuration` | Пропагировать изменения словаря из storage-проектов (или внутриконфигурационных storages) в переведённые артефакты. EDT menu: Translation → Translate configuration |
| `get_translation_project_info` | Диагностика: translation storages проекта и ID доступных провайдеров |
