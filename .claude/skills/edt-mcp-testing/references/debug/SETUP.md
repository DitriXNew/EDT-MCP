# Debug — общая подготовка стенда

Чтобы протестировать debug-инструменты, нужен **исполняемый код + триггер выполнения + точка останова**. Пустой `TestConfiguration` сам по себе ничего не выполняет, поэтому брейкпоинт «не на чем» словить.

## Рабочий сценарий (проверено 2026-06-02, e2e)

1. **Вписать исполняемый код в авто-стартовый обработчик** — `ManagedApplicationModule.OnStart()`, который выполняется сам при старте клиента (не нужен ручной триггер).
   - Имя обработчика зависит от варианта встроенного языка: при `scriptVariant=English` это `OnStart` / `Message()`, при русском — `ПриНачалеРаботыСистемы` / `Сообщить()`. **Проверь вариант:** `get_configuration_properties(projectName)` → поле `scriptVariant` (у `TestConfiguration` — `English`).
   ```bsl
   // TEST DEBUG
   Procedure OnStart()
       Greeting = "Debug e2e OK";
       Sum = 40 + 2;
       Total = Sum * 10;        // <- сюда breakpoint (Greeting+Sum уже присвоены, Total ещё Undefined)
       Message(Greeting + " | sum=" + Sum + " | total=" + Total);
   EndProcedure
   ```
   Запись: `write_module_source(projectName="TestConfiguration", modulePath="Configuration/ManagedApplicationModule.bsl", mode="replace", source=<код>)`.
2. `set_breakpoint` на строку `Total = Sum * 10;`.
3. `debug_launch(launchConfigurationName="TestConfiguration Thin Client", updateBeforeLaunch=true)` — на старте клиент выполнит `OnStart` и остановится на брейкпоинте.
4. Ловить и инспектировать: `wait_for_break` → `get_variables` / `step` / `evaluate_expression` → `resume`.
5. Прибрать: `remove_breakpoint`, при необходимости закрыть клиент (`Stop-Process` по 1cv8c, т.к. `terminate_launch` может быть не в снапшоте инструментов хоста).

## Подводные камни (реально словлены)

- **DB-update требует ЭКСКЛЮЗИВНОГО доступа к файловой базе.** Если базу держит запущенный 1С-клиент, `debug_launch(updateBeforeLaunch=true)` виснет на `Connecting to designer agent for infobase <name>` и отваливается по таймауту (видно в `D:\WS\EDT\.metadata\.log`). Лечение — закрыть клиентов (`Stop-Process -Name 1cv8,1cv8c`). **Elevated-процесс из неэлевейтед-шелла не убить** (`Access denied`) — такой 1С закрывает только пользователь.
- **Без `updateBeforeLaunch=true`** клиент стартует на СТАРОЙ конфигурации в базе — новый `OnStart` туда не попал, брейкпоинт не сработает. (Само по себе `write_module_source` меняет только исходник в воркспейсе/на диске, не конфигурацию базы.)
- **MCP-вызов `debug_launch` может вернуть таймаут**, пока сервер-сайд ещё обновляет базу/поднимает клиент — это не обязательно провал: перепроверь `debug_status` спустя время и по `Get-Process 1cv8c`.
- **Breakpoint якорится к строке.** Если модуль сдвинулся (добавили код выше — напр. `BeforeStart`), EDT переякорит брейкпоинт на нужный оператор, но его `breakpointId` может смениться. Перед удалением смотри фактический список: `list_breakpoints`.
- **scriptVariant важен:** обработчик с неправильным для варианта именем 1С не распознает как событие — на старте он не выполнится, и брейкпоинт не словится.
