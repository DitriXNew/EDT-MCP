# common-module-missing-api

## 📋 General Information

| Parameter | Value |
|-----------|-------|
| **Check ID** | `common-module-missing-api` |
| **Title** | Programming interface |
| **Description** | Common module does not contain a programming interface |
| **Severity** | `MINOR` |
| **Type** | `CODE_STYLE` |
| **Complexity** | `NORMAL` |
| **Default State** | Enabled |
| **1C Standard** | [455](https://its.1c.ru/db/v8std/content/455/hdoc) |

---

## 🎯 What This Check Does

This check validates that a **common module contains at least one export procedure or function**. A common module without any export methods has no public API and cannot be used by other modules.

### Why This Is Important

- **Module purpose**: Common modules exist to provide shared functionality
- **Code organization**: Empty or non-export modules indicate design issues
- **Maintainability**: Unused modules clutter the codebase
- **Standards compliance**: Follows 1C standard 455 for module structure

---

## ❌ Error Example

### Error Message

```
Common module does not contain a programming interface
```

**Russian:**
```
Common module does not contain programming interface
```

### Noncompliant Code Example

```bsl
// CommonModule.UtilityModule
// ❌ No export procedures or functions

Procedure InternalHelper()
    // Private method - not accessible from outside
EndProcedure

Function PrivateCalculation()
    Return 42;
EndFunction
```

---

## ✅ Compliant Solution

### Correct Common Module

```bsl
// CommonModule.UtilityModule
// ✅ Has export procedures/functions - provides public API

#Region Public

// Calculates the sum of two numbers.
// 
// Parameters:
//  A - Number - first number
//  B - Number - second number
//
// Returns:
//  Number - sum of A and B
//
Function Sum(A, B) Export
    Return A + B;
EndFunction

// Formats a date for display.
//
// Parameters:
//  Date - Date - date to format
//
// Returns:
//  String - formatted date string
//
Function FormatDate(Date) Export
    Return Format(Date, "DLF=D");
EndFunction

#EndRegion

#Region Private

// Internal helper function
Function InternalHelper()
    Return True;
EndFunction

#EndRegion
```

### Module Structure Pattern

```bsl
// ✅ Proper common module structure

#Region Public

// Public API methods - accessible from other modules
Procedure PublicMethod() Export
    // Implementation
EndProcedure

#EndRegion

#Region Internal

// Internal methods - for use within subsystem
Procedure InternalMethod() Export
    // Implementation
EndProcedure

#EndRegion

#Region Private

// Private methods - only for this module
Procedure PrivateHelper()
    // Implementation
EndProcedure

#EndRegion
```

---

## 🔧 How to Fix

### Option 1: Add Export keyword to existing methods

```bsl
// Before
Function Calculate()
    Return 42;
EndFunction

// After
Function Calculate() Export
    Return 42;
EndFunction
```

### Option 2: Add new public methods

If the module has only internal helpers, consider:
1. Moving internal methods to the module that uses them
2. Adding public wrapper methods that expose the functionality

### Option 3: Delete the module

If the module is truly unused:
1. Verify no other modules reference it
2. Remove the module from configuration

---

## 📖 Common Module Best Practices

### Recommended Structure

| Region | Purpose | Export |
|--------|---------|--------|
| `#Region Public` | Public API for external callers | Yes |
| `#Region Internal` | Internal API within subsystem | Yes |
| `#Region Private` | Helper methods for this module | No |

### Naming Conventions

| Module Type | Suffix | Example |
|-------------|--------|---------|
| Server module | (none) | `CommonModule` |
| Client module | `Client` | `CommonModuleClient` |
| Client-Server | `ClientServer` | `CommonModuleClientServer` |
| Server Call | `ServerCall` | `CommonModuleServerCall` |
| Cached | `Cached` | `CommonModuleCached` |

---

## 📁 File Structure

This check applies to:

| Object Type | Description |
|-------------|-------------|
| Common modules | All common module objects in configuration |

### Location in Configuration

```
CommonModules/
└── ModuleName/
    └── Module.bsl  ← This file is checked
```

---

## 🔍 Technical Details

### What Is Checked

1. Finds all `CommonModule` objects in configuration
2. Parses the module's BSL code
3. Searches for any method with `Export` keyword
4. Reports issue if no export methods found

### Check Implementation Class

```
com.e1c.v8codestyle.bsl.check.CommonModuleMissingApiCheck
```

### Location in v8-code-style

```
bundles/com.e1c.v8codestyle.bsl/src/com/e1c/v8codestyle/bsl/check/CommonModuleMissingApiCheck.java
```

---

## 📚 References

- [1C:Enterprise Development Standards - Standard 455](https://its.1c.ru/db/v8std/content/455/hdoc)
