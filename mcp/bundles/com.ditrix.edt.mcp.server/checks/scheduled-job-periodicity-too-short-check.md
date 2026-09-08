# Scheduled job periodicity less than minute

## 📋 General Information

| Parameter | Value |
|----------|----------|
| **Check ID** | `scheduled-job-periodicity-too-short` |
| **Severity** | Major |
| **Type** | Warning |
| **Standard** | № 402 |

## 🎯 What This Check Does

Checks that scheduled job execution periodicity is not less than one minute. Too frequent job execution creates excessive server load.

## ❌ Error Examples

### Example 1 - Interval less than minute

```xml
<ScheduledJob>
  <Name>DataSync</Name>
  <Schedule>
    <RepeatPause>30</RepeatPause>  <!-- ERROR: 30 seconds - too short -->
  </Schedule>
</ScheduledJob>
```

### Example 2 - Повтор каждые 10 секунд

```xml
<ScheduledJob>
  <Name>QueueProcessing</Name>
  <Schedule>
    <RepeatPause>10</RepeatPause>  <!-- ERROR: Too frequent -->
  </Schedule>
</ScheduledJob>
```

## ✅ Compliant Solutions

### Example 1 - Интервал 1 минута

```xml
<ScheduledJob>
  <Name>DataSync</Name>
  <Schedule>
    <RepeatPause>60</RepeatPause>  <!-- OK: 60 seconds (1 minute) -->
  </Schedule>
</ScheduledJob>
```

### Example 2 - Интервал 5 минут

```xml
<ScheduledJob>
  <Name>QueueProcessing</Name>
  <Schedule>
    <RepeatPause>300</RepeatPause>  <!-- OK: 5 minutes -->
  </Schedule>
</ScheduledJob>
```

## 🔧 How to Fix

1. Откройте регламентное задание в Конфигураторе
2. Измените расписание на интервал не менее 1 минуты
3. Оцените реальную потребность в частоте выполнения

### Рекомендации по настройке:
- Для большинства заданий нормальный интервал: 1 день
- Частые задания (для актуальности данных): от 1 минуты
- Ресурсоёмкие задания: переносить на нерабочее время
- Несколько тяжёлых заданий: разносить по времени
- Периодичность должна быть сбалансирована со временем выполнения

### Ни в каких случаях не следует:
- Задавать периодичность меньше одной минуты

## 🔍 Technical Details

- **Category**: Metadata checks
- **Применимость**: Регламентные задания

## 📚 References

- [Standard #402 - Настройка расписания регламентных заданий](https://its.1c.ru/db/v8std#content:402:hdoc)
