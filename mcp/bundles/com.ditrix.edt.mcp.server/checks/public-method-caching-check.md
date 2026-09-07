# Public API caching check

## 📋 General Information

| Parameter | Value |
|----------|----------|
| **Check ID** | `public-method-caching` |
| **Severity** | Major |
| **Type** | Warning |
| **Standard** | № 644 |

## 🎯 What This Check Does

Checks that modules with return values reuse do not create public API ("ProgramInterface" section). Such modules should contain only internal service API.

## ❌ Error Examples

### Example - Public API in cached module

```bsl
// Common module with ReturnValuesReuse = AtSession

#Region Public  // ← ERROR: Public API in cached module

Procedure GetData() Export
EndProcedure

#EndRegion
```

### In Russian:

```bsl
// Common module with return values reuse

#Область ПрограммныйИнтерфейс  // ← ERROR

Процедура ПолучитьДанные() Экспорт
КонецПроцедуры

#КонецОбласти
```

## ✅ Compliant Solutions

### Example - Internal API

```bsl
// Common module with ReturnValuesReuse = AtSession

#Region Internal

Procedure GetData() Export
EndProcedure

#EndRegion
```

### In Russian:

```bsl
// Common module with return values reuse

#Область СлужебныйПрограммныйИнтерфейс

Процедура ПолучитьДанные() Экспорт
КонецПроцедуры

#КонецОбласти
```

## 🔧 How to Fix

1. Rename region `ПрограммныйИнтерфейс` to `СлужебныйПрограммныйИнтерфейс`
2. Or rename `Public` to `Internal`
3. For public API, create a separate non-cached wrapper module

### Reason for restriction:
- Modules with caching are intended for internal use
- Public API should be stable and not depend on implementation details
- Caching is an implementation detail, not part of the API contract

## 🔍 Technical Details

- **Java class**: `PublicMethodCachingCheck`
- **Location**: `com.e1c.v8codestyle.bsl.check`
- **Applicability**: Common modules with return values reuse

## 📚 References

- [Standard #644 - Library Compatibility Assurance](https://its.1c.ru/db/v8std/content/644/hdoc)
