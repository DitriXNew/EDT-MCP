# Attachable event handler name

## 📋 General Information

| Parameter | Value |
|----------|----------|
| **Check ID** | `module-attachable-event-handler-name` |
| **Severity** | Minor |
| **Type** | Code style |
| **Standard** | № 492 |

## 🎯 What This Check Does

Checks that name of programmatically added event handler follows pattern: prefix **Attachable_** (or **Подключаемый_**).

## ❌ Error Examples

### Example 1 - Wrong handler name

```bsl
// Parameters:
//  Item - FormField
//
Procedure SetupField(Item)
    
    Item.SetAction("OnChange", "ItemOnChange");  // ← ERROR: Missing prefix
    
EndProcedure
```

### Example 2 - No prefix

```bsl
Procedure ConfigureTable(Table)
    
    Table.SetAction("Selection", "ProcessSelection");  // ← ERROR: Should be Attachable_
    
EndProcedure
```

## ✅ Compliant Solutions

### Example 1 - Correct prefix

```bsl
// Parameters:
//  Item - FormField
//
Procedure SetupField(Item)
    
    Item.SetAction("OnChange", "Attachable_ItemOnChange");  // OK
    
EndProcedure
```

### Example 2 - Russian prefix

```bsl
Procedure НастроитьПоле(Элемент)
    
    Элемент.УстановитьДействие("ПриИзменении", "Подключаемый_ЭлементПриИзменении");  // OK
    
EndProcedure
```

## 🔧 How to Fix

1. Rename handler to include prefix:
   - English: `Attachable_HandlerName`
   - Russian: `Подключаемый_ИмяОбработчика`

2. Create handler procedure with matching name

### Naming pattern:
```
Attachable_<ElementName><EventName>
Подключаемый_<ИмяЭлемента><ИмяСобытия>
```

## 🔍 Technical Details

- **Java class**: `AttachableEventHandlerNameCheck`
- **Location**: `com.e1c.v8codestyle.bsl.check`

## 📚 References

- [Standard #492 - Обработчики событий модуля формы, подключаемые из кода](https://its.1c.ru/db/v8std#content:492:hdoc)
