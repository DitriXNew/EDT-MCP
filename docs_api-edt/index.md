# Оглавление — API EDT (служебная документация)

Статус папки и правила ведения: см. [README.md](README.md).
Правовой статус и интероперабельность: см. [LEGAL.md](LEGAL.md).

## Формы

1. [Генерация форм](forms/01-form-generation.md) — `IFormGenerator`, `FormType`, `FormFieldInfo`, `IFormFieldGenerator`.
2. [Добавление элементов и реквизитов](forms/02-form-items.md) — `IFormItemManagementService`, `FormNewItemDescriptor`, `FormAttributeManagementService`.
3. [Персистентность формы](forms/03-form-persistence.md) — модель формы, BM-транзакция, `attachTopObject`, последовательность мастера, размещение на диске.

## Инфраструктура

1. [Получение сервисов EDT (DI)](infrastructure/01-services-di.md) — Guice-инжекторы плагинов, экспорт пакетов, импорты в `MANIFEST.MF`.
2. [Паттерн MCP-инструмента](infrastructure/02-mcp-tool-pattern.md) — `IMcpTool`, регистрация, группы, шаблон write-операции через BM-транзакцию.
3. [Совместимость с версиями EDT](infrastructure/03-edt-version-compatibility.md) — дрейф API между релизами, диапазоны версий, рефлективные фасады, чек-лист при апгрейде.

## Связанные артефакты в репозитории

- Бандл MCP-сервера: `mcp/bundles/com.ditrix.edt.mcp.server/`
- Пример создания объекта метаданных: `mcp/bundles/com.ditrix.edt.mcp.server/src/com/ditrix/edt/mcp/server/tools/impl/CreateMetadataObjectTool.java`
- Пример добавления реквизита: `mcp/bundles/com.ditrix.edt.mcp.server/src/com/ditrix/edt/mcp/server/tools/impl/AddMetadataAttributeTool.java`
- Манифест бандла: `mcp/bundles/com.ditrix.edt.mcp.server/META-INF/MANIFEST.MF`
