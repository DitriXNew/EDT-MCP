# common-module-named-self-reference

## 📋 General Information

| Parameter | Value |
|-----------|-------|
| **Check ID** | `common-module-named-self-reference` |
| **Title** | Redundant module name prefix in method call |
| **Description** | Check for redundant self module name in method call |
| **Severity** | `MINOR` |
| **Type** | `CODE_SMELL` |
| **Complexity** | `NORMAL` |
| **Default State** | Enabled |

---

## 🎯 What This Check Does

This check identifies **redundant self-references** when a common module calls its own methods using the module name prefix.

### Why This Is Important

- **Code clarity**: Self-references add unnecessary verbosity
- **Maintenance**: Module renaming requires fewer changes
- **Best practices**: Follow 1C coding conventions
- **Readability**: Cleaner code without redundant prefixes

---

## ❌ Error Example

### Error Message

```
Redundant module name prefix in method call
```

**Russian:**
```
Избыточный префикс имени модуля в вызове метода
```

### Noncompliant Code Example

```bsl
// In module: CommonUtilities

Function FormatDate(Date) Export
    Return Format(Date, "DLF=D");
EndFunction

Function GetFormattedDocument(DocumentRef) Export
    DocumentData = GetDocumentData(DocumentRef);
    
    // ❌ Redundant: calling own module with module name prefix
    FormattedDate = CommonUtilities.FormatDate(DocumentData.Date);
    
    Return FormattedDate;
EndFunction
```

---

## ✅ Compliant Solution

### Correct Code

```bsl
// In module: CommonUtilities

Function FormatDate(Date) Export
    Return Format(Date, "DLF=D");
EndFunction

Function GetFormattedDocument(DocumentRef) Export
    DocumentData = GetDocumentData(DocumentRef);
    
    // ✅ Correct: calling own method without module prefix
    FormattedDate = FormatDate(DocumentData.Date);
    
    Return FormattedDate;
EndFunction
```

---

## 📖 Self-Reference Patterns

### When Module Prefix Is Required

| Scenario | Required? | Example |
|----------|-----------|---------|
| Calling own method | ❌ No | `FormatDate(Value)` |
| Calling another module | ✓ Yes | `StringUtils.Trim(Value)` |
| Calling global module | Optional | `DoSomething()` or `GlobalModule.DoSomething()` |

### Examples

```bsl
// In module: DataProcessing

Procedure ProcessDocument(DocumentRef) Export
    // ❌ Wrong: self-reference
    Data = DataProcessing.GetDocumentData(DocumentRef);
    
    // ✅ Correct: direct call
    Data = GetDocumentData(DocumentRef);
    
    // ✅ Correct: calling different module
    FormattedData = StringUtilitiesClientServer.FormatData(Data);
EndProcedure

Function GetDocumentData(DocumentRef) Export
    Return DocumentRef.GetObject();
EndFunction
```

---

## 🔧 How to Fix

### Step 1: Identify self-references

Find calls in format:
```
ModuleName.MethodName()
```

Where `ModuleName` is the current module name.

### Step 2: Remove the module prefix

**Before:**
```bsl
Result = CurrentModuleName.SomeMethod(Parameter);
```

**After:**
```bsl
Result = SomeMethod(Parameter);
```

### Step 3: Verify the method exists in current module

Ensure the method is actually defined in the current module:
- Check `Export` keyword
- Verify method signature

---

## 📋 Edge Cases

### When Self-Reference Might Be Intentional

```bsl
// In Global module
// Sometimes explicit reference is used for clarity
Procedure Initialize() Export
    // Self-reference in global module for explicit clarity
    // This check will still flag it, but developer may suppress
    GlobalUtilitiesGlobal.SetupDefaults();
EndProcedure
```

### Recursive Calls

```bsl
// ❌ Redundant even for recursion
Function Factorial(N) Export
    If N <= 1 Then
        Return 1;
    EndIf;
    Return N * MathModule.Factorial(N - 1);  // Redundant
EndFunction

// ✅ Correct
Function Factorial(N) Export
    If N <= 1 Then
        Return 1;
    EndIf;
    Return N * Factorial(N - 1);  // Direct recursion
EndFunction
```

---

## 🔍 Technical Details

### What Is Checked

1. Finds method calls with explicit module name prefix
2. Compares prefix with current module name
3. Reports if they match (redundant self-reference)

### Check Implementation Class

```
com.e1c.v8codestyle.bsl.check.CommonModuleNamedSelfReference
```

### Location in v8-code-style

```
bundles/com.e1c.v8codestyle.bsl/src/com/e1c/v8codestyle/bsl/check/
```

