# Method or variable accessible AtClient

## 📋 General Information

| Parameter | Value |
|----------|----------|
| **Check ID** | `module-accessibility-at-client` |
| **Severity** | Major |
| **Type** | Warning |
| **Standard** | № 680 |

## 🎯 What This Check Does

Checks that method or variable of manager or object module are not accessible at client. Such modules should work only on server.

## ❌ Error Examples

```bsl
// Object module without preprocessor protection

Var ModuleVariable;  // ERROR: Accessible at client

Procedure BeforeDelete(Cancel)
    // ERROR: Method accessible at client
EndProcedure

Procedure DoSomething() Export
    // ERROR: Export method accessible at client
EndProcedure

ModuleVariable = Undefined;
```

## ✅ Compliant Solutions

```bsl
#If Server Or ThickClientOrdinaryApplication Or ExternalConnection Then

Var ModuleVariable;

Procedure BeforeDelete(Cancel)
    // OK: Protected by preprocessor
EndProcedure

Procedure DoSomething() Export
    // OK: Only available on server
EndProcedure

ModuleVariable = Undefined;

#Else
    Raise NStr("en = 'Invalid client call of object.'");
#EndIf
```

## 🔧 How to Fix

1. Wrap entire module code in preprocessor directive:
   ```bsl
   #If Server Or ThickClientOrdinaryApplication Or ExternalConnection Then
   // ... module code ...
   #Else
       Raise NStr("en = 'Invalid client call.'");
   #EndIf
   ```

2. Or use `&AtServer` compiler directive for specific methods

### Применимость:
- Модули объектов
- Модули менеджеров
- Модули наборов записей

## 🔍 Technical Details

- **Java class**: `AccessibilityAtClientInObjectModuleCheck`
- **Location**: `com.e1c.v8codestyle.bsl.check`

## 📚 References

- [Standard #680 - Поддержка толстого клиента](https://its.1c.ru/db/v8std#content:680:hdoc:2)
- [Standard #746 - Обработчики представления](https://its.1c.ru/db/v8std#content:746:hdoc)
