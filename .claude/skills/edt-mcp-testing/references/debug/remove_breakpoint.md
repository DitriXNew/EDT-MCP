# remove_breakpoint — как тестировать

**Назначение.** Снять брейкпоинт — по `breakpointId` или по координатам `projectName+module+lineNumber`.

**Предусловие.** Есть брейкпоинт; актуальный id взять из `list_breakpoints`.

**Вызов (реально, 2026-06-02):**
```
remove_breakpoint(breakpointId=1867)
```

**Ожидаемый результат:**
```json
{"removed":true,"success":true}
```

**Подводные камни.**
- Удаление **несуществующего/устаревшего id** не ошибка, а `{"removed":false,"success":true}` — в прогоне `remove_breakpoint(1863)` (старый id со стр.5) вернул `removed:false`, потому что брейк уже жил под id 1867. **Сначала `list_breakpoints`, потом удаляй по фактическому id.**
- Альтернатива — удаление по координатам (`projectName`+`module`+`lineNumber`), полезно когда id неизвестен.
