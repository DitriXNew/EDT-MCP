# invocation-form-event-handler

## 📋 General Information

| Parameter | Value |
|-----------|-------|
| **Check ID** | `invocation-form-event-handler` |
| **Title** | Program invocation of form event handler |
| **Description** | Checks for direct programmatic calls to form event handlers |
| **Severity** | `MAJOR` |
| **Type** | `WARNING` |
| **Complexity** | `NORMAL` |
| **Default State** | Enabled |

---

## 🎯 What This Check Does

This check identifies **direct programmatic calls to form event handlers** from code, which is generally not recommended.

### Why This Is Important

- **Event handler semantics**: Handlers are meant to be called by the platform
- **Unexpected behavior**: Direct calls bypass platform event processing
- **State inconsistency**: Handler may expect certain pre-conditions
- **Maintenance issues**: Makes code flow harder to understand

---

## ❌ Error Example

### Error Message

```
Program invocation of form event handler
```

**Russian:**
```
Программный вызов обработчика события формы
```

### Noncompliant Code Example

```bsl
// Form module
&AtClient
Procedure OnOpen(Cancel)
    InitializeFormData();
EndProcedure

&AtClient
Procedure CustomerOnChange(Item)
    RecalculatePrices();
EndProcedure

&AtClient
Procedure RefreshData()
    // ❌ Direct call to event handler
    OnOpen(False);
    
    // ❌ Calling another event handler directly
    CustomerOnChange(Items.Customer);
EndProcedure
```

---

## ✅ Compliant Solution

### Extract Logic to Separate Procedures

```bsl
// Form module
&AtClient
Procedure OnOpen(Cancel)
    // ✅ Call extracted procedure
    InitializeFormData();
EndProcedure

&AtClient
Procedure CustomerOnChange(Item)
    // ✅ Call extracted procedure
    RecalculatePricesClient();
EndProcedure

&AtClient
Procedure RefreshData()
    // ✅ Call the extracted procedures, not event handlers
    InitializeFormData();
    RecalculatePricesClient();
EndProcedure

#Region Private

&AtClient
Procedure InitializeFormData()
    // Initialization logic here
EndProcedure

&AtClient
Procedure RecalculatePricesClient()
    // Recalculation logic here
EndProcedure

#EndRegion
```

---

## 📖 Understanding the Problem

### Why Not Call Event Handlers Directly

```bsl
// Event handler structure (platform expectation):
&AtClient
Procedure BeforeClose(Cancel, Exit, WarningText, StandardProcessing)
    // Platform provides:
    // - Cancel = False
    // - Exit = True/False (system-controlled)
    // - WarningText = ""
    // - StandardProcessing = True
    
    If Modified Then
        Cancel = True;  // Platform processes this
    EndIf;
EndProcedure

// If called directly:
BeforeClose(False, False, "", True);  // ❌ Problems:
// 1. Platform won't process Cancel flag
// 2. Exit flag doesn't reflect real state
// 3. Form won't actually close/stay open
```

### Platform Event Flow

```
User Action (e.g., clicks X button)
        ↓
Platform prepares parameters
        ↓
Platform calls BeforeClose()
        ↓
Platform processes returned Cancel value
        ↓
Form closes or stays open

Direct call bypasses this flow!
```

---

## 📋 Refactoring Patterns

### Pattern 1: Extract Business Logic

```bsl
// Before - ❌
&AtClient
Procedure OnCreateAtServer(Cancel, StandardProcessing)
    FillDefaultValues();
    SetupFormAppearance();
EndProcedure

&AtClient
Procedure ResetForm()
    OnCreateAtServer(False, True);  // ❌ Wrong!
EndProcedure

// After - ✅
&AtServer
Procedure OnCreateAtServer(Cancel, StandardProcessing)
    InitializeFormState();
EndProcedure

&AtServer
Procedure InitializeFormState()
    FillDefaultValues();
    SetupFormAppearance();
EndProcedure

&AtClient
Procedure ResetForm()
    InitializeFormStateAtServer();  // ✅ Call extracted procedure
EndProcedure
```

### Pattern 2: Shared Handler Logic

```bsl
// Before - ❌
&AtClient
Procedure QuantityOnChange(Item)
    RecalculateRow();
EndProcedure

&AtClient
Procedure PriceOnChange(Item)
    QuantityOnChange(Items.Quantity);  // ❌ Wrong!
EndProcedure

// After - ✅
&AtClient
Procedure QuantityOnChange(Item)
    RecalculateCurrentRow();
EndProcedure

&AtClient
Procedure PriceOnChange(Item)
    RecalculateCurrentRow();  // ✅ Both call shared procedure
EndProcedure

&AtClient
Procedure RecalculateCurrentRow()
    // Shared logic here
EndProcedure
```

---

## 📋 Common Event Handlers

### Handlers That Should Never Be Called Directly

| Handler | Why |
|---------|-----|
| `OnCreateAtServer` | Form lifecycle managed by platform |
| `OnOpen` | Platform controls opening process |
| `BeforeClose` | Platform processes Cancel flag |
| `OnClose` | Platform manages closing |
| `BeforeWrite` | Writing process is complex |
| `AfterWrite` | Post-write state managed |

### Item Event Handlers

| Handler | Why |
|---------|-----|
| `OnChange` | Input validation context |
| `StartChoice` | Selection dialog context |
| `Clearing` | Clear processing |
| `AutoComplete` | Autocomplete context |

---

## 🔧 How to Fix

### Step 1: Identify direct calls

Find calls to event handlers in code:
- `OnOpen(`
- `OnCreateAtServer(`
- `*OnChange(`
- etc.

### Step 2: Extract logic to procedures

Move the business logic from event handler to a separate procedure.

### Step 3: Update callers

Replace direct event handler calls with calls to extracted procedures.

### Step 4: Update event handler

Have event handler call the extracted procedure.

---

## 🔍 Technical Details

### What Is Checked

1. Identifies form event handler methods
2. Scans for direct invocations of these methods in code
3. Reports each direct call

### Check Implementation Class

```
com.e1c.v8codestyle.bsl.check.InvocationFormEventHandlerCheck
```

### Location in v8-code-style

```
bundles/com.e1c.v8codestyle.bsl/src/com/e1c/v8codestyle/bsl/check/
```

