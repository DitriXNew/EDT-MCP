# debug_status — как тестировать

**Назначение.** Показать активные debug-launch'и: applicationId, конфиг/тип, режим (debug/run), suspended-флаг, число потоков, строку верхнего suspended-фрейма.

**Предусловие.** Нет (вернёт `count:0`, если ничего не запущено — тоже валидный кейс).

**Вызов (реально, 2026-06-02):**
```
debug_status()
```

**Ожидаемый результат — нет сессий:**
```json
{"registry":{"liveFrames":0,"liveThreads":0,"activeApplications":0},"success":true,"count":0,"launches":[]}
```
**Останов на breakpoint (после debug_launch + OnStart):**
```json
{"registry":{"liveFrames":0,"liveThreads":1,"activeApplications":1},"success":true,"count":1,
 "launches":[{"applicationId":"attach:1C Enterprise debug process","mode":"debug","debug":true,
   "launchConfiguration":"1C Enterprise debug process",
   "configurationType":"com._1c.g5.v8.dt.debug.core.LocalRuntime","attach":true,
   "project":"TestConfiguration","threadCount":2,"suspended":true,
   "suspendedAt":"ManagedApplicationModule.OnStart() line: 9 @ 9","registered":true}]}
```

**Подводные камни.**
- `debug_status` перечисляет launch'и напрямую из менеджера — показывает и RUN, и debug (поле `mode`/`debug`). Это отличается от **auto-resolve** (`findLoneActiveApplicationId`), который после A13 берёт только DEBUG-режим.
- `suspendedAt` — удобный признак, что брейк сработал; дальше бери фрейм через `wait_for_break`.
- Запущенный runtime-client клиент отображается как attach-launch `1C Enterprise debug process` (LocalRuntime) с синтетическим `applicationId: "attach:<configName>"`.
