# Персистентность формы

Как сгенерированная форма становится сохранённой формой проекта.

## Две сущности формы

| Сущность | Класс | Что это | Файл на диске |
|---|---|---|---|
| MD-объект формы | `com._1c.g5.v8.dt.metadata.mdclass.BasicForm` (`Form` / `CommonForm`) | элемент коллекции `forms` владельца | `<Owner>/Forms/<Name>.mdo` |
| Содержимое формы | `com._1c.g5.v8.dt.form.model.Form` | дерево элементов + реквизиты | `<Owner>/Forms/<Name>/Form.form` |
| Модуль формы | `com._1c.g5.v8.dt.bsl.model.Module` | модуль формы | `<Owner>/Forms/<Name>/.../Module.bsl` |

Обе модельные сущности (`BasicForm` и `model.Form`) регистрируются как **top-объекты BM**
через `IBmTransaction.attachTopObject(...)`, после чего EDT сам пишет соответствующие файлы.

Пример layout на диске (из проекта, `Catalog`):

```
Catalogs/Catalog/Forms/ItemForm/Form.form
```

## Последовательность мастера (разбор `FormNewWizardRelatedModelsFactory`)

Класс `com._1c.g5.v8.dt.form.ui.wizard.FormNewWizardRelatedModelsFactory` (плагин
`com._1c.g5.v8.dt.form.ui_24.x`) — единственное место вызова `generateForm`. По анализу
байткода последовательность внутри активной BM-транзакции (`IDtNewWizardContext.getActiveTransaction()`):

1. `Form formModel = IFormGenerator.generateForm(owner, basicForm, formType, scriptVariant, name, version, fields, columnCount, mode);`
2. `module.setOwner(formModel);` — привязка модуля формы (`com._1c.g5.v8.dt.bsl.model.Module`).
3. `tx.attachTopObject((IBmObject) <object>, <fqn>);` — регистрация top-объекта.
4. `tx.toTransactionObject(<eobject>);` — перевод объекта в контекст транзакции.
5. `context.put(<object>);` — складывание объектов в контекст мастера.

Параллельно `FormWizardModel.applyChanges(IBmTransaction, IProgressMonitor)` настраивает
свойства MD-объекта формы (`BasicForm`):

- `setUseInInterfaceCompatibilityMode(FormPreferableInterface)`;
- запись ссылок на форму в владельца (`IBmObject.eSet(feature, form)` по списку из
  `getFormReferencesList()` — это назначение формы основной: формой элемента/списка/выбора и т.п.);
- для `CommonForm`: `getExplanation()`, `getExtendedPresentation()`, `setUseStandardCommands(...)`.

## Рекомендуемый порядок для собственного инструмента

Воспроизвести логику мастера в одной BM-write-транзакции (тот же паттерн, что в
`AddMetadataAttributeTool` / `CreateMetadataObjectTool`):

```text
bmModel.execute(AbstractBmTask):
  owner       = (MdObject) tx.getObjectById(ownerBmId)
  basicForm   = factory.create(<Form|CommonForm eClass>, version)   // MD-объект формы
  basicForm.setName(name); // + synonym
  owner.getForms().add(basicForm)
  tx.attachTopObject((IBmObject) basicForm, "<OwnerType>.<OwnerName>.Form.<Name>")
  formModel   = formGenerator.generateForm(owner, basicForm, type, sv, name, version, fields, columnCount, mode)
  module.setOwner(formModel)
  tx.attachTopObject((IBmObject) formModel, <fqn содержимого формы>)
  // при setAsDefault: проставить ссылку формы в соответствующем feature владельца
```

> Замечания / непроверенные детали:
> - Точные FQN для двух `attachTopObject` (особенно для `model.Form` и модуля) нужно
>   сверять с мастером при реализации — критично для корректной записи `.form`/`.mdo`.
> - `scriptVariant` берётся из `Configuration.getScriptVariant()`, `version` — из
>   `IV8Project.getVersion()` (уже используется в `CreateMetadataObjectTool`).
> - После записи желательно `revalidate_objects` / проверить `get_project_errors`.

## Проверка валидности «тип формы ↔ тип владельца»

- `RECORD_SET` — только регистры;
- `RECORD` — независимый регистр сведений;
- `FOLDER` / `FOLDER_CHOICE` — только иерархические справочники;
- `OBJECT` / `LIST` / `CHOICE` — справочники, документы и т.п.;
- `CommonForm` — без владельца (отдельный режим инструмента).
