# module-structure-event-regions-check

## 📋 General Information

| Parameter | Value |
|-----------|-------|
| **Check ID** | `module-structure-event-regions-check` |
| **Title** | Checks the region of event handlers |
| **Description** | Checks that event handlers are placed in correct event handler regions |
| **Severity** | `MINOR` |
| **Type** | `CODE_SMELL` |
| **Complexity** | `NORMAL` |
| **Default State** | Enabled |

---

## 🎯 What This Check Does

This check verifies that **event handler methods** in object modules (catalogs, documents, etc.) are placed in the appropriate regions. Only event handlers should be in event regions, and all event handlers should be placed in these regions.

### Why This Is Important

- **Code organization**: Clear separation of event handlers from business logic
- **Navigation**: Easy to find handlers by looking in known regions
- **Consistency**: Uniform structure across all modules
- **Standards compliance**: Follows 1C module structure standards

---

## ❌ Error Example

### Error Messages

```
The event handler "BeforeWrite" should be placed in the "EventHandlers" region
```

```
Only event methods can be placed in the "EventHandlers" region
```

### Noncompliant Code Example

```bsl
// ❌ Event handler outside region
Procedure BeforeWrite(Cancel)
    ValidateData(Cancel);
EndProcedure

#Region EventHandlers

// ❌ Non-event method in event region
Procedure CalculateTotal()
    // This is not an event handler!
EndProcedure

#EndRegion

#Region Private

// ❌ Event handler in wrong region
Procedure OnWrite(Cancel)
    WriteLog();
EndProcedure

#EndRegion
```

---

## ✅ Compliant Solution

### Correct Region Structure

```bsl
#Region EventHandlers

// ✅ Event handlers in correct region
Procedure BeforeWrite(Cancel)
    ValidateData(Cancel);
EndProcedure

Procedure OnWrite(Cancel)
    WriteLog();
EndProcedure

Procedure BeforeDelete(Cancel)
    CheckDeletePermission(Cancel);
EndProcedure

Procedure Filling(FillingData, FillingText, StandardProcessing)
    FillDefaults(FillingData);
EndProcedure

#EndRegion

#Region Private

// ✅ Helper methods in Private region
Procedure ValidateData(Cancel)
    If Not ValueIsFilled(Object.Customer) Then
        Cancel = True;
        Message("Customer is required");
    EndIf;
EndProcedure

Procedure CalculateTotal()
    // Calculation logic
EndProcedure

Procedure WriteLog()
    // Logging logic
EndProcedure

#EndRegion
```

---

## 📋 Standard Object Module Events

### Catalog Object Module

```bsl
#Region EventHandlers

Procedure BeforeWrite(Cancel)
EndProcedure

Procedure OnWrite(Cancel)
EndProcedure

Procedure BeforeDelete(Cancel)
EndProcedure

Procedure OnCopy(CopiedObject)
EndProcedure

Procedure Filling(FillingData, FillingText, StandardProcessing)
EndProcedure

Procedure FillCheckProcessing(Cancel, CheckedAttributes)
EndProcedure

#EndRegion
```

### Document Object Module

```bsl
#Region EventHandlers

Procedure BeforeWrite(Cancel, WriteMode, PostingMode)
EndProcedure

Procedure OnWrite(Cancel)
EndProcedure

Procedure BeforeDelete(Cancel)
EndProcedure

Procedure Posting(Cancel, PostingMode)
EndProcedure

Procedure UndoPosting(Cancel)
EndProcedure

Procedure OnCopy(CopiedObject)
EndProcedure

Procedure Filling(FillingData, FillingText, StandardProcessing)
EndProcedure

Procedure FillCheckProcessing(Cancel, CheckedAttributes)
EndProcedure

#EndRegion
```

---

## 📖 Event Handler Categories

### Write Events

| Event | Description |
|-------|-------------|
| `BeforeWrite` | Before object is written |
| `OnWrite` | During write transaction |
| `AfterWrite` | After successful write |

### Delete Events

| Event | Description |
|-------|-------------|
| `BeforeDelete` | Before object deletion |

### Copy/Fill Events

| Event | Description |
|-------|-------------|
| `OnCopy` | When copying object |
| `Filling` | When filling from basis |
| `FillCheckProcessing` | Fill check validation |

### Document-Specific Events

| Event | Description |
|-------|-------------|
| `Posting` | Document posting |
| `UndoPosting` | Reversing posted document |

---

## 📋 Module Structure

### Standard Module Organization

```bsl
#Region Variables
    // Module-level variables
#EndRegion

#Region Public
    // Export procedures and functions
#EndRegion

#Region EventHandlers
    // Event handlers only
#EndRegion

#Region Private
    // Internal helper methods
#EndRegion
```

---

## 🔧 How to Fix

### Step 1: Identify event handlers

Find all methods that are event handlers (BeforeWrite, OnWrite, etc.).

### Step 2: Create EventHandlers region

```bsl
#Region EventHandlers
#EndRegion
```

### Step 3: Move event handlers

```bsl
// Move from anywhere to EventHandlers region
#Region EventHandlers
Procedure BeforeWrite(Cancel)
    // Handler logic
EndProcedure
#EndRegion
```

### Step 4: Move non-events out

```bsl
// Move helper methods to Private
#Region Private
Procedure HelperMethod()
    // Helper logic
EndProcedure
#EndRegion
```

---

## ⚠️ Common Mistakes

### Mistake 1: Mixed Content

```bsl
// ❌ Event and non-event methods mixed
#Region EventHandlers
Procedure BeforeWrite(Cancel)
EndProcedure

Procedure CalculateDiscount() // ❌ Not an event!
EndProcedure
#EndRegion
```

### Mistake 2: Events in Private

```bsl
// ❌ Event in Private region
#Region Private
Procedure OnWrite(Cancel) // ❌ Should be in EventHandlers
EndProcedure
#EndRegion
```

### Mistake 3: No Region

```bsl
// ❌ Event without region
Procedure BeforeWrite(Cancel)
EndProcedure
```

---

## 🔍 Technical Details

### What Is Checked

1. Object module structure
2. Event handler identification
3. Region content validation

### Event Handler Recognition

The check identifies event handlers by their standard names:
- `BeforeWrite`, `OnWrite`, `BeforeDelete`
- `Posting`, `UndoPosting`
- `Filling`, `FillCheckProcessing`
- `OnCopy`, etc.

### Check Implementation Class

```
com.e1c.v8codestyle.bsl.check.ModuleStructureEventRegionsCheck
```

### Location in v8-code-style

```
bundles/com.e1c.v8codestyle.bsl/src/com/e1c/v8codestyle/bsl/check/
```

---

## 📚 References

- [Module Structure Event Form Regions Check](module-structure-event-form-regions-check.md)
- [Module Structure Method in Region Check](module-structure-method-in-region-check.md)
