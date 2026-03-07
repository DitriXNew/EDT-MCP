# extension-method-prefix

## 📋 General Information

| Parameter | Value |
|-----------|-------|
| **Check ID** | `extension-method-prefix` |
| **Title** | Extension method does not have extension prefix |
| **Description** | The procedure (function) in the module of the extension object does not have a prefix corresponding to the prefix of the extension itself |
| **Severity** | `MAJOR` |
| **Type** | `WARNING` |
| **Complexity** | `NORMAL` |
| **Default State** | Enabled |
| **1C Standard** | [640](https://its.1c.ru/db/v8std/content/640/hdoc) |

---

## 🎯 What This Check Does

This check validates that **methods added in extension modules** have a **prefix matching the extension's name prefix**.

### Why This Is Important

- **Conflict prevention**: Avoids name collisions with main configuration
- **Origin clarity**: Easy to identify extension-specific code
- **Upgrade safety**: Clear separation during configuration updates
- **Standards compliance**: Follows Standard 640

---

## ❌ Error Example

### Error Message

```
The method "{methodName}" should have "{prefix}" prefix
```

**Russian:**
```
Метод "{methodName}" должен иметь префикс "{prefix}"
```

### Noncompliant Code Example

**Extension name:** `МояКомпания_Расширение` (prefix: `МояКомпания_`)

```bsl
// Extension of Catalog.Products module
// ❌ Method without extension prefix
Procedure AdditionalValidation() Export
    // Extension logic
EndProcedure

// ❌ Wrong prefix
Procedure Other_ProcessProduct() Export
    // Extension logic
EndProcedure
```

---

## ✅ Compliant Solution

### Correct Method Naming

**Extension name:** `МояКомпания_Расширение` (prefix: `МояКомпания_`)

```bsl
// Extension of Catalog.Products module
// ✅ Method with correct extension prefix
Procedure МояКомпания_AdditionalValidation() Export
    // Extension logic
EndProcedure

// ✅ English configuration
Procedure MyCompany_ProcessProduct() Export
    // Extension logic
EndProcedure
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

### Prefix Format

- Contains company/vendor identifier
- Ends with underscore `_`
- Applied to all added methods

---

## 📋 What Needs Prefix

### Methods That Need Prefix

| Scenario | Needs Prefix |
|----------|--------------|
| New method in extension module | ✓ Yes |
| New export method | ✓ Yes |
| New private method | ✓ Yes |
| Handler for extended event | ✓ Yes |

### Methods That Don't Need Prefix

| Scenario | Needs Prefix |
|----------|--------------|
| Overridden existing method | ✗ No |
| &Before/&After handlers | ✗ No (uses original name) |
| &ChangeAndValidate handlers | ✗ No |

---

## 📋 Extension Module Examples

### Adding New Methods

```bsl
// Extension of Document.Invoice module
// Extension prefix: ABC_

// ✅ New private function with prefix
Function ABC_CalculateDiscount(Amount, DiscountPercent)
    Return Amount * DiscountPercent / 100;
EndFunction

// ✅ New export method with prefix
Procedure ABC_ApplySpecialProcessing() Export
    // Custom logic
EndProcedure

// ✓ Override doesn't need prefix (same name as original)
Procedure BeforeWrite(Cancel, WriteMode, PostingMode)
    // Extended logic
    
    // Call original if needed
    // ...
EndProcedure
```

### Before/After Handlers

```bsl
// ✓ &Before handler - uses original method name
&Before("BeforeWrite")
Procedure ABC_BeforeWriteExtension(Cancel, WriteMode, PostingMode)
    // Pre-processing
EndProcedure

// ✓ &After handler - uses original method name
&After("OnWrite")
Procedure ABC_OnWriteExtension(Cancel)
    // Post-processing
EndProcedure
```

---

## 🔧 How to Fix

### Step 1: Identify extension prefix

Find the extension's configured prefix in extension properties.

### Step 2: Find methods without prefix

Look for methods in extension modules that don't start with the prefix.

### Step 3: Add prefix to method names

**Before:**
```bsl
Procedure DoSomething()
Function CalculateValue()
```

**After:**
```bsl
Procedure MyCompany_DoSomething()
Function MyCompany_CalculateValue()
```

### Step 4: Update all calls

Find and update all references to the renamed methods:

```bsl
// Before
Result = CalculateValue();

// After
Result = MyCompany_CalculateValue();
```

---

## ⚠️ Common Scenarios

### New Common Module Methods

```bsl
// CommonModule (in extension)
// Extension: XYZ_Customization

// ❌ Wrong
Function GetSettings() Export
EndFunction

// ✅ Correct
Function XYZ_GetSettings() Export
EndFunction
```

### Extended Form Module

```bsl
// Form module extension
// Extension: Custom_

// ❌ New method without prefix
&AtServer
Procedure InitializeExtendedData()
EndProcedure

// ✅ Correct
&AtServer
Procedure Custom_InitializeExtendedData()
EndProcedure
```

---

## 📋 Special Cases

### Handlers for Extended Subscriptions

```bsl
// Subscription handler in extension
// Extension: Ext_

// ✅ Handler with prefix
Procedure Ext_DocumentPostingHandler(Source, Cancel) Export
    // Handle posting
EndProcedure
```

### Overloaded Methods

When extending existing methods, keep original name:

```bsl
// Extending existing OnCreateAtServer
// No prefix needed - overriding existing
Procedure OnCreateAtServer(Cancel, StandardProcessing)
    // Extended logic
EndProcedure
```

---

## 🔍 Technical Details

### What Is Checked

1. Identifies methods in extension modules
2. Gets extension prefix from configuration
3. Checks if method name starts with prefix
4. Excludes override scenarios
5. Reports methods missing prefix

### Check Implementation Class

```
com.e1c.v8codestyle.bsl.check.ExtensionMethodPrefixCheck
```

### Location in v8-code-style

```
bundles/com.e1c.v8codestyle.bsl/src/com/e1c/v8codestyle/bsl/check/
```

---

## 📚 References

- [1C:Enterprise Development Standards - Standard 640](https://its.1c.ru/db/v8std/content/640/hdoc)
