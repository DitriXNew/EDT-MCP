# module-structure-event-form-regions-check

## 📋 General Information

| Parameter | Value |
|-----------|-------|
| **Check ID** | `module-structure-event-form-regions-check` |
| **Title** | Checks the region of form event handlers |
| **Description** | Checks that form event handler methods are placed in correct regions |
| **Severity** | `MINOR` |
| **Type** | `CODE_SMELL` |
| **Complexity** | `NORMAL` |
| **Default State** | Enabled |

---

## 🎯 What This Check Does

This check verifies that **form event handler methods** are placed in the appropriate regions according to the module structure standard. It ensures handlers are in event-related regions and non-handlers are not.

### Why This Is Important

- **Code organization**: Consistent structure across all modules
- **Navigation**: Easy to find event handlers
- **Maintainability**: Clear separation of concerns
- **Standards compliance**: Follows 1C development standards

---

## ❌ Error Example

### Error Messages

```
Method "OnOpen" can not be placed in the "Private" region
```

```
The event method "OnOpen" should be placed in the "FormEventHandlers" region
```

### Noncompliant Code Example

```bsl
// ❌ Event handler in wrong region
#Region Private

Procedure OnOpen(Cancel) // ❌ Event handler in Private region
    InitializeForm();
EndProcedure

Procedure CalculateTotal()
    // Helper method - OK here
EndProcedure

#EndRegion

// ❌ Event handler outside any region
Procedure BeforeClose(Cancel)
    SaveSettings();
EndProcedure
```

---

## ✅ Compliant Solution

### Correct Region Structure

```bsl
#Region FormEventHandlers

// ✅ Event handlers in correct region
Procedure OnOpen(Cancel)
    InitializeForm();
EndProcedure

Procedure BeforeClose(Cancel)
    SaveSettings();
EndProcedure

Procedure OnCreateAtServer(Cancel, StandardProcessing)
    FillFormData();
EndProcedure

#EndRegion

#Region FormHeaderItemsEventHandlers

// ✅ Form item event handlers
Procedure CustomerOnChange(Item)
    RecalculatePrices();
EndProcedure

Procedure DateOnChange(Item)
    UpdateExchangeRate();
EndProcedure

#EndRegion

#Region FormTableItemsEventHandlers

// ✅ Table event handlers
Procedure ItemsOnActivateRow(Item)
    UpdateItemDetails();
EndProcedure

Procedure ItemsBeforeAddRow(Item, Cancel, Clone, Parent, IsFolder)
    CheckAddingAllowed(Cancel);
EndProcedure

#EndRegion

#Region Private

// ✅ Private helper methods
Procedure InitializeForm()
    // Initialization logic
EndProcedure

Procedure CalculateTotal()
    // Calculation logic
EndProcedure

#EndRegion
```

---

## 📋 Standard Form Module Regions

### Region Structure

| Region | Purpose | Contains |
|--------|---------|----------|
| `FormEventHandlers` | Form lifecycle events | OnOpen, BeforeClose, OnCreateAtServer, etc. |
| `FormHeaderItemsEventHandlers` | Header item events | Item OnChange, OnActivate, etc. |
| `FormTableItemsEventHandlers` | Table/list events | OnActivateRow, BeforeAddRow, etc. |
| `FormCommandsEventHandlers` | Command handlers | Command action handlers |
| `Private` | Internal methods | Helper procedures and functions |

### Region Order

```bsl
#Region Variables
#EndRegion

#Region FormEventHandlers
#EndRegion

#Region FormHeaderItemsEventHandlers
#EndRegion

#Region FormTableItemsEventHandlers
#EndRegion

#Region FormCommandsEventHandlers
#EndRegion

#Region Private
#EndRegion
```

---

## 📖 Event Handler Categories

### Form Events

```bsl
#Region FormEventHandlers

// Form lifecycle
Procedure OnOpen(Cancel)
EndProcedure

Procedure OnClose(Exit)
EndProcedure

Procedure BeforeClose(Cancel, Exit, WarningText, StandardProcessing)
EndProcedure

// Server events
Procedure OnCreateAtServer(Cancel, StandardProcessing)
EndProcedure

Procedure OnReadAtServer(CurrentObject)
EndProcedure

Procedure BeforeWriteAtServer(Cancel, CurrentObject, WriteParameters)
EndProcedure

Procedure AfterWriteAtServer(CurrentObject, WriteParameters)
EndProcedure

#EndRegion
```

### Header Item Events

```bsl
#Region FormHeaderItemsEventHandlers

Procedure CustomerOnChange(Item)
EndProcedure

Procedure DateOnChange(Item)
EndProcedure

Procedure CommentStartChoice(Item, ChoiceData, StandardProcessing)
EndProcedure

#EndRegion
```

### Table Events

```bsl
#Region FormTableItemsEventHandlers

Procedure ItemsOnActivateRow(Item)
EndProcedure

Procedure ItemsSelection(Item, SelectedRow, Field, StandardProcessing)
EndProcedure

Procedure ItemsBeforeAddRow(Item, Cancel, Clone, Parent, IsFolder)
EndProcedure

Procedure ItemsAfterDeleteRow(Item)
EndProcedure

#EndRegion
```

---

## ⚙️ Check Parameters

| Parameter | Default | Description |
|-----------|---------|-------------|
| `excludedMethodNames` | `""` | Comma-separated list of excluded command method names |
| `multilevelNesting` | `False` | Allow multilevel nesting of regions |

---

## 🔧 How to Fix

### Step 1: Identify the event handler

Determine what type of event the handler handles.

### Step 2: Create correct region if missing

```bsl
#Region FormEventHandlers
#EndRegion
```

### Step 3: Move handler to correct region

```bsl
// Move from:
#Region Private
Procedure OnOpen(Cancel)
EndProcedure
#EndRegion

// To:
#Region FormEventHandlers
Procedure OnOpen(Cancel)
EndProcedure
#EndRegion
```

### Step 4: Verify region placement

Ensure all event handlers are in appropriate regions.

---

## 📋 Quick Reference

### Which Region for Which Handler?

| Handler Pattern | Region |
|-----------------|--------|
| `OnOpen`, `OnClose`, `BeforeClose` | `FormEventHandlers` |
| `OnCreateAtServer`, `OnReadAtServer` | `FormEventHandlers` |
| `BeforeWriteAtServer`, `AfterWriteAtServer` | `FormEventHandlers` |
| `{ItemName}OnChange` | `FormHeaderItemsEventHandlers` |
| `{ItemName}StartChoice` | `FormHeaderItemsEventHandlers` |
| `{TableName}OnActivateRow` | `FormTableItemsEventHandlers` |
| `{TableName}BeforeAddRow` | `FormTableItemsEventHandlers` |
| `{CommandName}Command` | `FormCommandsEventHandlers` |

---

## 🔍 Technical Details

### What Is Checked

1. Form module methods
2. Event handler identification
3. Region placement verification

### Check Implementation Class

```
com.e1c.v8codestyle.bsl.check.ModuleStructureEventFormRegionsCheck
```

### Location in v8-code-style

```
bundles/com.e1c.v8codestyle.bsl/src/com/e1c/v8codestyle/bsl/check/
```

---

## 📚 References

- [Module Structure Event Regions Check](module-structure-event-regions-check.md)
- [Module Structure Method in Region Check](module-structure-method-in-region-check.md)
