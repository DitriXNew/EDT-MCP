# variable-name-invalid-check

## 📋 General Information

| Parameter | Value |
|-----------|-------|
| **Check ID** | `variable-name-invalid-check` |
| **Title** | Variable name is invalid |
| **Description** | Checks that variable names follow naming conventions |
| **Severity** | `MINOR` |
| **Type** | `CODE_SMELL` |
| **Complexity** | `NORMAL` |
| **Default State** | Enabled |

---

## 🎯 What This Check Does

This check validates that **variable names** follow the established naming conventions:
- Must start with a capital letter
- Must not start with an underscore
- Must have minimum length

### Why This Is Important

- **Code consistency**: Uniform naming across codebase
- **Readability**: Proper names improve understanding
- **Standards compliance**: Follows 1C development standards
- **Maintainability**: Consistent names are easier to search

---

## ❌ Error Example

### Error Messages

```
Variable name is invalid: variable name must start with a capital letter
Variable name is invalid: variable name starts with an underline
Variable name is invalid: variable length is less than 2
```

### Noncompliant Code Example

```bsl
// ❌ Variable starts with lowercase letter
Procedure ProcessData()
    counter = 0;           // ❌ Should be "Counter"
    totalAmount = 0;       // ❌ Should be "TotalAmount"
    
    For Each item In Items Do  // ❌ Should be "Item"
        counter = counter + 1;
    EndDo;
EndProcedure

// ❌ Variable starts with underscore
Procedure Calculate()
    _Result = 0;           // ❌ Underscore at start
    _TempValue = 100;      // ❌ Underscore at start
    __Counter = 0;         // ❌ Double underscore
EndProcedure

// ❌ Variable name too short
Procedure Process()
    x = 0;                 // ❌ Too short (less than min length)
    i = 1;                 // ❌ Too short
    a = GetValue();        // ❌ Too short
EndProcedure

// ❌ Multiple violations
Function calc(val)
    res = val * 2;         // ❌ Lowercase, short
    Return res;
EndFunction
```

---

## ✅ Compliant Solution

### Correct Variable Naming

```bsl
// ✅ Variables start with capital letter
Procedure ProcessData()
    Counter = 0;           // ✅ Correct
    TotalAmount = 0;       // ✅ Correct
    
    For Each Item In Items Do  // ✅ Correct
        Counter = Counter + 1;
    EndDo;
EndProcedure

// ✅ No underscore at start
Procedure Calculate()
    Result = 0;            // ✅ Correct
    TempValue = 100;       // ✅ Correct
    ItemCounter = 0;       // ✅ Correct
EndProcedure

// ✅ Meaningful names with proper length
Procedure Process()
    Index = 0;             // ✅ Correct (descriptive)
    Counter = 1;           // ✅ Correct
    Value = GetValue();    // ✅ Correct
EndProcedure

// ✅ Proper naming throughout
Function Calculate(InputValue)
    Result = InputValue * 2;  // ✅ Correct
    Return Result;
EndFunction
```

---

## 📋 Naming Conventions

### Variable Naming Rules

| Rule | Description | Example |
|------|-------------|---------|
| Capital start | First letter must be uppercase | `Counter`, not `counter` |
| No underscore start | Cannot start with `_` | `Result`, not `_Result` |
| Minimum length | Must meet minimum length | Default: 2 characters |
| CamelCase | Use CamelCase for multi-word names | `TotalAmount` |

### Examples by Category

#### Loop Variables

```bsl
// ✅ Correct loop variable names
For Index = 1 To 10 Do
    // ...
EndDo;

For Each Item In Collection Do
    // ...
EndDo;

For LineNumber = 1 To Table.Count() Do
    // ...
EndDo;
```

#### Counters and Accumulators

```bsl
// ✅ Correct counter/accumulator names
Counter = 0;
TotalAmount = 0;
ItemCount = 0;
RunningTotal = 0;
```

#### Temporary Variables

```bsl
// ✅ Correct temporary variable names
TempValue = GetValue();
CurrentItem = Items[Index];
SelectedRow = Table.FindRows(Filter)[0];
```

---

## 📋 Common Violations and Fixes

### Lowercase Start

```bsl
// ❌ Wrong
value = 100;
result = Calculate();
currentUser = GetUser();

// ✅ Fixed
Value = 100;
Result = Calculate();
CurrentUser = GetUser();
```

### Underscore Start

```bsl
// ❌ Wrong
_Counter = 0;
_Result = "";
__Temp = 0;

// ✅ Fixed
Counter = 0;
Result = "";
Temp = 0;
```

### Too Short Names

```bsl
// ❌ Wrong
i = 0;
x = GetX();
a = Values[0];

// ✅ Fixed
Index = 0;
XCoordinate = GetX();
FirstValue = Values[0];
```

---

## 📋 Configuration

### Check Parameters

| Parameter | Default | Description |
|-----------|---------|-------------|
| `minNameLength` | 2 | Minimum variable name length |

### Example Settings

```
// Allow single-character loop variables
minNameLength = 1

// Require more descriptive names
minNameLength = 3
```

---

## 📋 Acceptable Short Names

In some coding standards, these short names are acceptable for specific uses:

| Name | Typical Use |
|------|-------------|
| `I`, `J`, `K` | Loop indices (if allowed) |
| `N` | Count |
| `ID` | Identifier |

### If Short Names Allowed

```bsl
// Only if minNameLength = 1 and starts with capital
For I = 1 To N Do
    Items[I].Process();
EndDo;
```

---

## 🔧 How to Fix

### Step 1: Find invalid variable names

Look for lowercase starts, underscore starts, or short names.

### Step 2: Rename to follow conventions

- Capitalize first letter
- Remove leading underscores
- Use descriptive names

### Step 3: Update all usages

Rename the variable everywhere it's used.

### Step 4: Use IDE rename feature

Use refactoring tools to rename safely.

---

## 📋 Module Variables

### Module-Level Variables

```bsl
// ✅ Correct module variable names
Var ModuleCounter;
Var CachedData;
Var IsInitialized;

// ❌ Incorrect
Var counter;      // Lowercase
Var _cached;      // Underscore
Var x;            // Too short
```

---

## 📋 Export Variables

### Public Variables

```bsl
// ✅ Correct export variable names
Var PublicCounter Export;
Var SharedData Export;

// ❌ Incorrect
Var counter Export;    // Lowercase
Var _data Export;      // Underscore
```

---

## 🔍 Technical Details

### What Is Checked

1. Variable name first character
2. Underscore at start
3. Variable name length
4. Compliance with naming pattern

### Check Implementation Class

```
com.e1c.v8codestyle.bsl.check.VariableNameInvalidCheck
```

### Location in v8-code-style

```
bundles/com.e1c.v8codestyle.bsl/src/com/e1c/v8codestyle/bsl/check/
```

---

## 📚 References

- [1C Naming Conventions](https://its.1c.ru/db/v8std/content/647/hdoc)
- [Code Style Guidelines](https://its.1c.ru/db/v8std)
