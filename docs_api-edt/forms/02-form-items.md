# Добавление элементов и реквизитов формы

Источник: `com._1c.g5.v8.dt.form_31.1.4`, пакеты `com._1c.g5.v8.dt.form.service.item`,
`com._1c.g5.v8.dt.form.service.attribute`.

После генерации формы (см. [01-form-generation.md](01-form-generation.md)) редактируется
**модель формы** `com._1c.g5.v8.dt.form.model.Form`:

- `form.getItems()` — дерево визуальных элементов (контейнеры `FormItemContainer`);
- `form.getAttributes()` — реквизиты формы (к ним привязываются поля через `AbstractDataPath`).

Все изменения выполняются внутри write-транзакции BM на объекте модели формы
(паттерн см. [03-form-persistence.md](03-form-persistence.md) и
[infrastructure/02-mcp-tool-pattern.md](../infrastructure/02-mcp-tool-pattern.md)).

## `IFormItemManagementService` — визуальные элементы

`com._1c.g5.v8.dt.form.service.item.IFormItemManagementService`

Ключевые методы (приведены «удобные» перегрузки; у большинства есть вариант с `int index`
для позиции, константа позиции «в конец» — `IFormItemManagementService.LAST`):

```java
// Поле (привязка к реквизиту через dataPath)
FormField addField(FormItemContainer parent, AbstractDataPath path, Form form, FormNewItemDescriptor d);
FormField addField(FormItemContainer parent, AbstractDataPath path, int index, Form form, FormNewItemDescriptor d);

// Группа
FormGroup addGroup(FormItemContainer parent, Form form, FormNewItemDescriptor d);
FormGroup addGroup(FormItemContainer parent, ManagedFormGroupType type, Form form, FormNewItemDescriptor d);
FormGroup addGroup(FormItemContainer parent, int index, ManagedFormGroupType type, Form form, FormNewItemDescriptor d);

// Таблица
Table addTable(FormVisualEntity parent, AbstractDataPath path, boolean withFields, Form form, FormNewItemDescriptor d);
Table addTable(FormItemContainer parent, int index, Form form, FormNewItemDescriptor d);
List<FormField> addTableFieldsByDataPath(Table table, AbstractDataPath path, Form form, FormNewItemDescriptor d);

// Поле + связанная таблица
FormField addFieldWithTable(FormItemContainer parent, AbstractDataPath path, boolean b, Form form, FormNewItemDescriptor d);

// Кнопка (привязка к команде)
Button addButton(FormItemContainer parent, Command cmd, AbstractDataPath path, Form form, FormNewItemDescriptor d);
Button addButton(FormItemContainer parent, int index, Command cmd, AbstractDataPath path, Form form, FormNewItemDescriptor d);

// Декорация (надпись/картинка)
Decoration addDecoration(FormItemContainer parent, Form form, FormNewItemDescriptor d);
Decoration addDecoration(FormItemContainer parent, ManagedFormDecorationType type, Form form, FormNewItemDescriptor d);

// Дополнение (Addition)
Addition addAddition(FormItemContainer parent, Form form, FormNewItemDescriptor d);
```

Общие параметры:

- `FormItemContainer parent` — родитель: корень формы (сам `Form` является контейнером)
  или вложенная `FormGroup` / `Table`;
- `AbstractDataPath path` — путь к реквизиту формы (например `Объект.Наименование` /
  `Object.Description`);
- `Form form` — модель формы;
- `FormNewItemDescriptor d` — дескриптор нового элемента (см. ниже);
- `int index` — позиция вставки (необязательно).

## `FormNewItemDescriptor`

`com._1c.g5.v8.dt.form.service.item.FormNewItemDescriptor`

```java
public class FormNewItemDescriptor {
    public FormNewItemDescriptor(String itemName,
                                 Map<String, String> itemTitle,   // язык -> заголовок
                                 boolean isFormattedText);
    public String getItemName();
    public Map<String, String> getItemTitle();
    public boolean isFormattedText();
}
```

- `itemName` — имя элемента (если нужно автоимя — генерируется сервисом именования, см. ниже);
- `itemTitle` — карта `код языка -> заголовок` (`ru`, `en`, ...);
- `isFormattedText` — форматированный текст (для декораций/надписей).

## `FormAttributeManagementService` — реквизиты формы

`com._1c.g5.v8.dt.form.service.attribute.FormAttributeManagementService` (публичный конструктор)

```java
public class FormAttributeManagementService {
    public FormAttributeManagementService();

    void addAttribute(Form form, FormAttribute attribute);
    void addAttributeColumn(IBmTransaction tx, Form form, AbstractDataPath path, FormAttributeColumn column);
    void cloneAttributeAdditionalColumns(IBmTransaction tx, AbstractDataPath path,
            FormAttributeColumn from, FormAttributeColumn to, boolean b);
    void replaceFormExtensionAttribute(IBmTransaction tx, FormAttribute attr, Form form);
    void deleteAttribute(IBmTransaction tx, AbstractFormAttribute attr, boolean b);
    void cloneAttributeExtInfo(IBmTransaction tx, Form form, TypeDescription type,
            FormAttribute from, FormAttribute to);
}
```

Типичный порядок: сначала при необходимости создаётся реквизит формы (`addAttribute`),
затем поле, привязанное к нему по `dataPath` (`IFormItemManagementService.addField`).

## Вспомогательные сервисы

- `com._1c.g5.v8.dt.form.service.naming.IFormItemNamingService`,
  `IFormItemNameGeneratorFactory` — генерация уникальных имён элементов;
- `com._1c.g5.v8.dt.form.service.item.IDataPathManagementService` — работа с `AbstractDataPath`;
- `com._1c.g5.v8.dt.form.service.FormUtil` — утилиты доступа к модели формы.

Все эти сервисы (как и `IFormGenerator`) резолвятся из Guice-инжектора плагина форм —
см. [infrastructure/01-services-di.md](../infrastructure/01-services-di.md).

## Низкоуровневая альтернатива (task-классы)

Те же операции внутри EDT реализованы как task-классы в
`com._1c.g5.v8.dt.form.service.item.task` (`AddFieldTask`, `AddGroupTask`, `AddTableTask`,
`AddButtonTask`, `AddDecorationTask`, `AddFieldWithTableTask`, `AddAdditionTask`,
`CloneFormItemTask`, `DeleteFormItemTask`, `MoveFormItemTask`, `MoveToEndFormItemTask`,
`UpdateFormDefaultButtonTask`). Для прикладного кода предпочтительнее высокоуровневый
`IFormItemManagementService`.
