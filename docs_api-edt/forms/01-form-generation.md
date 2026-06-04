# Генерация форм

Источник: `com._1c.g5.v8.dt.form_31.1.4` (пакет `com._1c.g5.v8.dt.form.generator;version="4.0.0"`, экспортируется).

## Основной интерфейс `IFormGenerator`

`com._1c.g5.v8.dt.form.generator.IFormGenerator`

```java
public interface IFormGenerator {

    // Генерирует модель формы (содержимое .form) для объекта метаданных.
    Form generateForm(
        MdObject owner,                       // владелец формы (Catalog, Document, register, ...)
        BasicForm form,                       // MD-объект формы (Form / CommonForm)
        FormType type,                        // вид формы (см. ниже)
        ScriptVariant scriptVariant,          // вариант языка (RUSSIAN / ENGLISH) из конфигурации
        String name,                          // имя формы
        Version version,                      // версия платформы (из IV8Project.getVersion())
        FormFieldInfo fields,                 // дерево полей для размещения (см. ниже)
        Integer columnCount,                  // число колонок (может быть null)
        InterfaceCompatibilityMode mode);     // режим совместимости интерфейса

    // Генерация содержимого модуля формы (Module.bsl).
    Supplier<InputStream> generateModuleContent(
        FormType type, ScriptVariant sv, String name, Version version);

    Supplier<InputStream> generateModuleContent(
        FormType type, ScriptVariant sv, String name, Version version, String extra);

    // Генерация дополнительных вложений формы (например статических ресурсов).
    Map<IPath, Supplier<InputStream>> generateAttachments(
        FormType type, ScriptVariant sv, String name, Version version);
}
```

Возвращаемый тип `com._1c.g5.v8.dt.form.model.Form` — это **содержимое** формы (дерево
элементов + реквизиты), отдельный top-BM-объект, который сохраняется в `Form.form`.
См. [03-form-persistence.md](03-form-persistence.md).

## Перечисление `FormType`

`com._1c.g5.v8.dt.form.generator.FormType` (enum). Полный список значений:

| FormType | Назначение (1C) |
|---|---|
| `GENERIC` | Произвольная (пустая) форма |
| `OBJECT` | Форма элемента / объекта |
| `FOLDER` | Форма группы (для иерархических справочников) |
| `RECORD_SET` | Форма набора записей (регистры) |
| `RECORD` | Форма записи (независимый регистр сведений) |
| `LIST` | Форма списка |
| `CHOICE` | Форма выбора |
| `FOLDER_CHOICE` | Форма выбора группы |
| `CONSTANTS` | Форма констант |
| `SEARCH` | Форма поиска |
| `REPORT` | Форма отчёта |
| `REPORT_SETTINGS` | Форма настроек отчёта |
| `REPORT_VARIANT` | Форма варианта отчёта |
| `SAVE` | Форма сохранения |
| `LOAD` | Форма загрузки |
| `DYNAMIC_LIST` | Форма динамического списка |
| `CHANGE_HISTORY` | Форма истории изменений |
| `VERSION_DATA` | Форма данных версии |
| `VERSION_DIFFERENCES` | Форма различий версий |

Соответствие пользовательским названиям для MCP-инструмента:

- `item` / `ФормаЭлемента` -> `OBJECT`
- `list` / `ФормаСписка` -> `LIST`
- `choice` / `ФормаВыбора` -> `CHOICE`
- `folder` / `ФормаГруппы` -> `FOLDER`
- `folderChoice` / `ФормаВыбораГруппы` -> `FOLDER_CHOICE`
- `record` -> `RECORD`, `recordSet` -> `RECORD_SET`

## `FormFieldInfo` — дерево полей для размещения

`com._1c.g5.v8.dt.form.generator.FormFieldInfo`

```java
public class FormFieldInfo {
    public FormFieldInfo(String name, boolean readOnly, TypeDescription type,
                         AbstractDataPath dataPath, boolean select);
    public boolean isSelect();
    public void setSelect(boolean);
    public String getName();
    public AbstractDataPath getDataPath();
    public TypeDescription getType();
    public boolean isReadOnly();
    public List<FormFieldInfo> getChildren();
    public void addChild(FormFieldInfo);
    public FormFieldInfo getParent();
}
```

- Описывает иерархию полей (с флагом `select` — попадает ли поле на форму).
- Строится мастером через `IFormFieldGenerator` (см. ниже).
- Для генерации формы «по умолчанию» дерево можно построить через `IFormFieldGenerator`
  либо передать минимальное значение (даст более общую форму). _(поведение при `null`
  отдельно не проверялось)_.

## `IFormFieldGenerator`

`com._1c.g5.v8.dt.form.generator.IFormFieldGenerator` — строит `FormFieldInfo` по
объекту метаданных (используется мастером `FormWizardModel`, поле `formFieldGenerator`).
Биндится в Guice вместе с `IFormGenerator` (см.
[infrastructure/01-services-di.md](../infrastructure/01-services-di.md)).

## Где это вызывается в самом EDT

Класс-оркестратор мастера: `com._1c.g5.v8.dt.form.ui.wizard.FormNewWizardRelatedModelsFactory`
(плагин `com._1c.g5.v8.dt.form.ui_24.x`) — единственное место, где вызывается
`IFormGenerator.generateForm(...)` с последующей привязкой результата. Подробный разбор
последовательности — в [03-form-persistence.md](03-form-persistence.md).

## Внимание: сигнатура нестабильна между релизами

`generateForm` менялась между версиями EDT (в `form_29.0.0` было 8 параметров без
`InterfaceCompatibilityMode`, в `form_31.1.4` — 9). Перед использованием см.
[infrastructure/03-edt-version-compatibility.md](../infrastructure/03-edt-version-compatibility.md)
— рекомендуется вызывать через рефлективный фасад с перебором перегрузок.
