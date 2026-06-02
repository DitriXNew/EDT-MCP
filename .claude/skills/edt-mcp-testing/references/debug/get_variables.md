# get_variables — как тестировать

**Назначение.** Прочитать переменные стек-фрейма приостановленного потока. Передавай `frameRef` (из `wait_for_break`, предпочтительно) или `threadId`+`frameIndex`; `expandPath` — раскрыть вложенное.

**Предусловие.** Поток в suspend; `frameRef` из `wait_for_break`.

**Вызов (реально, 2026-06-02) — на breakpoint'е (стр.9, до выполнения):**
```
get_variables(frameRef=3)
```
**Результат:**
```json
{"success":true,"count":3,"variables":[
 {"name":"Greeting","type":"String","value":"\"Debug e2e OK\"","hasChildren":false},
 {"name":"Sum","type":"Number","value":"42","hasChildren":false},
 {"name":"Total","type":"Undefined","value":"Undefined","hasChildren":false}]}
```
**После `step over` (frameRef сменился на 5, строка 9 выполнилась):**
```json
{"variables":[ ...,{"name":"Total","type":"Number","value":"420","hasChildren":false}],"count":3}
```

**Подводные камни.**
- **Семантика останова — ДО выполнения строки** брейка: на стр.9 `Total` ещё `Undefined`; значение появляется только после `step`. Это и есть проверка корректности (видно изменение `Undefined → 420`).
- `frameRef` валиден для конкретного снимка; после `step`/`resume` бери НОВЫЙ `frameRef`/`topFrameRef` из ответа `step`/`wait_for_break`.
- Для структур/коллекций `hasChildren:true` — раскрывай `expandPath` (dot-путь).
