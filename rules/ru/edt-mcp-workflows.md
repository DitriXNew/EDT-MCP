# Типовые сценарии работы с EDT-MCP

Это «cookbook»: короткие рецепты на частые задачи. Параметры инструментов смотри в `edt-mcp-tools.md` или в описаниях самих tool-ов.

## 1. Онбординг — первый контакт с проектом

Цель: понять, что за проект, какая конфигурация, какие подсистемы и где смотреть код.

1. `get_edt_version` — фиксируешь версию EDT (полезно для последующих ответов про совместимость).
2. `list_projects` — список проектов workspace, выбираешь нужный.
3. `get_configuration_properties` с `projectName` — имя, версия, ключевые свойства конфигурации.
4. `get_problem_summary` (опц. `projectName`) — общая картина: сколько ошибок и где. Сразу понятно, в каком состоянии конфигурация.
5. `list_subsystems` — обзор дерева подсистем.
6. `get_metadata_objects` с `metadataType: commonModules`, `limit: 100` — посмотри ключевые общие модули.
7. Зафиксируй для себя найденные паттерны (тип конфигурации, версия БСП, ключевые общие модули, расширения), чтобы не перезапрашивать одно и то же дальше по сессии.

## 2. Чтение кода объекта

Перед правкой/анализом конкретного объекта (документа, справочника, общего модуля):

1. `get_metadata_details` с массивом FQN — свойства, реквизиты, табличные части.
2. `list_modules` с `projectName` (фильтруй сам) — модули проекта/объекта.
3. `get_module_structure` с `projectName`+`modulePath` — карта методов и регионов модуля.
4. `read_method_source` с `projectName`+`modulePath`+`methodName` — конкретный метод. **Не** `read_module_source` целиком, если можно ограничиться методом или диапазоном строк (`startLine`/`endLine`).

## 3. Поиск использований и влияния

Перед изменением метода или объекта:

- **Объект метаданных (top-level)**: `find_references` с `objectFqn` — все ссылки в коде, формах, ролях, метаданных. Для вложенных (реквизиты, табличные части) этот инструмент **не работает**.
- **BSL-метод**: `get_method_call_hierarchy` с `projectName`+`modulePath`+`methodName`+`direction` (`callers` | `callees`).
- **Перейти к определению**: `go_to_definition` с `projectName`+`symbol` (имя метода или FQN объекта).
- **Текстовый поиск по коду**: `search_in_code` с `outputMode: count` сначала (оценка масштаба), потом `full` для деталей.
- **Тип/сигнатура на позиции**: `get_symbol_info` с `projectName`+`modulePath`+`line`+`column`.

## 4. Правка BSL-кода

См. `edt-mcp-write-safety.md` для деталей.

Сокращённо:
1. `read_method_source` → запомни тело.
2. Подготовь новую версию метода целиком (или фрагмента для `searchReplace`).
3. `write_module_source` с `mode: "searchReplace"`, `oldSource` (что заменить) и `source` (на что). Не отключай syntax-check без причины (`skipSyntaxCheck`).
4. Проверь ответ на ошибки синтаксиса/семантики. При наличии — исправь.
5. `get_project_errors` с `projectName` и `objects: ["<FQN>"]` — финальная проверка по изменённому объекту. Если правок много — `get_problem_summary` по проекту для общей картины.

## 5. Новый запрос 1С

1. Подготовь текст запроса.
2. `validate_query` с `projectName` и `queryText`. Если запрос для СКД — `dcsMode: true`.
3. Если ошибок нет — вставь в код через `write_module_source`.

## 6. Рефакторинг метаданных

### Переименование

1. Если переименовываешь **top-level объект** (`Catalog.X`, `Document.Y`, `CommonModule.Z`) — сперва `find_references` для оценки масштаба. Для вложенных (реквизиты, табличные части) этот шаг пропускаем: `find_references` для них недоступен.
2. `rename_metadata_object` с `objectFqn` (текущий FQN) и `newName` (**короткое** новое имя, не FQN), **без** `confirm` — preview всех change points.
3. Если нужно — отключи отдельные изменения через `disableIndices` (формат — список индексов из preview).
4. Тот же вызов с `confirm: true` — применить.

### Удаление

1. Для top-level — `find_references` для оценки масштаба. Для вложенных — пропускаем.
2. `delete_metadata` с `projectName`+`fqn` (полное имя объекта или подчинённого члена, например `Catalog.Products` или `Document.SalesOrder.Attribute.Amount`), без `confirm` — preview affected references.
3. С `confirm: true` — применить.

### Создание объекта или члена (например, реквизита)

`create_metadata` с `projectName`+`fqn` — адресует узел по полному имени FQN. Top-level объект — `Type.Name` (например, `Catalog.Products`); подчинённый член — `Type.Name.Kind.Name` (например, `Catalog.Products.Attribute.Weight`, `InformationRegister.Prices.Resource.Sum`, `Enum.Colors.EnumValue.Red`). Вид (kind) выводится из FQN, поэтому один инструмент и создаёт объекты, и добавляет члены — без ручной правки `.mdo`. Опционально передай `properties` (`[{name, value, language?}]`), чтобы задать `synonym` / `comment` при создании; остальные свойства задавай потом через `modify_metadata`.

### Задание свойств

