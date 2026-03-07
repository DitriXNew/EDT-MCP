# change-and-validate-instead-of-around

## 📋 General Information

| Parameter | Value |
|-----------|-------|
| **Check ID** | `change-and-validate-instead-of-around` |
| **Title** | Use pragma &ChangeAndValidate instead of &Around |
| **Description** | Checks that pragma &ChangeAndValidate is used when there is no call ProceedWithCall |
| **Severity** | `TRIVIAL` |
| **Type** | `CODE_STYLE` |
| **Complexity** | `NORMAL` |
| **Default State** | Enabled |
| **Minimum Platform** | 8.3.16+ |

---

## 🎯 What This Check Does

This check validates that in **extension modules**, when using method interception with the `&Around` annotation, you actually call `ProceedWithCall()`. If you **don't call `ProceedWithCall()`**, you should use `&ChangeAndValidate` annotation instead.

### Background: Extension Method Annotations

In 1C:Enterprise extensions, you can intercept methods from the base configuration using annotations:

| Annotation | Purpose | Use Case |
|------------|---------|----------|
| `&Before` | Execute code before the original method | Pre-processing, validation |
| `&After` | Execute code after the original method | Post-processing, logging |
| `&Around` | Replace the original method, can call `ProceedWithCall()` | Full control, conditional execution |
| `&ChangeAndValidate` | Modify/validate without calling original | Complete replacement |

### Why This Is Important

- **Code clarity**: `&Around` implies you might call the original method
- **Semantic correctness**: `&ChangeAndValidate` explicitly states you're replacing the behavior
- **Maintainability**: Other developers understand intent without reading the code
- **Best practices**: Follows 1C extension development guidelines

---

## ❌ Error Example

### Error Message

```
It's recommended to use pragma &ChangeAndValidate instead of &Around
```

**Russian:**
```
Рекомендуется использовать аннотацию &ChangeAndValidate вместо &Around
```

### Noncompliant Code Example

```bsl
// ❌ Using &Around but NOT calling ProceedWithCall()
// This should use &ChangeAndValidate instead
&Around
Procedure Ext_MyProcedure(Param) Export
    
    // Custom logic that completely replaces the original
    Param = 1;
    
    // ❌ No ProceedWithCall() - so why use &Around?
    
EndProcedure
```

### Why This Is Wrong

When you use `&Around` without calling `ProceedWithCall()`:
- The original method is **never executed**
- The annotation implies you **might** call the original method
- It's semantically misleading to other developers

---

## ✅ Compliant Solution

### Option 1: Use &ChangeAndValidate (if you don't need original method)

```bsl
// ✅ Correct: Using &ChangeAndValidate when not calling ProceedWithCall
&ChangeAndValidate
Procedure Ext_MyProcedure(Param) Export
    
    // Custom logic that completely replaces the original
    Param = 1;
    
    // No need for ProceedWithCall() - annotation is clear
    
EndProcedure
```

### Option 2: Keep &Around and call ProceedWithCall

```bsl
// ✅ Correct: Using &Around WITH ProceedWithCall
&Around
Function Ext_MyFunction() Export
    
    // Pre-processing logic
    PrepareData();
    
    // Call the original method
    Result = ProceedWithCall();
    
    // Post-processing logic
    ProcessResult(Result);
    
    Return Result;
    
EndFunction
```

### Complete Examples

#### Validation with Complete Replacement

```bsl
// ✅ Use &ChangeAndValidate for complete replacement
&ChangeAndValidate
Function Ext_CalculateDiscount(Amount) Export
    
    // Completely override discount calculation
    If Amount > 10000 Then
        Return Amount * 0.15;  // 15% discount
    ElsIf Amount > 5000 Then
        Return Amount * 0.10;  // 10% discount
    Else
        Return 0;
    EndIf;
    
    // Original method is never called
    
EndFunction
```

#### Conditional Execution with Around

```bsl
// ✅ Use &Around when conditionally calling original
&Around
Procedure Ext_BeforeWrite(Cancel) Export
    
    // Custom validation
    If Not ValidateCustomRules() Then
        Cancel = True;
        Return;
    EndIf;
    
    // Call original method if validation passed
    ProceedWithCall();
    
    // Post-processing after original execution
    LogWriteOperation();
    
EndProcedure
```

#### Pre/Post Processing with Around

```bsl
// ✅ Use &Around for wrapping with pre/post logic
&Around
Function Ext_GetData(Parameters) Export
    
    // Pre-processing
    ModifyParameters(Parameters);
    
    // Call original and get result
    Result = ProceedWithCall();
    
    // Post-processing
    EnrichResult(Result);
    
    Return Result;
    
EndFunction
```

---

## 📖 Extension Annotations Reference

### When to Use Each Annotation

| Annotation | Use When | ProceedWithCall |
|------------|----------|-----------------|
| `&Before` | Add logic BEFORE original, original always runs | Not applicable |
| `&After` | Add logic AFTER original, original always runs | Not applicable |
| `&Around` | Wrap original, conditionally execute original | **Required** |
| `&ChangeAndValidate` | Completely replace original behavior | **Not used** |

### Russian Equivalents

| English | Russian |
|---------|---------|
| `&Before` | `&Перед` |
| `&After` | `&После` |
| `&Around` | `&Вместо` |
| `&ChangeAndValidate` | `&ИзменениеИКонтроль` |
| `ProceedWithCall()` | `ПродолжитьВызов()` |

---

## 🔧 How to Fix

### Step 1: Analyze your method

Determine if you need to call the original method:
- **Need original execution** → Keep `&Around` and add `ProceedWithCall()`
- **Complete replacement** → Change to `&ChangeAndValidate`

### Step 2: Apply the fix

**If replacing completely:**
```bsl
// Before
&Around
Procedure Ext_MyMethod() Export
    // Custom logic, no ProceedWithCall
EndProcedure

// After
&ChangeAndValidate
Procedure Ext_MyMethod() Export
    // Custom logic, no ProceedWithCall
EndProcedure
```

**If you need original method:**
```bsl
// Before
&Around
Procedure Ext_MyMethod() Export
    // Custom logic only
EndProcedure

// After
&Around
Procedure Ext_MyMethod() Export
    // Pre-processing
    ProceedWithCall();  // Add this call
    // Post-processing
EndProcedure
```

---

## 📁 File Structure

This check applies to:

| File Type | Description |
|-----------|-------------|
| Extension modules | BSL files in extension configurations |
| Common modules | Extended common modules |
| Object modules | Extended object modules |
| Manager modules | Extended manager modules |
| Form modules | Extended form modules |

### Extension Module Naming Convention

Extension methods typically follow the naming pattern:
```
Ext_OriginalMethodName
```

---

## 🔍 Technical Details

### Platform Version Requirement

This check only applies to platform version **8.3.16 and higher**, when `&ChangeAndValidate` annotation was introduced.

### How the Check Works

1. Finds all `Pragma` nodes with `&Around` (or `&Вместо`) annotation
2. Gets the containing `Method`
3. Searches for any `Invocation` of `ProceedWithCall()` (or `ПродолжитьВызов()`)
4. If no such call is found, reports the issue

### Check Implementation Class

```
com.e1c.v8codestyle.bsl.check.ChangeAndValidateInsteadOfAroundCheck
```

### Location in v8-code-style

```
bundles/com.e1c.v8codestyle.bsl/src/com/e1c/v8codestyle/bsl/check/ChangeAndValidateInsteadOfAroundCheck.java
```

---

## 📚 References

- [1C:Enterprise Platform - Extension Annotations](https://its.1c.ru/db/v8std)
