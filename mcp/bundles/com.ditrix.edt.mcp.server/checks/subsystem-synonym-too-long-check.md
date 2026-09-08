# Subsystem name length exceeds 35 characters

## 📋 General Information

| Parameter | Value |
|----------|----------|
| **Check ID** | `subsystem-synonym-too-long` |
| **Severity** | Minor |
| **Type** | Code style |
| **Standard** | № 712 |

## 🎯 What This Check Does

Checks that total length of section name (subsystem synonym) does not exceed 35 characters including spaces. This allows placing the name in 2 lines of sections panel.

## ❌ Error Examples

### Example 1 - Too long synonym

```xml
<Subsystem>
  <Name>DocumentManagementAndProcessing</Name>
  <Synonym>Управление документооборотом и обработка документов</Synonym>  <!-- ERROR: 49 chars > 35 -->
</Subsystem>
```

### Example 2 - Длинное название раздела

```xml
<Subsystem>
  <Name>EnterpriseResourcePlanning</Name>
  <Synonym>Планирование ресурсов предприятия и управление производством</Synonym>  <!-- ERROR -->
</Subsystem>
```

## ✅ Compliant Solutions

### Example 1 - Короткий синоним

```xml
<Subsystem>
  <Name>DocumentManagement</Name>
  <Synonym>Документооборот</Synonym>  <!-- OK: 14 chars -->
</Subsystem>
```

### Example 2 - Сокращённое название

```xml
<Subsystem>
  <Name>Production</Name>
  <Synonym>Производство</Synonym>  <!-- OK: 12 chars -->
</Subsystem>
```

### Example 3 - Двухстрочное название

```xml
<Subsystem>
  <Name>HRManagement</Name>
  <Synonym>Управление персоналом</Synonym>  <!-- OK: 20 chars - fits in 2 lines -->
</Subsystem>
```

## 🔧 How to Fix

1. Откройте подсистему в Конфигураторе
2. Сократите синоним до 35 символов или менее
3. Используйте краткие формулировки

### Рекомендации:
- Максимум 35 символов с пробелами
- Позволяет разместить название в 2 строки
- При превышении появится многоточие
- Выбирайте названия примерно одного размера по ширине

## 🔍 Technical Details

- **Category**: Metadata checks
- **Применимость**: Подсистемы верхнего уровня (разделы)

## 📚 References

- [Standard #712 - Панель разделов, пункт 2.1](https://its.1c.ru/db/v8std#content:712:hdoc)
