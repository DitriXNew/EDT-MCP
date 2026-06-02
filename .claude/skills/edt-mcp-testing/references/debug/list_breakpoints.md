# list_breakpoints — как тестировать

**Назначение.** Перечислить активные line-breakpoint'ы (опц. фильтр по `projectName`).

**Предусловие.** Хотя бы один `set_breakpoint` поставлен.

**Вызов (реально, 2026-06-02):**
```
list_breakpoints(projectName="TestConfiguration")
```

**Ожидаемый результат:**
```json
{"success":true,"count":1,"breakpoints":[
  {"breakpointId":1867,"project":"TestConfiguration",
   "file":"/TestConfiguration/src/Configuration/ManagedApplicationModule.bsl",
   "lineNumber":9,"enabled":true,"modelId":"com._1c.g5.v8.dt.debug"}]}
```

**Подводные камни.**
- **`breakpointId` может отличаться от того, что вернул `set_breakpoint`**, если модуль сдвинулся (EDT переякорил брейкпоинт). Поэтому перед `remove_breakpoint` бери актуальный id именно отсюда (в прогоне: ставили на стр.5 → id 1863, после добавления `BeforeStart` выше реальный брейк оказался на стр.9 → id 1867).
- `modelId":"com._1c.g5.v8.dt.debug"` подтверждает, что это BSL-брейкпоинт, а не generic-маркер.
