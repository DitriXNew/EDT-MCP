# Each event should have its own handler

## 📋 General Information

| Parameter | Value |
|----------|----------|
| **Check ID** | `form-items-single-event-handler` |
| **Severity** | Minor |
| **Type** | Code style |
| **Standard** | № 455 |

## 🎯 What This Check Does

Checks that each form event has its own separate handler procedure assigned. One handler should not be used for multiple events or elements.

## ❌ Error Examples

### Example 1 - One handler for multiple events

```bsl
// Form designer: Both OnChange and OnActivate point to same handler

&AtClient
Procedure FieldHandler(Item)  // ← ERROR: Same handler for multiple events
    // Complex logic handling both events
    If SomeCondition Then
        // OnChange logic
    Else
        // OnActivate logic
    EndIf;
EndProcedure
```

### Example 2 - Shared handler for different elements

```bsl
// Multiple form items use ProductOnChange handler

&AtClient
Procedure ProductOnChange(Item)  // ← ERROR: Shared between items
    // ...
EndProcedure
```

## ✅ Compliant Solutions

### Example 1 - Separate handlers

```bsl
&AtClient
Procedure FieldOnChange(Item)
    ProcessFieldChange();
EndProcedure

&AtClient
Procedure FieldOnActivate(Item)
    ProcessFieldActivation();
EndProcedure

&AtClient
Procedure ProcessFieldChange()
    // Shared logic extracted to separate procedure
EndProcedure

&AtClient
Procedure ProcessFieldActivation()
    // Activation logic
EndProcedure
```

### Example 2 - Individual handlers

```bsl
&AtClient
Procedure Product1OnChange(Item)
    ProcessProductChange(Item);
EndProcedure

&AtClient
Procedure Product2OnChange(Item)
    ProcessProductChange(Item);
EndProcedure

&AtClient
Procedure ProcessProductChange(Item)
    // Common logic in shared procedure
EndProcedure
```

## 🔧 How to Fix

1. Create a separate handler with default name for each form element
2. Extract common logic into a separate procedure/function
3. Call the common procedure from each handler

### Pattern:
```
ElementEvent -> ElementEventHandler -> CommonProcedure
```

### Почему это важно:
- Смешение событий усложняет логику
- Снижает устойчивость кода
- Код должен рассчитывать только на один тип вызова

## 🔍 Technical Details

- **Category**: Form checks
- **Применимость**: Обработчики событий элементов форм

## 📚 References

- [Standard #455 - Структура модуля](https://its.1c.ru/db/v8std/content/455/hdoc#2.4.3)
