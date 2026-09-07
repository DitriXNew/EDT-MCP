# form-data-to-value-check

## 📋 General Information

| Parameter | Value |
|-----------|-------|
| **Check ID** | `form-data-to-value-check` |
| **Title** | FormDataToValue in form module |
| **Description** | Checks for usage of FormDataToValue function in form module code |
| **Severity** | `MAJOR` |
| **Type** | `CODE_SMELL` |
| **Complexity** | `NORMAL` |
| **Default State** | Enabled |

---

## 🎯 What This Check Does

This check identifies improper usage of the **`FormDataToValue()`** function in form module code. This function should be used carefully as it creates a copy of data, consuming memory and potentially causing data synchronization issues.

### Why This Is Important

- **Memory consumption**: Creates additional data copies
- **Data synchronization**: Changes to converted data not reflected in form
- **Performance**: Unnecessary data conversion
- **Architecture**: Better patterns exist for most use cases

---

## ❌ Error Example

### Error Message

```
Avoid using FormDataToValue in form module. Consider using form attributes or server calls directly.
```

### Noncompliant Code Example

```bsl
// ❌ Unnecessary conversion in form module
&AtServer
Procedure ProcessDataAtServer()
    // ❌ Converting form data to object unnecessarily
    DocumentObject = FormDataToValue(Object, Type("DocumentObject.Order"));
    
    // Processing
    DocumentObject.Amount = CalculateTotal();
    
    // ❌ Must convert back - error prone!
    ValueToFormData(DocumentObject, Object);
EndProcedure

// ❌ Converting just to access properties
&AtServer
Procedure CheckStatusAtServer()
    // ❌ Unnecessary conversion for read-only access
    DocumentObject = FormDataToValue(Object, Type("DocumentObject.Order"));
    
    If DocumentObject.Posted Then
        // ...
    EndIf;
EndProcedure

// ❌ Converting tabular section unnecessarily
&AtServer
Procedure ProcessItemsAtServer()
    // ❌ Can work with form data directly
    DocumentObject = FormDataToValue(Object, Type("DocumentObject.Order"));
    
    For Each Row In DocumentObject.Items Do
        Row.Amount = Row.Quantity * Row.Price;
    EndDo;
    
    ValueToFormData(DocumentObject, Object);
EndProcedure
```

---

## ✅ Compliant Solution

### Work with Form Data Directly

```bsl
// ✅ Work with form attributes directly
&AtServer
Procedure ProcessDataAtServer()
    // ✅ Access form attributes directly
    Object.Amount = CalculateTotal();
EndProcedure

// ✅ Access properties directly
&AtServer
Procedure CheckStatusAtServer()
    // ✅ Form data has same properties
    If Object.Posted Then
        // ...
    EndIf;
EndProcedure

// ✅ Work with tabular sections directly
&AtServer
Procedure ProcessItemsAtServer()
    // ✅ Iterate form data directly
    For Each Row In Object.Items Do
        Row.Amount = Row.Quantity * Row.Price;
    EndDo;
EndProcedure
```

### When Conversion Is Necessary

```bsl
// ✅ Conversion needed for object methods
&AtServer
Procedure WriteDocumentAtServer()
    // ✅ Needed for Write() method
    DocumentObject = FormDataToValue(Object, Type("DocumentObject.Order"));
    
    Try
        DocumentObject.Write();
        Modified = False;
        
        // ✅ Sync back after write
        ValueToFormData(DocumentObject, Object);
    Except
        Raise;
    EndTry;
EndProcedure

// ✅ Conversion needed for posting
&AtServer
Procedure PostDocumentAtServer()
    // ✅ Needed for Post() method
    DocumentObject = FormDataToValue(Object, Type("DocumentObject.Order"));
    
    DocumentObject.Write(DocumentWriteMode.Posting);
    
    ValueToFormData(DocumentObject, Object);
EndProcedure
```

---

## 📖 Understanding FormDataToValue

### What It Does

```
Form Data (FormDataStructure)  →  FormDataToValue  →  Business Object
       ↑                                                      ↓
       └────────────────  ValueToFormData  ←──────────────────┘
```

### When Conversion Is Required

| Operation | Needs Conversion |
|-----------|------------------|
| Read property values | ❌ No |
| Modify property values | ❌ No |
| Iterate tabular sections | ❌ No |
| Call object methods (Write, Post) | ✅ Yes |
| Pass to common modules | Depends |
| Use metadata methods | ✅ Yes |

---

## 📋 Proper Usage Patterns

### Pattern 1: Read-Only Access

