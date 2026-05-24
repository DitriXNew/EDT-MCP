# BSL code style and quality

> Adapted from proven rules used in real-world 1C projects. Not tied to any specific configuration.

## Comments

- Prefer **self-documenting code** over comments. Do not comment what the code already expresses.
- A comment is appropriate when it adds: motivation, a description of a non-trivial algorithm, constraints/side effects, or a technical-debt marker.
- Do not write tracker task numbers in BSL comments — they belong in the commit/PR; in code they go stale quickly.

## Quality and checks

- After any edit, perform an internal code review (style, readability, correctness, edge cases, security, concurrency). If a problem is found — fix it and repeat until the code is clean.
- Before writing new code — check whether a common/manager module already has an exported implementation.
- When writing a query — verify attribute and tabular section names against the metadata.

## Prohibitions

- Do not use `Сообщить()`. Use `ОбщегоНазначения.СообщитьПользователю` (server) or `ОбщегоНазначенияКлиент.СообщитьПользователю` (client).
- Do not use deprecated global methods (`ПодробноеПредставлениеОшибки()` without a prefix). Use `ОбработкаОшибок.ПодробноеПредставлениеОшибки()`.
- Do not use the letter `ё`/`Ё` (U+0451/U+0401) in `.bsl` files. Use `е`/`Е`. In user documentation — acceptable.
- Do not use the em dash `—` (U+2014) in `.bsl`. Use `-` (U+002D).
- Do not access attribute values through dot notation on references (`Контрагент.ИНН`) — this loads the entire object. Use `ОбщегоНазначения.ЗначениеРеквизитаОбъекта(Контрагент, "ИНН")`.
- Do not run queries inside loops. Use batch queries and temporary tables.
- Do not compare a Boolean value with `Истина`/`Ложь`. Write `Если Активен Тогда`, not `Если Активен = Истина Тогда`.
- Do not use global context names as variable names (`Документы`, `Справочники`, `Метаданные`, `Константы`).
- Do not name procedures/functions/variables after built-in functions (`Выполнить`, `Тип`, `Строка`, `Дата`, `Формат`, `ОписаниеОшибки`).
- Do not use `Попытка ... Исключение` for database read/write operations without justification.
- Do not add `ЗаписьЖурналаРегистрации()` to ordinary code without an explicit request. **Exception** — inside an `Исключение` block of a transaction (see the "Transactions" section, where journal logging is part of the canonical pattern).
- Do not use Hungarian notation (`МассивКонтрагентов`). Write `Контрагенты`.
- Do not abbreviate object and attribute names to acronyms (`АВР`). Use full names: `АктВыполненныхРабот`.

## 1C queries

- The query text — on a new line after `Запрос.Текст =`, at the same indentation level:
  ```bsl
  Запрос = Новый Запрос;
  Запрос.Текст =
      "ВЫБРАТЬ
      |   Контрагенты.ИНН КАК ИНН
      |ИЗ
      |   Справочник.Контрагенты КАК Контрагенты";
  ```
- Always use parameters (`Запрос.УстановитьПараметр()`); do not substitute values by string concatenation.
- Always use aliases via `КАК`: `Контрагенты.ИНН КАК ИНН`.
- Use `ПЕРВЫЕ N` when a limited number of records is needed.
- Use an intermediate variable for the result: `РезультатЗапроса = Запрос.Выполнить()`. Do not chain `.Выполнить().Выгрузить()`.
- Before inserting a new query into code — `validate_query`.

## Transactions — canonical pattern

```bsl
НачатьТранзакцию();
Попытка
    // Блокировка (если нужна) - Чтение - Запись
    ЗафиксироватьТранзакцию();
Исключение
    ОтменитьТранзакцию();
    ЗаписьЖурналаРегистрации(НСтр("ru = 'Событие'"),
        УровеньЖурналаРегистрации.Ошибка, , ,
        ПодробноеПредставлениеОшибки(ИнформацияОбОшибке()));
    ВызватьИсключение;
КонецПопытки;
```

Rules:
- `НачатьТранзакцию()` immediately before `Попытка`. No code between them.
- `ЗафиксироватьТранзакцию()` — the last statement before `Исключение`.
- `ОтменитьТранзакцию()` — the first statement in `Исключение`.
- `ЗаписьЖурналаРегистрации` — **after** `ОтменитьТранзакцию()` (otherwise it will be lost on rollback).
- Always `ВызватьИсключение` after logging — rethrow upward.
- Inside a transaction — no HTTP calls, heavy queries, or user dialogs.

## Locks

