# export-variable-in-object-module

## 📋 General Information

| Parameter | Value |
|-----------|-------|
| **Check ID** | `export-variable-in-object-module` |
| **Title** | Use of an export variable is not recommended |
| **Description** | It's not recommended to use the export variable in the object module |
| **Severity** | `MAJOR` |
| **Type** | `WARNING` |
| **Complexity** | `NORMAL` |
| **Default State** | Enabled |
| **1C Standard** | [439](https://its.1c.ru/db/v8std/content/439/hdoc) |

---

## 🎯 What This Check Does

This check identifies **export variables** declared in **object modules**, which is generally not recommended.

### Why This Is Important

- **Data integrity**: Export variables can be modified from anywhere
- **Encapsulation**: Breaks object encapsulation principles
- **Debugging difficulty**: Hard to track variable changes
- **Upgrade safety**: May conflict with platform updates
- **Standards compliance**: Follows Standard 439

---

## ❌ Error Example

### Error Message

```
It's not recommended to use the export variable in the object module
```

**Russian:**
```
Не рекомендуется использовать экспортные переменные в модуле объекта
```

### Noncompliant Code Example

```bsl
// Document.Invoice object module
#Region Variables

Var ProcessingMode Export;  // ❌ Export variable in object module
Var AdditionalData Export;  // ❌ Export variable

#EndRegion

Procedure BeforeWrite(Cancel)
    If ProcessingMode = "Auto" Then
        // ... logic based on variable
    EndIf;
EndProcedure
```

---

## ✅ Compliant Solutions

### Option 1: Use Object Attribute

```bsl
// In metadata: Add attribute "ProcessingMode" to the document
// Type: EnumRef.ProcessingModes

// Object module - ✅ Using standard attribute
Procedure BeforeWrite(Cancel)
    If ProcessingMode = Enums.ProcessingModes.Auto Then
        // ... logic based on attribute
    EndIf;
EndProcedure
```

### Option 2: Use AdditionalProperties

```bsl
// Object module - ✅ Using AdditionalProperties
Procedure BeforeWrite(Cancel)
    If AdditionalProperties.Property("ProcessingMode") Then
        ProcessingMode = AdditionalProperties.ProcessingMode;
        If ProcessingMode = "Auto" Then
            // ... logic
        EndIf;
    EndIf;
EndProcedure

// Calling code
DocumentObject = DocumentRef.GetObject();
DocumentObject.AdditionalProperties.Insert("ProcessingMode", "Auto");
DocumentObject.Write();
```

### Option 3: Use Parameters in Methods

```bsl
// Object module - ✅ Use method parameters
Procedure ProcessWithMode(Mode) Export
    If Mode = "Auto" Then
        // ... logic
    EndIf;
EndProcedure

// Calling code
DocumentObject = DocumentRef.GetObject();
DocumentObject.ProcessWithMode("Auto");
```

---

## 📖 Understanding the Problem

### Why Export Variables Are Problematic

```bsl
// With export variable:
Var InternalFlag Export;  // ❌

// Problems:
// 1. Anyone can read it
Value = DocumentObject.InternalFlag;

// 2. Anyone can write it
DocumentObject.InternalFlag = True;

// 3. No control over when/how it's modified
// 4. No validation possible
// 5. Hard to debug when value changes unexpectedly
```

### Proper Encapsulation

```bsl
// Using AdditionalProperties:
// 1. Explicit passing of data
DocumentObject.AdditionalProperties.Insert("Flag", True);

// 2. Explicit checking
If AdditionalProperties.Property("Flag", Value) Then
    // Use Value
EndIf;

// 3. Clear data flow
// 4. Easy to track in code
```

---

## 📋 Alternative Approaches

### AdditionalProperties Pattern

```bsl
// Calling code (form, processor, etc.)
DocumentObject = Documents.Invoice.CreateDocument();
DocumentObject.Date = CurrentSessionDate();
DocumentObject.AdditionalProperties.Insert("SkipValidation", True);
DocumentObject.AdditionalProperties.Insert("ImportMode", True);
DocumentObject.Write();

// Object module
Procedure BeforeWrite(Cancel)
    // Check for flags
    SkipValidation = False;
    AdditionalProperties.Property("SkipValidation", SkipValidation);
    
    If Not SkipValidation Then
        ValidateDocument(Cancel);
    EndIf;
EndProcedure
```

### Method Parameters Pattern

```bsl
// Add export method with parameter
Procedure WriteWithMode(WriteMode, PostingMode, ProcessingFlags) Export
    // Store flags for use in event handlers
    AdditionalProperties.Insert("ProcessingFlags", ProcessingFlags);
    
    Write(WriteMode, PostingMode);
EndProcedure
```

### Attribute Pattern

For persistent values, use metadata attributes:

```bsl
// Metadata:
// Attribute: ProcessingStatus (EnumRef.ProcessingStatuses)

// Usage - ✅ Standard approach
DocumentObject.ProcessingStatus = Enums.ProcessingStatuses.InProgress;
```

---

## 🔧 How to Fix

### Step 1: Identify export variables

Find declarations like:
```bsl
Var VariableName Export;
```

### Step 2: Choose replacement strategy

| Current Usage | Recommended Replacement |
|--------------|------------------------|
| Passing flags to event handlers | AdditionalProperties |
| Persistent object state | Object attributes |
| Method parameters | Export method with params |
| Temporary calculation data | Local variables or method results |

### Step 3: Refactor the code

**Before:**
```bsl
Var SkipCheck Export;

Procedure BeforeWrite(Cancel)
    If Not SkipCheck Then
        // ...
    EndIf;
EndProcedure
```

**After:**
```bsl
Procedure BeforeWrite(Cancel)
    SkipCheck = False;
    AdditionalProperties.Property("SkipCheck", SkipCheck);
    
    If Not SkipCheck Then
        // ...
    EndIf;
EndProcedure
```

### Step 4: Update calling code

**Before:**
```bsl
DocObject = DocRef.GetObject();
DocObject.SkipCheck = True;
DocObject.Write();
```

**After:**
```bsl
DocObject = DocRef.GetObject();
DocObject.AdditionalProperties.Insert("SkipCheck", True);
DocObject.Write();
```

---

## 📋 Applies To

This check applies to:

| Module Type | Checked |
|-------------|---------|
| Object Module | ✓ Yes |
| Manager Module | ✗ No |
| Form Module | ✗ No |
| Common Module | ✗ No |

---

## 🔍 Technical Details

### What Is Checked

1. Finds variable declarations in object modules
2. Checks for `Export` keyword
3. Reports export variables

### Check Implementation Class

```
com.e1c.v8codestyle.bsl.check.ExportVariableInObjectModuleCheck
```

### Location in v8-code-style

```
bundles/com.e1c.v8codestyle.bsl/src/com/e1c/v8codestyle/bsl/check/
```

---

## 📚 References

- [1C:Enterprise Development Standards - Standard 439](https://its.1c.ru/db/v8std/content/439/hdoc)
