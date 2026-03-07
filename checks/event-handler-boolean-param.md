# event-handler-boolean-param

## 📋 General Information

| Parameter | Value |
|-----------|-------|
| **Check ID** | `event-handler-boolean-param` |
| **Title** | Use event handler boolean parameter |
| **Description** | Checks that boolean parameter of event handler is used correctly to set boolean value |
| **Severity** | `MAJOR` |
| **Type** | `WARNING` |
| **Complexity** | `NORMAL` |
| **Default State** | Enabled |

---

## 🎯 What This Check Does

This check validates that **boolean parameters** in event handlers are assigned correctly:
- `Cancel` should only be set to `True`
- `StandardProcessing` should only be set to `False`
- `Perform` should only be set to `False`

### Why This Is Important

- **Correct behavior**: Setting wrong values can break expected logic
- **Event chain**: Multiple handlers may set these parameters
- **Data integrity**: Incorrect Cancel handling can cause data issues
- **Standards compliance**: Follows 1C event handling conventions

---

## ❌ Error Examples

### Error Message for Cancel

```
Parameter "Cancel" should set to True, but expression may replace existing value to False
```

### Error Message for StandardProcessing

```
Parameter "StandardProcessing" should set to False, but expression may replace existing value to True
```

### Noncompliant Code Examples

#### Wrong: Setting Cancel to False

```bsl
Procedure BeforeWrite(Cancel)
    If SomeCondition Then
        Cancel = True;
    Else
        Cancel = False;  // ❌ Wrong! Never set Cancel to False
    EndIf;
EndProcedure
```

#### Wrong: Setting StandardProcessing to True

```bsl
Procedure OnOpen(Cancel, StandardProcessing)
    If UseCustomOpen Then
        StandardProcessing = False;
    Else
        StandardProcessing = True;  // ❌ Wrong! Never set to True
    EndIf;
EndProcedure
```

---

## ✅ Compliant Solutions

### Correct Cancel Usage

```bsl
Procedure BeforeWrite(Cancel)
    // ✅ Only set Cancel to True when needed
    If Not ValidateDocument() Then
        Cancel = True;
    EndIf;
    // Don't set Cancel = False - it's already False by default
EndProcedure
```

### Correct StandardProcessing Usage

```bsl
Procedure OnOpen(Cancel, StandardProcessing)
    // ✅ Only set StandardProcessing to False when needed
    If UseCustomOpenLogic Then
        StandardProcessing = False;
        OpenCustomForm();
    EndIf;
    // Don't set StandardProcessing = True - it's already True by default
EndProcedure
```

### Correct Perform Usage

```bsl
Procedure FillingCheck(Cancel, AttributesToCheck)
    // ✅ Cancel starts as False, only set to True
    If Not ValueIsFilled(Customer) Then
        Cancel = True;
        Message("Customer is required");
    EndIf;
EndProcedure
```

---

## 📖 Boolean Parameter Semantics

### Understanding Event Parameters

| Parameter | Initial Value | Meaning when True | Meaning when False |
|-----------|---------------|-------------------|---------------------|
| `Cancel` | False | Stop operation | Continue operation |
| `StandardProcessing` | True | Use default logic | Skip default logic |
| `Perform` | True | Execute action | Skip action |

### Why Not Reset to Default?

Multiple event handlers may run in sequence. If one handler sets `Cancel = True` and another sets `Cancel = False`, the cancellation is incorrectly overridden:

```bsl
// First handler
Procedure BeforeWrite(Cancel)
    If ImportantValidationFails Then
        Cancel = True;  // Operation should be canceled
    EndIf;
EndProcedure

// Second handler (wrong!)
Procedure BeforeWrite(Cancel)
    If MyCondition Then
        Cancel = True;
    Else
        Cancel = False;  // ❌ This overrides first handler's Cancel!
    EndIf;
EndProcedure
```

---

## 📋 Event Handlers with Boolean Parameters

### Common Events

| Event | Parameters |
|-------|------------|
| `BeforeWrite` | Cancel |
| `OnWrite` | Cancel |
| `BeforeDelete` | Cancel |
| `OnOpen` | Cancel, StandardProcessing |
| `BeforeOpen` | Cancel, StandardProcessing |
| `BeforeClose` | Cancel, StandardProcessing |
| `BeforeAddRow` | Cancel |
| `FillingCheck` | Cancel |

### Form Events

| Event | Parameters |
|-------|------------|
| `OnCreateAtServer` | Cancel, StandardProcessing |
| `OnOpen` | Cancel |
| `BeforeClose` | Cancel, StandardProcessing |
| `OnClose` | StandardProcessing |

---

## 📋 Advanced Patterns

### Multiple Validations

```bsl
Procedure BeforeWrite(Cancel)
    // ✅ Use OR to accumulate Cancel state
    If Not ValueIsFilled(Date) Then
        Cancel = True;
        Message("Date is required");
    EndIf;
    
    If Not ValueIsFilled(Customer) Then
        Cancel = True;
        Message("Customer is required");
    EndIf;
    
    // Note: Cancel accumulates True values
    // If any validation fails, Cancel = True
EndProcedure
```

### Respecting Previous Cancel State

```bsl
Procedure BeforeWrite(Cancel)
    // ✅ Check if already canceled before doing expensive validation
    If Not Cancel Then
        If ExpensiveValidationFails() Then
            Cancel = True;
        EndIf;
    EndIf;
EndProcedure
```

---

## 🔧 How to Fix

### Step 1: Find incorrect assignments

Search for patterns:
- `Cancel = False`
- `StandardProcessing = True`
- `Perform = True`

### Step 2: Remove or correct assignments

**Before:**
```bsl
If Condition Then
    Cancel = True;
Else
    Cancel = False;  // Remove this!
EndIf;
```

**After:**
```bsl
If Condition Then
    Cancel = True;
EndIf;
```

### Step 3: Use conditional logic correctly

If you need conditional behavior, only set to the "action" value:

```bsl
// For Cancel (action = True)
If ShouldCancel Then
    Cancel = True;
EndIf;

// For StandardProcessing (action = False)
If ShouldOverrideDefault Then
    StandardProcessing = False;
EndIf;
```

---

## ⚙️ Check Parameters

| Parameter | Default | Description |
|-----------|---------|-------------|
| `checkEventOnly` | `true` | Check only event handlers (vs all methods) |
| `paramsToTrue` | `Cancel` | Parameters that should only be set to True |
| `paramsToFalse` | `StandardProcessing,Perform` | Parameters that should only be set to False |

---

## 🔍 Technical Details

### What Is Checked

1. Finds methods with boolean parameters matching configured names
2. Finds assignment statements for these parameters
3. Checks if assignment value matches expected direction
4. Reports if parameter is set to wrong value

### Check Implementation Class

```
com.e1c.v8codestyle.bsl.check.EventHandlerBooleanParamCheck
```

### Location in v8-code-style

```
bundles/com.e1c.v8codestyle.bsl/src/com/e1c/v8codestyle/bsl/check/
```

