# step — как тестировать

**Назначение.** Шагнуть приостановленным потоком: `kind ∈ {over, into, out}`. Блокирует до следующего SUSPEND и возвращает новый снимок фрейма.

**Предусловие.** Поток в suspend; `threadId` из `wait_for_break`.

**Вызов (реально, 2026-06-02):**
```
step(threadId=2, kind="over")
```
**Результат:**
```json
{"success":true,"hit":true,"threadId":4,"topFrameRef":5,"threadName":"Thin client",
 "applicationId":"attach:1C Enterprise debug process",
 "frames":[{"frameIndex":0,"frameRef":5,"name":"ManagedApplicationModule.OnStart() line: 10","line":10}]}
```
Проверка: шагнули со стр.9 на стр.10; после этого `get_variables(frameRef=5)` показывает `Total=420` (строка 9 выполнилась).

**Подводные камни.**
- В ответе **новый `threadId`/`frameRef`** (тут 2→4, 3→5) — используй их для последующих `get_variables`/`step`/`resume`, старые могут протухнуть.
- `over` — не заходя в вызовы; `into` — внутрь; `out` — до выхода из текущей процедуры.
- На таймаут (если шаг улетел в долгое выполнение) вернётся без нового suspend — действуй как с `wait_for_break`.
