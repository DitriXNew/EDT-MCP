# terminate_launch — как тестировать

**Назначение.** Завершить запущенную debug/run-сессию (закрыть клиент/отвязать attach). Используется, чтобы пересоздать сессию после `alreadyRunning`.

**Предусловие.** Активный launch (`debug_status` показывает `count>=1`).

**Ожидаемый вызов:**
```
terminate_launch(applicationId="attach:1C Enterprise debug process")
```
(или по `launchConfigurationName` — сверь сигнатуру актуальной схемой инструмента).

**Подводные камни.**
- **Не было в снапшоте инструментов хоста** в сессии 2026-06-02 (хотя инструмент есть в коде, commit `a3a14e9`) — если `ToolSearch`/прямой вызов недоступен, **fallback на убийство процесса**: `Stop-Process -Name 1cv8c -Force` (клиент), что приводит к завершению привязанного attach-launch. Elevated-процесс из неэлевейтед-шелла не убить (`Access denied`).
- После `terminate_launch`/kill проверь `debug_status` → `count:0` и `Get-Process 1cv8c` → пусто.
- Перед прогоном допиши/сверь этот референс реальным выводом, когда инструмент будет доступен в хосте.
