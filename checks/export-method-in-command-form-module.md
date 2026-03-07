# export-method-in-command-form-module

## 📋 General Information

| Parameter | Value |
|-----------|-------|
| **Check ID** | `export-method-in-command-form-module` |
| **Title** | Restrictions on the use of export procedures and functions |
| **Description** | Do not embed export procedures and functions in modules of commands and forms |
| **Severity** | `MINOR` |
| **Type** | `WARNING` |
| **Complexity** | `NORMAL` |
| **Default State** | Enabled |
| **1C Standard** | [544](https://its.1c.ru/db/v8std/content/544/hdoc) |

---

## 🎯 What This Check Does

This check identifies **export procedures and functions** in **command modules** and **form modules**, which is generally not recommended.

### Why This Is Important

- **Inaccessibility**: Form and command modules cannot be called from external code
- **Dead code**: Export keyword has no effect in these modules
- **Misleading**: Developers may think these methods can be called externally
- **Standards compliance**: Follows Standard 544

---

## ❌ Error Example

### Error Message

```
Do not embed export procedures and functions in modules of commands and forms. You cannot address such modules from external code, so embedded export procedures and functions become dysfunctional.
```

**Russian:**
```
Не следует размещать экспортные процедуры и функции в модулях команд и форм. Обратиться к таким модулям извне нельзя, поэтому экспортные процедуры и функции в них бессмысленны.
```

### Noncompliant Code Example

**Form Module:**
```bsl
// ❌ Export in form module - cannot be called externally
Function CalculateTotal() Export
    Total = 0;
    For Each Row In Object.Items Do
        Total = Total + Row.Amount;
    EndDo;
    Return Total;
EndFunction

Procedure ProcessData() Export  // ❌ Export is meaningless here
    // ... processing logic
EndProcedure
```

**Command Module:**
```bsl
// ❌ Export in command module
Procedure ExecuteAction() Export
    // ... action logic
EndProcedure
```

---

## ✅ Compliant Solutions

### Option 1: Remove Export Keyword

```bsl
// Form Module
// ✅ Without Export - correct for form-internal methods
Function CalculateTotal()
    Total = 0;
    For Each Row In Object.Items Do
        Total = Total + Row.Amount;
    EndDo;
    Return Total;
EndFunction

Procedure ProcessData()  // ✅ No Export
    // ... processing logic
EndProcedure
```

### Option 2: Move to Common Module

If the method needs to be called from outside:

```bsl
// CommonModule: DocumentCalculations
Function CalculateDocumentTotal(ItemsTable) Export  // ✅ Export in common module
    Total = 0;
    For Each Row In ItemsTable Do
        Total = Total + Row.Amount;
    EndDo;
    Return Total;
EndFunction

// Form Module
Function CalculateTotal()
    // ✅ Call common module method
    Return DocumentCalculations.CalculateDocumentTotal(Object.Items);
EndFunction
```

---

## 📖 Understanding Module Accessibility

### Module Types and Export

| Module Type | Can Be Called Externally | Export Makes Sense |
|-------------|-------------------------|-------------------|
| Common Module | ✓ Yes | ✓ Yes |
| Object Module | ✓ Yes (via GetObject()) | ✓ Yes |
| Manager Module | ✓ Yes | ✓ Yes |
| **Form Module** | ✗ No | ✗ No |
| **Command Module** | ✗ No | ✗ No |
| Session Module | ✗ No | ✗ No |

### Why Forms Can't Be Called

```bsl
// This is NOT possible:
MyForm = Forms.SomeForm.GetModule();  // ❌ No such access
MyForm.CalculateTotal();  // ❌ Cannot call form methods

// Forms are only accessible from within themselves
```

---

## 📋 Exceptions

### Attachable Event Handlers

Methods used with `NotifyDescription` callbacks must be Export:

```bsl
// ✅ Exception: Attachable event handler
&AtClient
Procedure Attachable_ProcessResult(Result, AdditionalParameters) Export
    // This method is called via NotifyDescription
    If Result = DialogReturnCode.Yes Then
        SaveDocument();
    EndIf;
EndProcedure

// Usage:
Handler = New NotifyDescription("Attachable_ProcessResult", ThisObject);
ShowQueryBox(Handler, "Save changes?", QuestionDialogMode.YesNo);
```

### CallbackDescription Methods

```bsl
// ✅ Exception: Callback handler
&AtClient
Procedure OnFileSelectionComplete(SelectedFiles, AdditionalParameters) Export
    If SelectedFiles <> Undefined Then
        ProcessSelectedFiles(SelectedFiles);
    EndIf;
EndProcedure
```

---

## ⚙️ Check Parameters

| Parameter | Default | Description |
|-----------|---------|-------------|
| `checkFormServerMethods` | `true` | Check server methods in forms |
| `checkFormClientMethods` | `false` | Check client methods in forms |
| `excludeModuleMethodNamePattern` | `^(?U)(Подключаемый\|Attachable)_.*$` | Exclude pattern for method names |
| `notifyDescriptionMethods` | `""` | Comma-separated list of excluded callback method names |

---

## 🔧 How to Fix

### Step 1: Identify export methods in forms/commands

Find all `Export` procedures and functions in:
- Form modules
- Command modules

### Step 2: Evaluate each method

Ask:
- Is this method used as a callback handler? → Keep Export
- Is this method called only within the form? → Remove Export
- Should this method be reusable? → Move to common module

### Step 3: Apply appropriate fix

**For internal methods:**
```bsl
// Before
Function DoSomething() Export

// After
Function DoSomething()  // Remove Export
```

**For callback handlers:**
```bsl
// Keep Export, ensure name matches pattern
Procedure Attachable_OnComplete(Result, Parameters) Export
```

**For reusable logic:**
```bsl
// Move to common module
// CommonModule: SharedFunctions
Function SharedLogic(Parameters) Export
```

---

## 🔍 Technical Details

### What Is Checked

1. Finds form modules and command modules
2. Identifies methods with `Export` keyword
3. Checks against exclusion patterns (Attachable_*, etc.)
4. Reports export methods that don't match exceptions

### Check Implementation Class

```
com.e1c.v8codestyle.bsl.check.ExportMethodInCommandFormModuleCheck
```

### Location in v8-code-style

```
bundles/com.e1c.v8codestyle.bsl/src/com/e1c/v8codestyle/bsl/check/
```

---

## 📚 References

- [1C:Enterprise Development Standards - Standard 544](https://its.1c.ru/db/v8std/content/544/hdoc)
