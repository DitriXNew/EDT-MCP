# module-structure-init-code-in-region-check

## 📋 General Information

| Parameter | Value |
|-----------|-------|
| **Check ID** | `module-structure-init-code-in-region-check` |
| **Title** | Initialize code is in the correct region |
| **Description** | Checks that initialization code is placed in the Initialize region |
| **Severity** | `MINOR` |
| **Type** | `CODE_SMELL` |
| **Complexity** | `NORMAL` |
| **Default State** | Enabled |

---

## 🎯 What This Check Does

This check ensures that **module initialization code** (code executed when the module loads) is placed in the designated **Initialize** region. This follows the 1C module structure standard.

### Why This Is Important

- **Code organization**: Initialization code is easy to find
- **Predictability**: Module loading behavior is clear
- **Maintenance**: Easy to review and modify startup code
- **Standards compliance**: Follows 1C module structure standard

---

## ❌ Error Example

### Error Message

```
Initialize code should be placed in the {RegionName} region
```

### Noncompliant Code Example

```bsl
#Region Variables
    Var ModuleSettings;
#EndRegion

#Region Public
    Procedure DoSomething() Export
    EndProcedure
#EndRegion

// ❌ Initialization code outside Initialize region
ModuleSettings = New Structure;
ModuleSettings.Insert("Timeout", 30);
LoadDefaultSettings();

#Region Private
    Procedure LoadDefaultSettings()
    EndProcedure
#EndRegion
```

---

## ✅ Compliant Solution

### Correct Structure with Initialize Region

```bsl
#Region Variables
    Var ModuleSettings;
#EndRegion

#Region Public
    Procedure DoSomething() Export
    EndProcedure
#EndRegion

#Region Private
    Procedure LoadDefaultSettings()
    EndProcedure
#EndRegion

#Region Initialize

// ✅ Initialization code in correct region
ModuleSettings = New Structure;
ModuleSettings.Insert("Timeout", 30);
LoadDefaultSettings();

#EndRegion
```

---

## 📋 Module Initialization Code

### What Is Initialization Code

Initialization code is any code that:
- Executes when the module is loaded
- Is outside any procedure or function
- Sets up module-level variables
- Performs startup configuration

### Examples of Initialization Code

```bsl
// Variable initialization
Var Counter;

// Immediate code (runs on load)
Counter = 0;
InitializeModule();
RegisterHandlers();

// Constants setup
MaxRetries = 3;
DefaultTimeout = 30;
```

---

## 📖 Module Structure Order

### Standard Region Order

```bsl
#Region Variables
    // Module-level variable declarations
    Var ModuleCache;
    Var IsInitialized;
#EndRegion

#Region Public
    // Public API - export procedures and functions
    Procedure PublicMethod() Export
    EndProcedure
#EndRegion

#Region Internal
    // Internal methods for use by other modules in same subsystem
    Procedure InternalMethod() Export
    EndProcedure
#EndRegion

#Region Private
    // Private helper methods
    Procedure HelperMethod()
    EndProcedure
#EndRegion

#Region Initialize
    // Module initialization code
    IsInitialized = False;
    ModuleCache = New Map;
    InitializeModule();
#EndRegion
```

---

## 📋 Region Naming

### Common Names for Initialize Region

| Language | Region Name |
|----------|-------------|
| English | `Initialize` |
| Russian | `Инициализация` |

### Alternative Valid Names

- `Initialization`
- `ModuleInitialization`

---

## 🔧 How to Fix

### Step 1: Identify initialization code

Find all code that runs when the module loads (outside procedures/functions).

### Step 2: Create Initialize region

```bsl
#Region Initialize
#EndRegion
```

### Step 3: Move initialization code

```bsl
// Before (at the end of module, no region)
Counter = 0;
RegisterEventHandlers();

// After
#Region Initialize
Counter = 0;
RegisterEventHandlers();
#EndRegion
```

### Step 4: Place region correctly

The Initialize region should typically be at the end of the module.

---

## 📖 Examples

### Common Module Initialization

```bsl
#Region Variables
    Var ProcessingQueue;
    Var EventHandlers;
#EndRegion

#Region Public
    Procedure ProcessItem(Item) Export
        // ...
    EndProcedure
#EndRegion

#Region Private
    Procedure InitializeQueue()
        ProcessingQueue = New Array;
    EndProcedure
    
    Procedure RegisterHandlers()
        EventHandlers = New Map;
        EventHandlers.Insert("OnStart", "HandleStart");
        EventHandlers.Insert("OnComplete", "HandleComplete");
    EndProcedure
#EndRegion

#Region Initialize
    // ✅ All initialization in correct region
    InitializeQueue();
    RegisterHandlers();
#EndRegion
```

### Form Module Initialization

```bsl
#Region FormEventHandlers
    Procedure OnCreateAtServer(Cancel, StandardProcessing)
        // Server-side initialization
    EndProcedure
    
    Procedure OnOpen(Cancel)
        // Client-side initialization
    EndProcedure
#EndRegion

#Region Private
    Procedure SetupFormElements()
    EndProcedure
#EndRegion

#Region Initialize
    // ✅ Module-level initialization
    // (Runs before any event handlers)
#EndRegion
```

---

## ⚠️ Best Practices

### Do

```bsl
// ✅ Keep initialization code simple
#Region Initialize
    InitializeModuleSettings();
#EndRegion
```

### Don't

```bsl
// ❌ Complex logic in initialization
#Region Initialize
    Query = New Query;
    Query.Text = "SELECT * FROM Catalog.Products";
    // Complex database operations on module load
#EndRegion
```

### Lazy Initialization

```bsl
// Better pattern for expensive operations
Var ModuleCache;

Function GetCache() Export
    If ModuleCache = Undefined Then
        ModuleCache = LoadCacheFromDatabase();
    EndIf;
    Return ModuleCache;
EndFunction

#Region Initialize
    ModuleCache = Undefined; // Just mark as not loaded
#EndRegion
```

---

## 🔍 Technical Details

### What Is Checked

1. Module structure analysis
2. Code outside procedures/functions
3. Region placement verification

### Check Implementation Class

```
com.e1c.v8codestyle.bsl.check.ModuleStructureInitCodeInRegionCheck
```

### Location in v8-code-style

```
bundles/com.e1c.v8codestyle.bsl/src/com/e1c/v8codestyle/bsl/check/
```

---

## 📚 References

- [Module Structure Variables in Region Check](module-structure-variables-in-region-check.md)
- [Module Structure Method in Region Check](module-structure-method-in-region-check.md)
