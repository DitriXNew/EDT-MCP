# list_configurations — как тестировать

**Назначение.** Перечислить launch-конфигурации EDT (runtime-client + Attach + прочие 1С-типы) с их состоянием `running`/`suspended`.

**Предусловие.** Открыт проект. Запущенная сессия не нужна (но если есть — увидишь `running:true`).

**Вызов (реально, 2026-06-02):**
```
list_configurations(projectName="TestConfiguration")
```

**Ожидаемый результат:**
```json
{"success":true,"count":1,"configurations":[
  {"name":"TestConfiguration Thin Client",
   "type":"com._1c.g5.v8.dt.launching.core.RuntimeClient",
   "attach":false,"project":"TestConfiguration","running":false}]}
```

**Подводные камни.**
- Имя из поля `name` — это то, что передаётся как `launchConfigurationName` в `debug_launch`/`run_yaxunit_tests`/`debug_yaxunit_tests`.
- `type='attach'` — серверная отладка (HTTP-сервисы, фоновые задания); `type='client'` — клиент; `type='all'` (дефолт).
- После `debug_launch` запущенного runtime-client EDT может показать привязанный **attach-launch** `1C Enterprise debug process` (LocalRuntime) — это нормальное состояние отладки клиента.
