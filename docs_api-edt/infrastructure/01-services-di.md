# Получение сервисов EDT (DI)

Как из бандла `com.ditrix.edt.mcp.server` получить сервисы платформы EDT.

## Два способа

### 1. OSGi `ServiceTracker` (для платформенных сервисов)

Используется в `Activator` для сервисов, зарегистрированных как OSGi-сервисы:
`IV8ProjectManager`, `IConfigurationProvider`, `IBmModelManager`, `IMarkerManager`,
`IApplicationManager`, `IMdRefactoringService` и т.д.

```java
v8ProjectManagerTracker = new ServiceTracker<>(context, IV8ProjectManager.class, null);
v8ProjectManagerTracker.open();
// ...
return v8ProjectManagerTracker.getService();
```

### 2. Guice-инжектор плагина (для сервисов, биндящихся в RuntimeModule)

Часть сервисов EDT доступна **только** через Guice-инжектор соответствующего плагина,
а не через OSGi. Образец в проекте — `Activator.getModelObjectFactory()`:

```java
MdPlugin mdPlugin = MdPlugin.getDefault();
Injector injector = mdPlugin.getInjector();
return injector.getInstance(IModelObjectFactory.class);
```

> Важно (из комментария в `Activator`): `IModelObjectFactory` контрибьютится несколькими
> языковыми плагинами; обычный OSGi-lookup вернёт произвольную (неподходящую) реализацию.
> Поэтому фабрику берут строго из инжектора MD-плагина. Аналогичная осторожность нужна
> для любых сервисов, биндящихся per-language.

## Сервисы форм (`IFormGenerator` и др.)

`IFormGenerator` и `IFormFieldGenerator` **биндятся в Guice** модуля
`com._1c.g5.v8.dt.form.FormRuntimeModule`:

```java
public Class<? extends IFormGenerator>      bindIFormGenerator();
public Class<? extends IFormFieldGenerator> bindIFormFieldsGenerator();
```

Инжектор плагина форм: `com._1c.g5.v8.dt.internal.form.FormPlugin`:

```java
public static FormPlugin getDefault();
public com.google.inject.Injector getInjector();
```

Соответственно получение генератора (по образцу `getModelObjectFactory`):

```java
Injector injector = FormPlugin.getDefault().getInjector();
IFormGenerator gen = injector.getInstance(IFormGenerator.class);
IFormFieldGenerator fieldGen = injector.getInstance(IFormFieldGenerator.class);
// аналогично: IFormItemManagementService, IFormItemNamingService, ...
```

> `FormPlugin` лежит во **внутреннем** пакете `com._1c.g5.v8.dt.internal.form`. Его нужно
> добавить в `Import-Package` (см. ниже). Внутренние пакеты EDT, как правило, остаются
> резолвимыми для импортирующих бандлов.

## Экспорт пакетов (проверено по MANIFEST плагина форм)

`com._1c.g5.v8.dt.form_31.1.4`:

- `com._1c.g5.v8.dt.form.generator;version="4.0.0"` — **экспортируется** (содержит
  `IFormGenerator`, `FormType`, `FormFieldInfo`, `IFormFieldGenerator`).
- `com._1c.g5.v8.dt.form.naming`, `com._1c.g5.v8.dt.form.service.naming`,
  `com._1c.g5.v8.dt.form.service.util` — экспортируются.
- Реализации (`com._1c.g5.v8.dt.internal.form.generator.*`: `FormGenerator`,
  `FormGeneratorCore`, `ListFormContainGenerator`, `ItemFormContainGenerator`, ...) —
  внутренние, напрямую не используются.

## Что добавить в `MANIFEST.MF` бандла MCP-сервера

Файл: `mcp/bundles/com.ditrix.edt.mcp.server/META-INF/MANIFEST.MF`.
Уже импортируются `com._1c.g5.v8.dt.bsl.model`, `com._1c.g5.v8.dt.form.layout.service`,
`com._1c.g5.v8.dt.form.ui.editor`, `com._1c.g5.v8.dt.metadata.mdclass`.

Для работы с генерацией/редактированием форм добавить в `Import-Package`:

- `com._1c.g5.v8.dt.form.generator` — генератор форм + `FormType`/`FormFieldInfo`;
- `com._1c.g5.v8.dt.form.model` — модель формы (`Form`, `FormField`, `FormGroup`, ...);
- `com._1c.g5.v8.dt.form.service.item`, `com._1c.g5.v8.dt.form.service.attribute`,
  `com._1c.g5.v8.dt.form.service.naming` — сервисы редактирования формы;
- `com._1c.g5.v8.dt.internal.form` — доступ к `FormPlugin.getInjector()`.

> Конкретные версии/диапазоны в `Import-Package` уточнять по факту сборки (как сделано
> для `com._1c.g5.v8.dt.core.platform;version="[12.0.0,13.0.0)"`).
