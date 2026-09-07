# module-structure-top-region-check

## 📋 General Information

| Parameter | Value |
|-----------|-------|
| **Check ID** | `module-structure-top-region-check` |
| **Title** | Module structure top region |
| **Description** | Checks that top-level regions match the standard for the module type |
| **Severity** | `MINOR` |
| **Type** | `CODE_SMELL` |
| **Complexity** | `NORMAL` |
| **Default State** | Enabled |

---

## 🎯 What This Check Does

This check verifies that **top-level regions** in a module conform to the standard structure for that module type. It validates region names, order, and prevents duplicates.

### Why This Is Important

- **Consistency**: All modules follow the same structure
- **Navigation**: Developers know where to find code
- **Maintainability**: Clear organization makes changes easier
- **Standards compliance**: Follows 1C module structure standard

---

## ❌ Error Example

### Error Messages

```
Region is not standard for current type of module
```

```
Module structure region is not on top
```

```
Region has duplicate
```

```
Region has the wrong order
```

### Noncompliant Code Example

```bsl
// ❌ Non-standard region name
#Region MyCustomRegion
    Procedure DoSomething() Export
    EndProcedure
#EndRegion

// ❌ Wrong region order
#Region Private
    Procedure HelperMethod()
    EndProcedure
#EndRegion

#Region Public  // ❌ Should come before Private
    Procedure APIMethod() Export
    EndProcedure
#EndRegion

// ❌ Duplicate region
#Region Private
    Procedure AnotherHelper()
    EndProcedure
#EndRegion
```

---

## ✅ Compliant Solution

### Correct Common Module Structure

```bsl
#Region Public

// ✅ Public API first
Procedure PublicMethod() Export
EndProcedure

#EndRegion

#Region Internal

// ✅ Internal methods second
Procedure InternalMethod() Export
EndProcedure

#EndRegion

#Region Private

// ✅ Private methods third
Procedure PrivateMethod()
EndProcedure

#EndRegion

#Region Initialize

// ✅ Initialization last
InitializeModule();

#EndRegion
```

### Correct Object Module Structure

```bsl
#Region Public

Procedure PublicAPI() Export
EndProcedure

#EndRegion

#Region EventHandlers

Procedure BeforeWrite(Cancel)
EndProcedure

Procedure OnWrite(Cancel)
EndProcedure

#EndRegion

#Region Private

Procedure HelperMethod()
EndProcedure

#EndRegion
```

### Correct Form Module Structure

```bsl
#Region FormEventHandlers

Procedure OnOpen(Cancel)
EndProcedure

Procedure OnClose(Exit)
EndProcedure

#EndRegion

#Region FormHeaderItemsEventHandlers

Procedure CustomerOnChange(Item)
EndProcedure

#EndRegion

#Region FormTableItemsEventHandlers

Procedure ItemsOnActivateRow(Item)
EndProcedure

#EndRegion

#Region FormCommandsEventHandlers

Procedure ProcessCommand(Command)
EndProcedure

#EndRegion

#Region Private

Procedure HelperMethod()
EndProcedure

#EndRegion
```

---

## 📋 Standard Regions by Module Type

### Common Module

| Order | Region | Required |
|-------|--------|----------|
| 1 | `Public` | No |
| 2 | `Internal` | No |
| 3 | `Private` | No |
| 4 | `Initialize` | No |

### Object Module

| Order | Region | Required |
|-------|--------|----------|
| 1 | `Public` | No |
| 2 | `EventHandlers` | No |
| 3 | `Internal` | No |
| 4 | `Private` | No |

### Manager Module

| Order | Region | Required |
|-------|--------|----------|
| 1 | `Public` | No |
| 2 | `EventHandlers` | No |
| 3 | `Internal` | No |
| 4 | `Private` | No |

### Form Module

| Order | Region | Required |
|-------|--------|----------|
| 1 | `Variables` | No |
| 2 | `FormEventHandlers` | No |
| 3 | `FormHeaderItemsEventHandlers` | No |
| 4 | `FormTableItemsEventHandlers` | No |
| 5 | `FormCommandsEventHandlers` | No |
| 6 | `Private` | No |

---

## ⚙️ Check Parameters

| Parameter | Default | Description |
|-----------|---------|-------------|
| `checkDuplicates` | `True` | Check for duplicate regions |
| `checkOrder` | `True` | Check region order |
| `excludeRegionName` | `""` | Comma-separated list of excluded region names |

### Configuration Example

```
# Exclude custom regions
excludeRegionName = Deprecated, Legacy
```

---

## 🔧 How to Fix

### Step 1: Identify module type

Determine what kind of module you're working with (common, object, form, etc.).

### Step 2: Review standard regions

Check the standard regions for that module type.

### Step 3: Rename non-standard regions

```bsl
// Before
#Region MyMethods
    Procedure DoSomething() Export
    EndProcedure
#EndRegion

// After
#Region Public
    Procedure DoSomething() Export
    EndProcedure
#EndRegion
```

### Step 4: Reorder regions

```bsl
// Wrong order
#Region Private
#EndRegion
#Region Public  // Should be first
#EndRegion

// Correct order
#Region Public
#EndRegion
#Region Private
#EndRegion
```

### Step 5: Remove duplicates

```bsl
// Merge duplicate regions
#Region Private
    Procedure Helper1()
    EndProcedure
    
    Procedure Helper2()  // Moved from duplicate region
    EndProcedure
#EndRegion
```

---

## 📖 Region Order Diagram

### Common Module

```
┌─────────────────┐
│    Public       │ ← External API
├─────────────────┤
│   Internal      │ ← Subsystem API
├─────────────────┤
│    Private      │ ← Helper methods
├─────────────────┤
│   Initialize    │ ← Startup code
└─────────────────┘
```

### Form Module

```
┌─────────────────────────────────┐
│       FormEventHandlers         │ ← OnOpen, OnClose
├─────────────────────────────────┤
│ FormHeaderItemsEventHandlers    │ ← Item events
├─────────────────────────────────┤
│ FormTableItemsEventHandlers     │ ← Table events
├─────────────────────────────────┤
│ FormCommandsEventHandlers       │ ← Command handlers
├─────────────────────────────────┤
│         Private                 │ ← Helper methods
└─────────────────────────────────┘
```

---

## ⚠️ Common Issues

### Non-Standard Names

```bsl
// ❌ Non-standard (various attempts)
#Region Helpers
#Region Utilities
#Region API
#Region Main

// ✅ Standard names
#Region Public
#Region Private
```

### Missing Export Consideration

When reorganizing, ensure methods have correct Export keyword:
- Public/Internal → needs Export
- Private → no Export

---

## 🔍 Technical Details

### What Is Checked

1. Top-level region names
2. Region order
3. Duplicate regions
4. Module type context

### Check Implementation Class

```
com.e1c.v8codestyle.bsl.check.ModuleStructureTopRegionCheck
```

### Location in v8-code-style

```
bundles/com.e1c.v8codestyle.bsl/src/com/e1c/v8codestyle/bsl/check/
```

---

## 📚 References

- [Module Structure Method in Region Check](module-structure-method-in-region-check.md)
- [Module Structure Event Regions Check](module-structure-event-regions-check.md)
- [1C Module Structure Standard](https://its.1c.ru/db/v8std)
