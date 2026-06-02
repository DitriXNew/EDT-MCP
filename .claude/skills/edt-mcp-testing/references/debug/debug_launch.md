# debug_launch — как тестировать

**Назначение.** Запустить EDT debug-сессию: по `launchConfigurationName` (любой конфиг, вкл. Attach) или по `projectName+applicationId` (runtime-client).

**Предусловие.** Поднят сценарий из [SETUP](SETUP.md) (код в `OnStart` + breakpoint), база доступна эксклюзивно для апдейта.

**Вызов (реально, 2026-06-02):**
```
debug_launch(launchConfigurationName="TestConfiguration Thin Client", updateBeforeLaunch=true)
```

**Ожидаемый результат (без обновления, быстрый путь):**
```json
{"success":true,"mode":"debug","project":"TestConfiguration","attach":false,
 "configurationType":"com._1c.g5.v8.dt.launching.core.RuntimeClient",
 "launchConfiguration":"TestConfiguration Thin Client",
 "message":"Debug session started successfully"}
```
С `updateBeforeLaunch=true` сначала обновляется база (designer agent), затем стартует клиент, и `OnStart` ловит breakpoint.

**Подводные камни.**
- **`updateBeforeLaunch=true` виснет, если базу держит другой клиент** → лог `Connecting to designer agent for infobase ...` + таймаут. Закрой клиентов (`Stop-Process 1cv8/1cv8c`); elevated — только пользователь. Без апдейта новый код в базу не попадёт.
- **Таймаут MCP-вызова ≠ провал**: сервер-сайд может ещё доделывать апдейт/старт. Перепроверь `debug_status` и `Get-Process 1cv8c`.
- **Legacy-путь `projectName+applicationId`** ищет конфиг по паре project+app; если runtime-client конфиг не привязан к этому appId → `success:false` + `availableConfigurations` («No launch configuration found»). Надёжнее звать по `launchConfigurationName`.
- **A12-фикс:** если приложение уже запущено (в т.ч. RUN-режиме без debug-target) — инструмент вернёт `alreadyRunning:true` с `mode`, а не поднимет второй клиент. Чтобы пересоздать сессию — сперва `terminate_launch`.
- **Flaky-канал:** видел голое `Error` — реальную причину смотри в `D:\WS\EDT\.metadata\.log` (там полный `structuredContent`).
