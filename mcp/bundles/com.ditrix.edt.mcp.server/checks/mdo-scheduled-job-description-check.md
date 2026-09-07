# Predefined scheduled job name

## 📋 General Information

| Parameter | Value |
|----------|----------|
| **Check ID** | `mdo-scheduled-job-description` |
| **Severity** | Minor |
| **Type** | Code style |
| **Standard** | № 767 |

## 🎯 What This Check Does

Checks that predefined scheduled jobs do not have description set in configurator. Instead of description, it's sufficient to set synonym.

## ❌ Error Examples

### Example 1 - Description is set

```xml
<ScheduledJob>
  <Name>DataSynchronization</Name>
  <Description>Синхронизация данных</Description>  <!-- ERROR: Description should be empty -->
  <Synonym>Синхронизация данных</Synonym>
  <Predefined>true</Predefined>
</ScheduledJob>
```

## ✅ Compliant Solutions

### Example 1 - Without description, only synonym

```xml
<ScheduledJob>
  <Name>DataSynchronization</Name>
  <Description></Description>  <!-- Empty or omitted -->
  <Synonym>Синхронизация данных</Synonym>
  <Predefined>true</Predefined>
</ScheduledJob>
```

## 🔧 How to Fix

1. Open the scheduled job in Configurator
2. Clear the "Description" field
3. Ensure the "Synonym" is filled

### Localization requirements:
- Description: empty
- Synonym: filled in configuration language
- Applies to: predefined scheduled jobs

## 🔍 Technical Details

- **Category**: Metadata checks
- **Applicability**: Predefined scheduled jobs

## 📚 References

- [Standard #767 - Scheduled Jobs: Localization Requirements](https://its.1c.ru/db/v8std#content:767:hdoc:1)