- `БлокировкаДанных` — before reading data in a transaction, to avoid a race condition (read-lock-modify-write).
- `ЗаблокироватьДанныеДляРедактирования(Ссылка)` — when programmatically modifying an object (protection against lost update).
- Lock ordering — uniform across the project (protection against deadlocks).

## Exception rethrow

- `ВызватьИсключение;` without a parameter — preserves the original stack trace. Use in intermediate code.
- `ВызватьИсключение "Текст";` — a new exception; the stack is lost. Use at the boundary with the user.
- `КраткоеПредставлениеОшибки()` — for user-facing messages; `ОбработкаОшибок.ПодробноеПредставлениеОшибки()` — for the log.

## String performance

In loops — use `Массив` + `СтрСоединить()` instead of `+`:

```bsl
ЧастиСтроки = Новый Массив;
Для Каждого Элемент Из Коллекция Цикл
    ЧастиСтроки.Добавить(Элемент.Наименование);
КонецЦикла;
Результат = СтрСоединить(ЧастиСтроки, ", ");
```

For large HTML/CSS templates — `ПотокВПамяти` + `ЗаписьТекста.ЗаписатьСтроку()`.

## Localization

- Do not build user-facing strings by concatenation. Use `СтрШаблон` + `НСтр`, store the result in a variable:
  ```bsl
  // НЕПРАВИЛЬНО
  Результат = НовыйРезультат(Истина, "Записано для " + Количество + " пользователей");

  // ПРАВИЛЬНО
  ТекстРезультата = СтрШаблон(НСтр("ru = 'Записано для %1 пользователей'"), Количество);
  Результат = НовыйРезультат(Истина, ТекстРезультата);
  ```

## Dates

- `ТекущаяДатаСеанса()` instead of `ТекущаяДата()` — correct handling of time zones.

## Forms

- Prefer `&НаСервереБезКонтекста` — does not serialize the entire form context (for large tables this can be megabytes).
- Group server calls: one `&НаСервереБезКонтекста` returning a `Структура`, instead of several separate calls.
- After `РеквизитФормыВЗначение("Объект")` — **always** call `ЗначениеВРеквизитФормы(ОбъектДокумент, "Объект")`, otherwise changes are lost.
- Visibility/availability changes — in one server procedure `УправлениеВидимостью()`.
- Use asynchronous dialogs (`ПоказатьВопрос` + `ОписаниеОповещения`) instead of modal ones (`Вопрос()`) — modal dialogs do not work in the web client.
- `Оповестить()` / `ОбработкаОповещения()` — for communication between forms after writing.

## Module region purposes

- `ПрограммныйИнтерфейс` — public methods.
- `СлужебныйПрограммныйИнтерфейс` — private methods for internal calls.
- `СлужебныеПроцедурыИФункции` — internal implementation.

## Procedure and module size

- Procedures/functions — up to 200 lines. Above 100 — consider decomposition.
- When adding new code to a common module — if it already has ~3000 lines, create a new focused module.
- Do not refactor existing large modules without an explicit user request.

## Documentation

- Document public procedures/functions: purpose, parameters, return value.

## BSL language specifics

- `Неопределено` vs `Null`. `Неопределено` — absence of a value at the variable level (default for an undeclared parameter, an uninitialised variable, a missing structure key in `СвойствоСтруктуры`). `Null` is a separate type that comes **only** from the DBMS for an absent field value (LEFT JOIN, ISNULL, etc.). Do not mix them: `Структура.Свойство("X") = Неопределено`, but `Выборка.Сумма = Null` for an empty JOIN. Check using the form that you got: `Если Значение = Неопределено Тогда` or `Если Значение = Null Тогда` (or `ЗначениеЗаполнено(Значение)`).
- `ЗначениеЗаполнено(Значение)` — the unified "value is filled" check (not an empty string, not an empty reference, not `Неопределено`, not `Null`, not a zero date). Prefer it over manual `Если Значение <> "" И Значение <> Неопределено`.
- `Тип("СправочникСсылка.Контрагенты")` vs `ТипЗнч(Значение)`. The former returns the type object by its name; the latter returns the type of a value. Comparison: `ТипЗнч(Ссылка) = Тип("СправочникСсылка.Контрагенты")`.
- `Новый Структура("А, Б", ЗначА, ЗначБ)` — short structure initialisation. Key names without spaces, comma-separated in a single string.
- All user-facing string literals — via `НСтр("ru = '...'; en = '...'")`. Never concatenate fragments of a user message with `+`; see the "Localization" section.

## Privileged mode

- `УстановитьПривилегированныйРежим(Истина)` — only when necessary. Afterwards — always `УстановитьПривилегированныйРежим(Ложь)`.
