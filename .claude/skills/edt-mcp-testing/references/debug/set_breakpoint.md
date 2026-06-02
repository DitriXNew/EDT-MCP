# set_breakpoint — как тестировать

**Назначение.** Поставить line-breakpoint на BSL-модуль (по EDT-пути от `src/` или абсолютному пути).

**Предусловие.** Живой EDT + проект открыт. Останавливаемый код для полного цикла — см. [SETUP](SETUP.md); но сам резолв брейкпоинта проверяется и без запуска.

**Вызов (реально, 2026-06-02):**
```
set_breakpoint(projectName="TestConfiguration",
               module="Configuration/ManagedApplicationModule.bsl",
               lineNumber=5)
```

**Ожидаемый результат:**
```json
{"breakpointId":1863,"success":true,
 "module":"Configuration/ManagedApplicationModule.bsl","lineNumber":5,
 "resolvedFile":"/TestConfiguration/src/Configuration/ManagedApplicationModule.bsl"}
```
Ключевая проверка — `resolvedFile` указывает в `src/<module>` (резолвер source-folder, A27). Также проверено на `module="CommonModules/Error/Module.bsl", lineNumber=1` → `resolvedFile:"/TestConfiguration/src/CommonModules/Error/Module.bsl"`.

**Подводные камни.**
- `module` принимает и EDT-путь от `src/`, и абсолютный путь; `projectName` обязателен для модуль-относительного.
- Файл резолвится через общий `BslModuleUtils.resolveModuleFile` (сначала `src/`, потом fallback по другим top-level папкам) — см. A27.
- Брейкпоинт можно ставить, даже если строка не исполнится; «сработает» он только когда по ней реально пойдёт выполнение (нужен триггер — см. SETUP).
