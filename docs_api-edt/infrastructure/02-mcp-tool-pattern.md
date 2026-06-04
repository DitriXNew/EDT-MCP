# Паттерн MCP-инструмента

Как устроен и регистрируется инструмент в бандле `com.ditrix.edt.mcp.server`.

## Интерфейс `IMcpTool`

`com.ditrix.edt.mcp.server.tools.IMcpTool`

```java
public interface IMcpTool {
    enum ResponseType { TEXT, JSON, MARKDOWN, IMAGE }

    String getName();                       // уникальное имя, напр. "create_metadata_object"
    String getDescription();                // описание для tools/list
    String getInputSchema();                // JSON Schema входных параметров
    String execute(Map<String, String> params);

    default ResponseType getResponseType() { return ResponseType.MARKDOWN; }
    default String getResultFileName(Map<String, String> params) { return getName() + ".md"; }
}
```

Хелперы:
- `com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder` — построение input schema
  (`.object().stringProperty(name, desc[, required]).booleanProperty(...).build()`);
- `com.ditrix.edt.mcp.server.protocol.JsonUtils.extractStringArgument(params, name)`;
- `com.ditrix.edt.mcp.server.protocol.ToolResult` — `success().put(k, v).toJson()` / `error(msg).toJson()`.

## Регистрация

1. В `McpServer.registerTools()` добавить `registry.register(new MyTool());`.
2. В `preferences/ToolGroup.java` добавить имя инструмента в подходящую группу
   (создание/редактирование метаданных — группа `REFACTORING`).

Существующие группы: `CORE`, `PROBLEMS`, `CODE_INTELLIGENCE`, `TAGS`, `APPLICATIONS`,
`DEBUG`, `BSL_CODE`, `REFACTORING`, `TRANSLATION`.

## Шаблон write-операции через BM-транзакцию

Образец — `CreateMetadataObjectTool` / `AddMetadataAttributeTool`. Ключевые моменты:

1. Выполнять в UI-потоке через `Display.syncExec` с `AtomicReference<String>` для результата.
2. Получить проект (`ResourcesPlugin.getWorkspace().getRoot().getProject(name)`),
   конфигурацию (`IConfigurationProvider.getConfiguration(project)`),
   модель BM (`IBmModelManager.getModel(project)`).
3. Запомнить `bmGetId()` нужных объектов **до** транзакции и заново получить их внутри
   через `tx.getObjectById(id)`.
4. Изменения — в `bmModel.execute(new AbstractBmTask<Void>(...) { ... })`.
5. Новые top-объекты — через `tx.attachTopObject((IBmObject) obj, fqn)`; добавление в
   коллекцию владельца — `((EList) collection).add(obj)`.

```java
AtomicReference<String> resultRef = new AtomicReference<>();
Display display = PlatformUI.getWorkbench().getDisplay();
display.syncExec(() -> {
    try { resultRef.set(executeInternal(...)); }
    catch (Exception e) {
        Activator.logError("Error in my_tool", e);
        resultRef.set(ToolResult.error(e.getMessage()).toJson());
    }
});
return resultRef.get();
```

```java
bmModel.execute(new AbstractBmTask<Void>("MyTask") {
    @Override public Void execute(IBmTransaction tx, IProgressMonitor pm) {
        MdObject owner = (MdObject) tx.getObjectById(ownerBmId);
        MdObject obj = (MdObject) factory.create(eClass, version);
        obj.setName(name);
        tx.attachTopObject((IBmObject) obj, fqn);
        ((EList<MdObject>) collection).add(obj);
        factory.fillDefaultReferences(obj);
        return null;
    }
});
```

## Полезные утилиты проекта

- `com.ditrix.edt.mcp.server.utils.MetadataTypeUtils` — `toEnglishSingular`,
  `getConfigReferenceName`, `normalizeFqn`, `findObject(config, type, name)`
  (поддержка русских имён типов);
- `Activator.getModelObjectFactory()` — фабрика MD-объектов с дефолтным наполнением
  (как в мастерах «Новый …»);
- `Activator.getV8ProjectManager().getProject(project).getVersion()` — версия платформы.

## Применение к формам

Для `generate_form` / `add_form_item` использовать тот же шаблон, но:
- сервисы форм брать из Guice-инжектора плагина форм (см.
  [01-services-di.md](01-services-di.md));
- объект транзакции для добавления элементов — модель формы
  `com._1c.g5.v8.dt.form.model.Form` (см. [../forms/03-form-persistence.md](../forms/03-form-persistence.md)).
