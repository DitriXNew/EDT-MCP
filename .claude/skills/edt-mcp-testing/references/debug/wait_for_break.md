# wait_for_break — как тестировать

**Назначение.** Дождаться SUSPEND (срабатывания breakpoint) и вернуть снимок потока/фрейма (`threadId`, `frameRef`). На таймауте — `{hit:false}`, launch НЕ завершается.

**Предусловие.** Запущена debug-сессия с активным breakpoint (см. [SETUP](SETUP.md)). `applicationId` можно не передавать — если активна ровно одна debug-сессия, она резолвится авто (A13).

**Вызов (реально, 2026-06-02):**
```
wait_for_break(timeout=5)
```

**Ожидаемый результат (брейк сработал):**
```json
{"success":true,"hit":true,"threadId":2,"topFrameRef":3,"autoResolved":true,
 "applicationId":"attach:1C Enterprise debug process","threadName":"Thin client",
 "frames":[{"frameIndex":0,"frameRef":3,"name":"ManagedApplicationModule.OnStart() line: 9","line":9}]}
```
`autoResolved:true` подтверждает A13: авто-резолв взял именно **debug**-launch. Запомни `threadId` (для `step`/`resume`) и `frameRef` (для `get_variables`/`evaluate_expression`).

**Подводные камни.**
- На таймауте `{"hit":false,"reason":"timeout"}` — это НЕ ошибка; зови повторно, чтобы продолжить ждать. Launch не убивается.
- Если активны несколько debug-launch'ей — авто-резолв вернёт неоднозначность; передавай `applicationId` явно.
- После A13 авто-резолв игнорирует RUN-launch'и (они не дают suspend) — поэтому одиночная RUN-сессия больше не «перехватывает» авто-резолв.
