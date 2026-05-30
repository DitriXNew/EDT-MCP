# Работа с метаданными EDT (`.mdo`, Form, прочие XML)

## Главное правило

Прежде чем редактировать `.mdo` или `Form.form` руками — **спроси себя**: не покрывает ли это MCP-инструмент?

| Задача | Инструмент | Почему лучше, чем руками |
|---|---|---|
| Создать новый top-level объект | `create_metadata_object` | Дефолтное наполнение EDT (как мастер «Создать»); UUID и `producedTypes` генерируются автоматически |
| Переименовать объект / реквизит / табличную часть / измерение / ресурс | `rename_metadata_object` | Каскадно правит BSL-код, формы, роли, подсистемы. Дает preview всех точек изменений |
| Удалить объект / реквизит и т. п. | `delete_metadata_object` | Чистит ссылки во всём проекте. Дает preview |
| Добавить реквизит в объект | `add_metadata_attribute` | Без рискованной ручной правки XML, UUID генерируется автоматически |
| Найти, где используется top-level объект | `find_references` | Полный поиск по метаданным, BSL, формам, ролям |

Ручная правка `.mdo` оправдана, когда:
- меняются свойства, для которых нет инструмента (например, `FullTextSearch`, `Format`, `DataLockControlMode`, набор `usePurposes` у формы, и т. п.);
- идёт массовое единообразное изменение, которое проще сделать поиском.

## Workflow `rename_metadata_object` / `delete_metadata_object`

Оба инструмента работают в две фазы — это защищает от каскадных правок «вслепую»:

1. **Preview**: вызови инструмент **без `confirm`** (или с `confirm: false`). Получишь:
   - таблицу change points с индексами, файлами, строкой/колонкой, описанием и флагом «обязательное / опциональное» (только для rename);
   - список проблем (ссылки, которые будут затронуты).
2. **Анализ**: посмотри, что в списке. Если есть нежелательные правки и они помечены как опциональные — запомни их индексы.
3. **Execute**: вызови **с `confirm: true`**. У `rename_metadata_object` можно дополнительно передать `disableIndices: "2,3,5"` — это исключит указанные change points из применения. **Отключать можно только опциональные** правки; обязательные точки изменений пропустить нельзя.

Параметр `maxResults` у `rename_metadata_object` — только размер preview-таблицы (default 20, `0` = без лимита). На само выполнение не влияет.

### FQN, которые понимают rename/delete

- Top-level: `Catalog.Products`, `Document.SalesOrder`, `CommonModule.MyModule`, `Subsystem.Sales`, и т. п. Русские имена типов тоже принимаются (`Справочник.Товары`).
- Вложенные дочерние объекты (только для rename/delete): `Catalog.Products.Attribute.Weight`, `Document.SalesOrder.TabularSection.Goods`, `InformationRegister.Prices.Dimension.Product`, `AccumulationRegister.Stock.Resource.Quantity`.

**Поддерживаемые child-типы** в FQN для rename/delete: `Attribute`, `TabularSection`, `Dimension`, `Resource` (и их русские формы единственного/множественного числа: `Реквизит`, `ТабличнаяЧасть`, `Измерение`, `Ресурс`). Другие виды (Forms, Commands, Templates, EnumValues, AccountingFlags и т. п.) этими инструментами **не правятся** — для них либо ручная XML-правка, либо отдельные инструменты.

## `find_references` — только top-level

`find_references` ищет ссылки на **верхнеуровневые объекты конфигурации** (`Catalog.X`, `Document.Y`, `CommonModule.Z` и т. п.). Передача под-объекта (`Catalog.X.Attribute.Y`, `Document.Z.Form.MainForm`) **вернёт ошибку** с пояснением.

Для поиска ссылок на реквизиты/табличные части используй:
- `rename_metadata_object` в preview-режиме (без `confirm`) — он покажет все места, где упоминается реквизит. Имя при этом не меняется.
- Либо `search_in_code` для текстового поиска по BSL.

## `create_metadata_object` — поддерживаемые типы

Создаёт новый top-level объект с тем же дефолтным наполнением, что и мастер EDT «Создать» (корректный UUID, `producedTypes`, формы/свойства по умолчанию, где применимо). Поддерживаемый `metadataType`: `Catalog`, `Document`, `InformationRegister`, `AccumulationRegister`, `Enum`, `CommonModule`, `Report`, `DataProcessor` (русские имена типов тоже принимаются).

Параметры: `projectName`, `metadataType`, `name` (обязательные); `synonym`, `comment`, `language` (опциональные). `name` должно быть корректным идентификатором 1С; объект с таким же типом и именем создать нельзя. `synonym` пишется для языка конфигурации по умолчанию, если не задан `language`.

Инструмент создаёт только сам объект. Чтобы добавить реквизиты / табличные части / задать тип реквизита — используй `add_metadata_attribute` или правь `.mdo` отдельным шагом. После создания прогони `get_project_errors` (и `revalidate_objects` на новый объект, если валидация выглядит устаревшей).

## `add_metadata_attribute` — поддерживаемые родители

