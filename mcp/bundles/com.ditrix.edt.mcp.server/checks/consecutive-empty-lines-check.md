# consecutive-empty-lines-check

## 📋 General Information

| Parameter | Value |
|-----------|-------|
| **Check ID** | `consecutive-empty-lines-check` |
| **Title** | Consecutive empty lines in code |
| **Description** | Checks for multiple consecutive empty lines in module code |
| **Severity** | `MINOR` |
| **Type** | `CODE_SMELL` |
| **Complexity** | `TRIVIAL` |
| **Default State** | Enabled |

---

## 🎯 What This Check Does

This check identifies **multiple consecutive empty lines** in module code. According to coding standards, only one empty line should be used to separate code blocks.

### Why This Is Important

- **Code consistency**: Uniform formatting across codebase
- **Readability**: Excessive whitespace reduces readability
- **Standards compliance**: Follows 1C development standards
- **Maintainability**: Clean code is easier to maintain

---

## ❌ Error Example

### Error Message

```
Multiple consecutive empty lines detected. Use only one empty line to separate code blocks.
```

### Noncompliant Code Example

```bsl
// ❌ Multiple empty lines between procedures
Procedure FirstProcedure()
    // Code
EndProcedure



Procedure SecondProcedure()
    // Code
EndProcedure




Procedure ThirdProcedure()
    // Code
EndProcedure

// ❌ Multiple empty lines inside procedure
Procedure ProcessData()
    Variable1 = 1;


    Variable2 = 2;



    Variable3 = 3;
EndProcedure

// ❌ Multiple empty lines at end of module
Procedure LastProcedure()
    // Code
EndProcedure




```

---

## ✅ Compliant Solution

### Use Single Empty Lines

```bsl
// ✅ Single empty line between procedures
Procedure FirstProcedure()
    // Code
EndProcedure

Procedure SecondProcedure()
    // Code
EndProcedure

Procedure ThirdProcedure()
    // Code
EndProcedure

// ✅ Single empty line for logical separation
Procedure ProcessData()
    Variable1 = 1;
    
    Variable2 = 2;
    
    Variable3 = 3;
EndProcedure

// ✅ No trailing empty lines
Procedure LastProcedure()
    // Code
EndProcedure
```

---

## 📋 When to Use Empty Lines

### Between Procedures/Functions

```bsl
// ✅ One empty line between procedures
Procedure ProcedureA()
    // Code
EndProcedure

Procedure ProcedureB()
    // Code
EndProcedure
```

### Between Logical Blocks

```bsl
// ✅ One empty line separates logical blocks
Procedure ProcessOrder()
    // Block 1: Validation
    If Not ValidateOrder() Then
        Return;
    EndIf;
    
    // Block 2: Calculation
    CalculateTotals();
    
    // Block 3: Saving
    SaveOrder();
EndProcedure
```

### Between Variable Declarations and Code

```bsl
// ✅ One empty line after variable declarations
Procedure Calculate()
    Result = 0;
    Counter = 0;
    
    For Each Item In Items Do
        Counter = Counter + 1;
        Result = Result + Item.Value;
    EndDo;
    
    Return Result;
EndProcedure
```

---

## 📋 When NOT to Use Empty Lines

### Between Related Statements

```bsl
// ✅ No empty line between related statements
Counter = Counter + 1;
Total = Total + Amount;

// ❌ Avoid separating related operations
Counter = Counter + 1;

Total = Total + Amount;
```

### Inside Control Structures

```bsl
// ✅ No unnecessary empty lines inside If
If Condition Then
    DoSomething();
    DoSomethingElse();
EndIf;

// ❌ Avoid
If Condition Then

    DoSomething();

    DoSomethingElse();

EndIf;
```

---

## 📋 Standard Formatting Rules

### Module Structure

```bsl
// ✅ Correct module structure with single empty lines

#Region Variables

Var ModuleVariable;

#EndRegion

#Region Public

Procedure PublicMethod() Export
    // Code
EndProcedure

#EndRegion

#Region Private

Procedure PrivateMethod()
    // Code
EndProcedure

#EndRegion
```

### Maximum Empty Lines

| Location | Maximum Empty Lines |
|----------|---------------------|
| Between procedures | 1 |
| Between regions | 1 |
| Inside procedures | 1 |
| At file start | 0 |
| At file end | 0 |

---

## 🔧 How to Fix

### Option 1: Manual Removal

Remove extra empty lines, leaving only one.

### Option 2: Use IDE Formatting

Use the IDE's format document feature to auto-fix.

### Option 3: Search and Replace

Use regex to find and replace multiple empty lines:

**Find:** `\n\n\n+`
**Replace:** `\n\n`

---

## 📋 Configuration

### Check Parameters

| Parameter | Default | Description |
|-----------|---------|-------------|
| `maxEmptyLines` | 1 | Maximum allowed consecutive empty lines |

### Example Configuration

```
maxEmptyLines = 1  // Default: allow only 1 empty line
```

---

## 📋 Examples of Fixes

### Example 1: Between Procedures

```bsl
// ❌ Before
Procedure A()
EndProcedure



Procedure B()
EndProcedure

// ✅ After
Procedure A()
EndProcedure

Procedure B()
EndProcedure
```

### Example 2: Inside Procedure

```bsl
// ❌ Before
Procedure Process()
    Step1();


    Step2();
EndProcedure

// ✅ After
Procedure Process()
    Step1();
    
    Step2();
EndProcedure
```

### Example 3: End of File

```bsl
// ❌ Before
Procedure Last()
EndProcedure



// ✅ After
Procedure Last()
EndProcedure
```

---

## 📋 Integration with IDE

### EDT Auto-Format

1. Select all code (Ctrl+A)
2. Format document (Ctrl+Shift+F)
3. Empty lines will be normalized

### Save Actions

Configure IDE to format on save:
- Remove trailing whitespace
- Normalize empty lines

---

## 🔍 Technical Details

### What Is Checked

1. Count of consecutive empty lines
2. Comparison with allowed maximum
3. Location in module

### Check Implementation Class

```
com.e1c.v8codestyle.bsl.check.ConsecutiveEmptyLinesCheck
```

### Location in v8-code-style

```
bundles/com.e1c.v8codestyle.bsl/src/com/e1c/v8codestyle/bsl/check/
```

---

## 📚 References

- [1C Coding Standards](https://its.1c.ru/db/v8std)
- [Module Structure Standards](https://its.1c.ru/db/v8std/content/455/hdoc)
