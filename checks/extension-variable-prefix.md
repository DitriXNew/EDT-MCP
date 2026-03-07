# extension-variable-prefix

## 📋 General Information

| Parameter | Value |
|-----------|-------|
| **Check ID** | `extension-variable-prefix` |
| **Title** | Extension variable does not have extension prefix |
| **Description** | The variable in the module of the extension object does not have a prefix corresponding to the prefix of the extension itself |
| **Severity** | `MAJOR` |
| **Type** | `WARNING` |
| **Complexity** | `NORMAL` |
| **Default State** | Enabled |
| **1C Standard** | [640](https://its.1c.ru/db/v8std/content/640/hdoc) |

---

## 🎯 What This Check Does

This check validates that **module-level variables added in extension modules** have a **prefix matching the extension's name prefix**.

### Why This Is Important

- **Conflict prevention**: Avoids name collisions with main configuration variables
- **Origin clarity**: Easy to identify extension-specific variables
- **Upgrade safety**: Clear separation during configuration updates
- **Standards compliance**: Follows Standard 640

---

## ❌ Error Example

### Error Message

```
The variable "{variableName}" should have "{prefix}" prefix
```

**Russian:**
```
Переменная "{variableName}" должна иметь префикс "{prefix}"
```

### Noncompliant Code Example

**Extension name:** `CustomExt_Feature` (prefix: `CustomExt_`)

```bsl
// Extension of Catalog.Products module
#Region Variables

Var ProductCache;            // ❌ Variable without extension prefix
Var AdditionalSettings;      // ❌ Variable without extension prefix

#EndRegion
```

---

## ✅ Compliant Solution

### Correct Variable Naming

**Extension name:** `CustomExt_Feature` (prefix: `CustomExt_`)

```bsl
// Extension of Catalog.Products module
#Region Variables

Var CustomExt_ProductCache;            // ✅ Variable with extension prefix
Var CustomExt_AdditionalSettings;      // ✅ Variable with extension prefix

#EndRegion
```

---

## 📖 Extension Prefix Rules

### Determining the Prefix

The prefix is derived from the extension name:

| Extension Name | Prefix |
|----------------|--------|
| `MyCompany_SalesExtension` | `MyCompany_` |
| `МояКомпания_ПродажиРасширение` | `МояКомпания_` |
| `ABC_CustomFeature` | `ABC_` |

### Variable Naming Pattern

```
{ExtensionPrefix}{VariableName}
```

Examples:
- `ABC_CacheData`
- `MyExt_InitializationComplete`
- `Custom_TempStorage`

---

## 📋 What Needs Prefix

### Variables That Need Prefix

| Scenario | Needs Prefix |
|----------|--------------|
| Module-level variable in extension | ✓ Yes |
| Export variable in extension | ✓ Yes |
| Private module variable | ✓ Yes |

### Variables That Don't Need Prefix

| Scenario | Needs Prefix |
|----------|--------------|
| Local variables inside methods | ✗ No |
| Parameters in methods | ✗ No |
| Variables in main configuration | ✗ No |

---

## 📋 Extension Module Examples

### Adding Variables to Extended Module

```bsl
// Extension of Document.Invoice module
// Extension prefix: XYZ_

#Region Variables

// ✅ Module-level variable with prefix
Var XYZ_ExtendedProcessingEnabled;

// ✅ Export variable with prefix (if really needed)
Var XYZ_CustomSettings Export;

#EndRegion

#Region EventHandlers

&Before("BeforeWrite")
Procedure XYZ_BeforeWriteExtension(Cancel, WriteMode, PostingMode)
    // Use the prefixed variable
    If XYZ_ExtendedProcessingEnabled Then
        XYZ_PerformAdditionalProcessing();
    EndIf;
EndProcedure

#EndRegion

#Region Private

Procedure XYZ_PerformAdditionalProcessing()
    // Local variable - no prefix needed
    LocalResult = ComputeSomething();
    
    // Using prefixed module variable
    XYZ_ExtendedProcessingEnabled = False;
EndProcedure

#EndRegion
```

---

## 🔧 How to Fix

### Step 1: Identify extension prefix

Find the extension's configured prefix in extension properties.

### Step 2: Find variables without prefix

Look for `Var` declarations at module level in extension modules that don't start with the prefix.

### Step 3: Add prefix to variable names

**Before:**
```bsl
Var CacheData;
Var IsInitialized;
```

**After:**
```bsl
Var MyExt_CacheData;
Var MyExt_IsInitialized;
```

### Step 4: Update all references

Find and update all references to the renamed variables:

```bsl
// Before
CacheData = New Map;
If IsInitialized Then
    // ...
EndIf;

// After
MyExt_CacheData = New Map;
If MyExt_IsInitialized Then
    // ...
EndIf;
```

---

## ⚠️ Common Scenarios

### Common Module Variables

```bsl
// CommonModule (in extension)
// Extension: ERP_Integration

#Region Variables

// ❌ Wrong
Var ConnectionSettings;

// ✅ Correct
Var ERP_ConnectionSettings;

#EndRegion
```

### Form Module Variables

```bsl
// Form module extension
// Extension: Custom_

#Region Variables

// ❌ Wrong
Var FormDataCache;

// ✅ Correct
Var Custom_FormDataCache;

#EndRegion
```

### Object Module Variables

```bsl
// Object module extension
// Extension: Addon_

#Region Variables

// ❌ Wrong
Var ProcessingFlags;
Var TempData Export;

// ✅ Correct
Var Addon_ProcessingFlags;
Var Addon_TempData Export;

#EndRegion
```

---

## 📋 Best Practices

### Minimize Module Variables

Consider using `AdditionalProperties` instead of module variables:

```bsl
// Instead of:
Var MyExt_ProcessingMode;

// Use:
Procedure SomeMethod()
    ProcessingMode = Undefined;
    AdditionalProperties.Property("MyExt_ProcessingMode", ProcessingMode);
    // ...
EndProcedure
```

### Initialize Variables Properly

```bsl
#Region Variables

Var MyExt_IsInitialized;

#EndRegion

#Region Private

Procedure MyExt_EnsureInitialized()
    If MyExt_IsInitialized = Undefined Then
        MyExt_IsInitialized = False;
    EndIf;
    
    If Not MyExt_IsInitialized Then
        // Initialization logic
        MyExt_IsInitialized = True;
    EndIf;
EndProcedure

#EndRegion
```

---

## 🔍 Technical Details

### What Is Checked

1. Identifies variable declarations in extension modules
2. Gets extension prefix from configuration
3. Checks if variable name starts with prefix
4. Reports variables missing prefix

### Check Implementation Class

```
com.e1c.v8codestyle.bsl.check.ExtensionVariablePrefixCheck
```

### Location in v8-code-style

```
bundles/com.e1c.v8codestyle.bsl/src/com/e1c/v8codestyle/bsl/check/
```

---

## 📚 References

- [1C:Enterprise Development Standards - Standard 640](https://its.1c.ru/db/v8std/content/640/hdoc)