Принимает `parentFqn` следующих типов: `Catalog`, `Document`, `ExchangePlan`, `ChartOfCharacteristicTypes`, `ChartOfAccounts`, `ChartOfCalculationTypes`, `BusinessProcess`, `Task`, `DataProcessor`, `Report`, `InformationRegister`, `AccumulationRegister`, `AccountingRegister`. Реквизит создаётся со свойствами по умолчанию и **корректно сгенерированным UUID** — UUID руками задавать не надо.

Если нужно сразу выставить тип реквизита, описание, синонимы и т. п. — отдельным шагом отредактируй `.mdo` руками или дождись отдельного инструмента для этого.

## Язык синонимов (`language`)

В инструментах чтения метаданных (`get_metadata_objects`, `get_metadata_details`, `list_subsystems`, `get_subsystem_content`) есть параметр `language` — код языка для значений `<synonym>` (например, `ru`, `en`). Если не задан — используется язык конфигурации по умолчанию. В русскоязычных проектах ставь `language: "ru"`, чтобы получать читаемые названия. На запись (`add_metadata_attribute`) этот параметр не влияет — синонимы при добавлении надо проставлять руками в `.mdo`.

## Ручная правка `.mdo` / Form XML: UUID v4

`.mdo` и `Form.form` — это XML, в котором почти каждый структурный элемент имеет атрибут `uuid="..."`. EDT/1С используют их как стабильные идентификаторы для refactoring, history и метаданных.

Если ты добавляешь новый элемент руками — **обязательно сгенерируй криптографически случайный UUID v4**. Иначе:
- два одинаковых UUID → EDT перестанет различать элементы, refactoring сломается;
- предсказуемый UUID → коллизия с уже существующим объектом проекта.

Это касается, в частности:
- `<attributes uuid="...">` — реквизиты
- `<tabularSections uuid="...">` и вложенные в них `<attributes uuid="...">` (поля табличной части)
- `<dimensions uuid="...">`, `<resources uuid="...">` — измерения/ресурсы регистров
- `<forms uuid="...">` — формы
- `<commands uuid="...">` — команды
- `<templates uuid="...">` — макеты
- `<enumValues uuid="...">` — значения перечислений
- В файле `Form.form`: новые `<items>`, `<attributes>`, `<commands>` формы

**Не трогай** `<producedTypes>` и поля `typeId`/`valueTypeId` внутри них — это автогенерируемые типы (`СправочникСсылка.X`, `СправочникМенеджер.X` и т. п.), EDT/1С формирует их самостоятельно. Если перезаписать вручную случайным UUID — типы рассинхронизируются с индексом, и `.mdo` сломается.

### Чего нельзя

- Плейсхолдер-UUID: `a1b2c3d4-...`, `00000000-...`, `11111111-...`
- Одинаковые UUID для разных элементов в одном или соседних файлах
- Последовательные/предсказуемые UUID
- Копирование UUID из соседнего реквизита «по образцу»

### Как сгенерировать

PowerShell:
```powershell
[guid]::NewGuid().ToString()
```

Пакетом:
```powershell
1..5 | ForEach-Object { [guid]::NewGuid().ToString() }
```

Bash (Linux/macOS):
```bash
uuidgen
```

## Структура элемента `<attributes>` (типовой шаблон)

```xml
<attributes uuid="<сгенерированный-uuid-v4>">
  <name>AttributeName</name>
  <synonym>
    <key>ru</key>
    <value>Отображаемое название</value>
  </synonym>
  <type>
    <types>DataType</types>
  </type>
  <minValue xsi:type="core:UndefinedValue"/>
  <maxValue xsi:type="core:UndefinedValue"/>
  <fillValue xsi:type="core:UndefinedValue"/>
  <fullTextSearch>Use</fullTextSearch>
  <dataHistory>Use</dataHistory>
</attributes>
```

## Порядок элементов

При добавлении нового элемента вставляй **после** существующих элементов того же типа (реквизиты после реквизитов, табличные части после табличных частей), сохраняя секционный порядок `.mdo` файла. Изменение порядка элементов в `.mdo` EDT воспринимает как изменение объекта и может породить лишние правки в системе контроля версий.

## Формы (`Form.form`)

- Перед изменением формы — `get_form_layout_snapshot` (YAML-структура) или `get_form_screenshot` (PNG). Без этого правок «вслепую» не делаем.
- В реквизитах и элементах формы UUID-правила те же.
- Если меняешь привязку формы к объекту/реквизиту — после правки прогони `get_project_errors`, форма не пересобирается автоматически.

## Обращение к ссылочным типам в `<types>`

Тип реквизита, ссылающийся на объект метаданных:
- `CatalogRef.<Name>` — справочник
- `DocumentRef.<Name>` — документ
- `EnumRef.<Name>` — перечисление
- `InformationRegisterRecordKey.<Name>` — ключ записи регистра сведений
- и т. д.

Имя объекта в ссылке должно **точно** совпадать с именем объекта в конфигурации (с учётом регистра).

## После любой правки `.mdo`

Прогоняй `get_project_errors` (или `get_problem_summary` после серии правок). EDT может не сразу заметить изменение — если ошибки выглядят странно, попробуй `revalidate_objects` на затронутые объекты.