`modify_metadata` с `projectName`+`fqn`+`properties` (`[{name, value, language?}]`) — задаёт назначаемые свойства объекта или члена по FQN (синоним, комментарий, структурированный `type` и прочие назначаемые скалярные/булевы/целочисленные/enum-свойства), с валидацией по набору назначаемых свойств и допустимым enum-литералам. Что можно задать — смотри через `get_metadata_details` (`assignable: true`). Свойство `name` (переименование) здесь запрещено — используй `rename_metadata_object`.

## 7. Отладка

`debug_launch` принимает либо **`launchConfigurationName`** (готовая launch-конфигурация из EDT, в т.ч. Attach), либо пару **`projectName` + `applicationId`** (без существующей конфигурации). Запоминай `applicationId` из ответа — дальше он нужен для `wait_for_break`, `start_profiling`, `get_applications`.

1. `list_configurations` или `get_applications` — узнать, что запускать.
2. `set_breakpoint` с `projectName`+`module`+`lineNumber` — точки останова.
3. `debug_launch` — запуск. Сохрани `applicationId` из ответа.
4. Пользователь выполняет действие в 1С:Предприятии.
5. `wait_for_break` с `applicationId` — ждёшь suspend. В ответе будет `threadId` и `frameRef`.
6. `debug_status` → `get_variables` (`frameRef` или `threadId`+`frameIndex`) → `evaluate_expression` → `step` (`threadId` + `kind`: `over`|`into`|`out`) → `resume`.
7. По завершении: `remove_breakpoint` для всех временных точек.

## 8. Запуск приложения и обновление ИБ

- Запуск в режиме предприятия (через дебаг или просто проверить): `debug_launch` по `launchConfigurationName` или `projectName`+`applicationId`.
- Обновление информационной базы: `update_database` с `launchConfigurationName` или `projectName`+`applicationId`. Используй после правок метаданных.

## 9. Прогон тестов YAxUnit

- Обычный прогон: `run_yaxunit_tests` с `projectName` — JUnit XML парсится, ответ в Markdown.
- При падении и непонятности: `debug_yaxunit_tests` + `set_breakpoint` в нужный тест → дальше как раздел 7.

## 10. Профилирование

1. Должен быть активный debug-сеанс (см. раздел 7), у тебя на руках `applicationId`.
2. `start_profiling` с `applicationId` — toggle ON.
3. Выполняешь сценарий в 1С:Предприятии.
4. `start_profiling` с тем же `applicationId` ещё раз — toggle OFF.
5. `get_profiling_results` (опц. `moduleFilter`, `minFrequency`) — per-module/per-line, call counts, timing.

## 11. Работа с формами

1. `get_form_layout_snapshot` с `projectName`+`formPath`, `mode: compact` — структура формы как YAML.
2. Если нужно визуально — `get_form_screenshot` с `projectName`+`formPath`.
3. Правки `.form` — через `Read`+`Edit` (соблюдай правила `edt-metadata.md`).

## 12. Анализ ошибок и проверок

1. `get_problem_summary` (опц. `projectName`) — обзор по проектам/severity.
2. `get_project_errors` с фильтрами `severity`, `objects`, `checkId`, `limit` — детали по подмножеству.
3. `get_check_description` с `checkId` — что именно проверяет правило и как чинить.
4. После массовых правок: `revalidate_objects` по конкретным FQN или `clean_project` для полной ревалидации.

## 13. Справка платформы и подсказки кода

- Документация типа платформы (методы, свойства, конструкторы): `get_platform_documentation`.
- Подсказки в конкретной позиции BSL (после точки, имени переменной): `get_content_assist` с `projectName`+`filePath`+`line`+`column`. Файл должен быть сохранён.
- Тип/hover-инфо для символа: `get_symbol_info`.

## 14. Экспорт / импорт XML

- `export_configuration_to_xml` — выгрузка проекта в каталог XML (аналог пункта меню EDT). Для отдачи проекта в репозиторий формата XML или в EDT/Designer.
- `import_configuration_from_xml` — обратная операция, создаёт новый EDT-проект.

## 15. Перевод (LanguageTool)

1. `get_translation_project_info` — настроенные storage и провайдеры.
2. `generate_translation_strings` — сгенерировать строки.
3. `translate_configuration` — пропагировать изменения словаря в артефакты.

## 16. Завершение работы — что сказать пользователю

После серии правок отчёт пользователю **должен** включать не только «что я сделал», но и **что нужно проверить руками** — то, что ИИ через MCP подтвердить не может. Минимум:

- **Список изменённых объектов** (FQN модулей, метаданных, форм) — чтобы пользователь знал, куда смотреть.
- **Что уже проверено через MCP** — например, `get_project_errors` по изменённому объекту вернул 0 ошибок, `validate_query` прошёл, тесты `run_yaxunit_tests` зелёные. Если что-то не проверено — явно скажи почему.
- **Что нужно проверить пользователю руками:**
  - визуальный рендеринг формы, если правил `.form` или код, влияющий на форму;
  - поведение в режиме предприятия (`1С:Предприятие`) — особенно для логики, которой нет в тестах;
  - производительность под нагрузкой, если правки касались запросов в горячих местах;
  - обновление информационной базы, если менялись метаданные;
  - открытые редакторы EDT с тем же модулем (могут показывать старую версию — рекомендуй перечитать файл с диска).
- **Что осталось незавершённым** — если задача шире, чем сделано в этой сессии, перечисли явно.

Не пиши «всё готово, всё работает», если часть проверок требует UI, который тебе недоступен. Честнее сказать «MCP-проверки прошли, но финальное подтверждение работы фичи — за вами».
