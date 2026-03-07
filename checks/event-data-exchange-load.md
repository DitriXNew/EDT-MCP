# event-data-exchange-load

## 📋 General Information

| Parameter | Value |
|-----------|-------|
| **Check ID** | `data-exchange-load` |
| **Title** | Check DataExchange.Load in event handler |
| **Description** | Mandatory checking of DataExchange.Load is absent in event handler |
| **Severity** | `MAJOR` |
| **Type** | `PORTABILITY` |
| **Complexity** | `NORMAL` |
| **Default State** | Enabled |
| **1C Standard** | [773](https://its.1c.ru/db/v8std/content/773/hdoc) |

---

## 🎯 What This Check Does

This check validates that **event handlers** (`OnWrite`, `BeforeWrite`, `BeforeDelete`) contain a check for `DataExchange.Load` with a `Return` statement.

### Why This Is Important

- **Data exchange compatibility**: Logic should be skipped during data exchange
- **Performance**: Avoids redundant validations during import
- **Data integrity**: Prevents blocking replicated data
- **Synchronization**: Allows distributed infobase to work correctly

---

## ❌ Error Example

### Error Message

```
Mandatory checking of "DataExchange.Load" is absent in event handler "{handlerName}"
```

**Russian:**
```
Обязательная проверка "ОбменДанными.Загрузка" отсутствует в обработчике события "{handlerName}"
```

### Noncompliant Code Example

```bsl
// Object module
Procedure BeforeWrite(Cancel)
    // ❌ Missing DataExchange.Load check
    
    If Not ValueIsFilled(Date) Then
        Cancel = True;
        Message("Date is required!");
    EndIf;
EndProcedure

Procedure OnWrite(Cancel)
    // ❌ Missing DataExchange.Load check
    
    UpdateRelatedDocuments();
EndProcedure
```

---

## ✅ Compliant Solution

### Correct Code Pattern

```bsl
// Object module
Procedure BeforeWrite(Cancel)
    // ✅ DataExchange.Load check at the beginning
    If DataExchange.Load Then
        Return;
    EndIf;
    
    If Not ValueIsFilled(Date) Then
        Cancel = True;
        Message("Date is required!");
    EndIf;
EndProcedure

Procedure OnWrite(Cancel)
    // ✅ DataExchange.Load check at the beginning
    If DataExchange.Load Then
        Return;
    EndIf;
    
    UpdateRelatedDocuments();
EndProcedure
```

---

## 📖 Understanding DataExchange.Load

### What Is DataExchange.Load

The `DataExchange.Load` property indicates that an object is being written as part of a **data exchange** process:
- Distributed infobase synchronization
- Data migration
- XML/JSON data import
- REST API operations

### When DataExchange.Load = True

| Scenario | DataExchange.Load |
|----------|-------------------|
| User saves document | False |
| Background job processes data | False |
| Data exchange imports object | **True** |
| Distributed infobase sync | **True** |
| REST API creates object | **True** |

---

## 📋 Event Handlers That Need Check

### Required in These Handlers

| Handler | Module Type | Must Check |
|---------|-------------|------------|
| `BeforeWrite` | Object, RecordSet | ✓ Yes |
| `OnWrite` | Object, RecordSet | ✓ Yes |
| `BeforeDelete` | Object | ✓ Yes |
| `Posting` | Document | ✓ Yes |
| `UndoPosting` | Document | ✓ Yes |

### Handler Names (EN/RU)

| English | Russian |
|---------|---------|
| `BeforeWrite` | `ПередЗаписью` |
| `OnWrite` | `ПриЗаписи` |
| `BeforeDelete` | `ПередУдалением` |

---

## 📋 Complete Example

### Document Object Module

```bsl
#Region EventHandlers

Procedure BeforeWrite(Cancel, WriteMode, PostingMode)
    
    // ✅ First line: DataExchange.Load check
    If DataExchange.Load Then
        Return;
    EndIf;
    
    // Set auto-calculated fields
    TotalAmount = CalculateTotal();
    
    // Validate document
    If Not ValidateDocument() Then
        Cancel = True;
    EndIf;
    
EndProcedure

Procedure OnWrite(Cancel)
    
    // ✅ First line: DataExchange.Load check
    If DataExchange.Load Then
        Return;
    EndIf;
    
    // Update related records
    InformationRegisters.DocumentIndex.UpdateIndex(Ref);
    
EndProcedure

Procedure Posting(Cancel, PostingMode)
    
    // ✅ First line: DataExchange.Load check
    If DataExchange.Load Then
        Return;
    EndIf;
    
    // Create register records
    RegisterRecords.Inventory.Write = True;
    // ... create movements
    
EndProcedure

Procedure BeforeDelete(Cancel)
    
    // ✅ First line: DataExchange.Load check
    If DataExchange.Load Then
        Return;
    EndIf;
    
    // Check if can delete
    If HasLinkedDocuments() Then
        Cancel = True;
        Message("Cannot delete - has linked documents");
    EndIf;
    
EndProcedure

#EndRegion
```

---

## ⚠️ Advanced Pattern

### When Some Logic Must Run During Exchange

```bsl
Procedure BeforeWrite(Cancel)
    
    // Logic that MUST run even during exchange
    ModificationDate = CurrentSessionDate();
    
    // ✅ Check before business logic
    If DataExchange.Load Then
        Return;
    EndIf;
    
    // Business logic to skip during exchange
    ValidateBusinessRules(Cancel);
    CalculateDerivedValues();
    
EndProcedure
```

---

## 🔧 How to Fix

### Step 1: Find event handlers

In object modules, find:
- `BeforeWrite`
- `OnWrite`
- `BeforeDelete`
- `Posting` (for documents)
- `UndoPosting` (for documents)

### Step 2: Add DataExchange.Load check

Add at the very beginning:

```bsl
If DataExchange.Load Then
    Return;
EndIf;
```

### Step 3: Verify check placement

The check should be:
- First or nearly first statement
- Followed by immediate `Return`

---

## ⚙️ Check Parameters

| Parameter | Default | Description |
|-----------|---------|-------------|
| `checkAtBeginning` | `false` | Require check to be at the very beginning |
| `dataExchangeLoadFunctionList` | `""` | Additional functions that check DataExchange.Load |

### Custom Function List

If you have helper functions:

```bsl
// In your common module
Function ShouldSkipDataExchange() Export
    Return DataExchange.Load;
EndFunction
```

Add to parameter: `CommonModule.ShouldSkipDataExchange`

---

## 🔍 Technical Details

### What Is Checked

1. Finds event handlers by name (BeforeWrite, OnWrite, etc.)
2. Checks for `DataExchange.Load` condition
3. Verifies there's a `Return` statement inside the condition
4. Optionally checks if it's at the beginning

### Check Implementation Class

```
com.e1c.v8codestyle.bsl.check.EventDataExchangeLoadCheck
```

### Location in v8-code-style

```
bundles/com.e1c.v8codestyle.bsl/src/com/e1c/v8codestyle/bsl/check/
```

---

## 📚 References

- [1C:Enterprise Development Standards - Standard 773](https://its.1c.ru/db/v8std/content/773/hdoc)
