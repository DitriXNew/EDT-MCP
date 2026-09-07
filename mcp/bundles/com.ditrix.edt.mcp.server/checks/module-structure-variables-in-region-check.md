# module-structure-variables-in-region-check

## 📋 General Information

| Parameter | Value |
|-----------|-------|
| **Check ID** | `module-structure-variables-in-region-check` |
| **Title** | Variable declaration is in the correct region |
| **Description** | Checks that variable declarations are placed in the Variables region |
| **Severity** | `MINOR` |
| **Type** | `CODE_SMELL` |
| **Complexity** | `NORMAL` |
| **Default State** | Enabled |

---

## 🎯 What This Check Does

This check ensures that **module-level variable declarations** are placed in the designated **Variables** region. All `Var` declarations should be at the top of the module in this region.

### Why This Is Important

- **Code organization**: Variables are easy to find
- **Visibility**: All module state is declared in one place
- **Maintenance**: Easy to review and modify variables
- **Standards compliance**: Follows 1C module structure standard

---

## ❌ Error Example

### Error Message

```
Variable declaration should be placed in the {RegionName} region
```

### Noncompliant Code Example

```bsl
#Region Public

    // ❌ Variable in Public region
    Var Counter;
    
    Procedure Increment() Export
        Counter = Counter + 1;
    EndProcedure

#EndRegion

// ❌ Variable outside any region
Var GlobalSetting;

#Region Private

    // ❌ Variable in Private region
    Var TempValue;
    
    Procedure Helper()
    EndProcedure

#EndRegion
```

---

## ✅ Compliant Solution

### Correct Structure with Variables Region

```bsl
#Region Variables

// ✅ All variables in Variables region
Var Counter;
Var GlobalSetting;
Var TempValue;
Var ModuleCache Export; // Export variables also here

#EndRegion

#Region Public

Procedure Increment() Export
    Counter = Counter + 1;
EndProcedure

Procedure GetSetting() Export
    Return GlobalSetting;
EndProcedure

#EndRegion

#Region Private

Procedure Helper()
    TempValue = 0;
EndProcedure

#EndRegion
```

---

## 📋 Variable Region Structure

### Standard Position

The Variables region should be the **first region** in the module:

```bsl
#Region Variables        // ← First
    // All Var declarations
#EndRegion

#Region Public           // ← Second
    // Export methods
#EndRegion

#Region Private          // ← Third
    // Helper methods
#EndRegion

#Region Initialize       // ← Last
    // Initialization code
#EndRegion
```

---

## 📖 Variable Types

### Export Variables

```bsl
#Region Variables

// ✅ Export variables visible to external code
Var PublicCounter Export;
Var LastError Export;

// Regular module variables
Var InternalCache;

#EndRegion
```

### Typed Variables

```bsl
#Region Variables

// Variables can have type annotations in comments
Var FilesList; // Array
Var Settings;  // Structure
Var Connection; // HTTPConnection

#EndRegion
```

---

## 🔧 How to Fix

### Step 1: Find all variable declarations

Search for all `Var` statements in the module.

### Step 2: Create Variables region

```bsl
#Region Variables
#EndRegion
```

### Step 3: Move all variables

```bsl
// Before (scattered)
Var A;
#Region Public
Var B;
    Procedure Method() Export
    EndProcedure
#EndRegion
Var C;

// After (consolidated)
#Region Variables
Var A;
Var B;
Var C;
#EndRegion

#Region Public
    Procedure Method() Export
    EndProcedure
#EndRegion
```

### Step 4: Place region first

Ensure Variables is the first region in the module.

---

## 📋 Module Structure Order

### Complete Module Template

```bsl
#Region Variables

Var ModuleSettings;
Var EventHandlers;
Var IsInitialized;

#EndRegion

#Region Public

Procedure PublicAPI() Export
    // Public methods
EndProcedure

#EndRegion

#Region Internal

Procedure InternalAPI() Export
    // Internal methods
EndProcedure

#EndRegion

#Region EventHandlers

Procedure BeforeWrite(Cancel)
    // Event handlers
EndProcedure

#EndRegion

#Region Private

Procedure HelperMethod()
    // Private methods
EndProcedure

#EndRegion

#Region Initialize

// Initialization
IsInitialized = False;
InitializeModule();

#EndRegion
```

---

## ⚠️ Common Mistakes

### Mistake 1: Variables in Code Regions

```bsl
// ❌ Variable mixed with code
#Region Public
Var Counter; // Wrong place!
Procedure Method() Export
EndProcedure
#EndRegion
```

### Mistake 2: Variables After Methods

```bsl
// ❌ Variables should come first
#Region Public
Procedure Method() Export
EndProcedure
#EndRegion

Var LateVariable; // Wrong - should be in Variables region at top
```

### Mistake 3: No Region At All

```bsl
// ❌ Variables without region
Var NoRegionVariable;

#Region Public
...
```

---

## 📖 Form Module Variables

### Form Module Structure

```bsl
#Region Variables

// Form module variables
Var FormCache;
Var LastSelection;

#EndRegion

#Region FormEventHandlers

Procedure OnOpen(Cancel)
    FormCache = New Map;
EndProcedure

#EndRegion
```

---

## 🔍 Technical Details

### What Is Checked

1. All `Var` declarations in module
2. Region containing each declaration
3. Correct region name ("Variables")

### Variable Declaration Syntax

```bsl
// Simple declaration
Var VariableName;

// Export declaration
Var VariableName Export;
```

### Check Implementation Class

```
com.e1c.v8codestyle.bsl.check.ModuleStructureVariablesInRegionCheck
```

### Location in v8-code-style

```
bundles/com.e1c.v8codestyle.bsl/src/com/e1c/v8codestyle/bsl/check/
```

---

## 📚 References

- [Module Structure Top Region Check](module-structure-top-region-check.md)
- [Module Structure Init Code in Region Check](module-structure-init-code-in-region-check.md)
- [1C Module Structure Standard](https://its.1c.ru/db/v8std)