```bsl
// ✅ No conversion needed for reading
&AtServer
Function GetDocumentInfoAtServer()
    Result = New Structure;
    Result.Insert("Number", Object.Number);
    Result.Insert("Date", Object.Date);
    Result.Insert("Amount", Object.Amount);
    Result.Insert("ItemCount", Object.Items.Count());
    Return Result;
EndFunction
```

### Pattern 2: Modifying Data

```bsl
// ✅ No conversion needed for modification
&AtServer
Procedure RecalculateAtServer()
    TotalAmount = 0;
    
    For Each Row In Object.Items Do
        Row.Amount = Row.Quantity * Row.Price;
        TotalAmount = TotalAmount + Row.Amount;
    EndDo;
    
    Object.TotalAmount = TotalAmount;
EndProcedure
```

### Pattern 3: Writing Object

```bsl
// ✅ Conversion needed only for Write
&AtServer
Procedure SaveAtServer()
    // ✅ Required for Write() call
    DocumentObject = FormDataToValue(Object, Type("DocumentObject.Order"));
    
    If Not CheckFilling() Then
        Return;
    EndIf;
    
    DocumentObject.Write();
    
    // ✅ Always sync back
    ValueToFormData(DocumentObject, Object);
    Modified = False;
EndProcedure
```

### Pattern 4: Using in Common Module

```bsl
// ✅ Pass form data directly if module supports it
&AtServer
Procedure FillDefaultsAtServer()
    // ✅ Common module accepts form data
    OrdersServer.FillDefaults(Object);
EndProcedure

// Common module code
Procedure FillDefaults(FormData) Export
    // Works with FormDataStructure
    FormData.Date = CurrentSessionDate();
    FormData.Author = Users.CurrentUser();
EndProcedure
```

---

## 📋 Memory and Performance Impact

### Memory Usage

```bsl
// ❌ Bad: Creates full copy in memory
DocumentObject = FormDataToValue(Object, Type("DocumentObject.Order"));
// Now 2 copies exist: form data + object

// ✅ Good: Works with existing data
For Each Row In Object.Items Do
    // Uses existing form data
EndDo;
```

### Performance Comparison

| Approach | Memory | Speed |
|----------|--------|-------|
| Direct form data access | Low | Fast |
| FormDataToValue | 2x | Slower |
| Multiple conversions | 3x+ | Much slower |

---

## 🔧 How to Fix

### Step 1: Identify why conversion is used

Check what operations are performed after conversion.

### Step 2: Determine if conversion is necessary

Only needed for object methods (Write, Post, etc.).

### Step 3: Remove unnecessary conversions

Work with form data directly.

### Step 4: Keep conversion only when required

For Write, Post, and similar operations.

---

## 📋 Refactoring Examples

### Example 1: Reading Properties

```bsl
// ❌ Before
&AtServer
Function IsPostedAtServer()
    Doc = FormDataToValue(Object, Type("DocumentObject.Order"));
    Return Doc.Posted;
EndFunction

// ✅ After
&AtServer
Function IsPostedAtServer()
    Return Object.Posted;
EndFunction
```

### Example 2: Modifying Tabular Section

```bsl
// ❌ Before
&AtServer
Procedure ClearItemsAtServer()
    Doc = FormDataToValue(Object, Type("DocumentObject.Order"));
    Doc.Items.Clear();
    ValueToFormData(Doc, Object);
EndProcedure

// ✅ After
&AtServer
Procedure ClearItemsAtServer()
    Object.Items.Clear();
EndProcedure
```

### Example 3: Calculation

```bsl
// ❌ Before
&AtServer
Procedure CalculateTotalsAtServer()
    Doc = FormDataToValue(Object, Type("DocumentObject.Order"));
    Doc.Amount = 0;
    For Each Row In Doc.Items Do
        Doc.Amount = Doc.Amount + Row.Total;
    EndDo;
    ValueToFormData(Doc, Object);
EndProcedure

// ✅ After
&AtServer
Procedure CalculateTotalsAtServer()
    Object.Amount = 0;
    For Each Row In Object.Items Do
        Object.Amount = Object.Amount + Row.Total;
    EndDo;
EndProcedure
```

---

## 🔍 Technical Details

### What Is Checked

1. FormDataToValue calls in form modules
2. Usage pattern analysis
3. Necessity of conversion

### Check Implementation Class

```
com.e1c.v8codestyle.bsl.check.FormDataToValueCheck
```

### Location in v8-code-style

```
bundles/com.e1c.v8codestyle.bsl/src/com/e1c/v8codestyle/bsl/check/
```

