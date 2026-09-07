# bsl-variable-name-invalid

## 📋 General Information

| Parameter | Value |
|-----------|-------|
| **Check ID** | `bsl-variable-name-invalid` |
| **Title** | Variable name is invalid |
| **Description** | Variable name is invalid |
| **Severity** | `MINOR` |
| **Type** | `CODE_STYLE` |
| **Complexity** | `NORMAL` |
| **Default State** | Enabled |
| **1C Standard** | [Standard 454 - Variable Naming](https://its.1c.ru/db/v8std/content/454/hdoc) |

---

## 🎯 What This Check Does

This check validates that variable names follow the 1C development naming conventions:

1. **Variable name must start with a capital letter** (not lowercase)
2. **Variable name cannot start with an underscore** (`_`)
3. **Variable name must have minimum length** (default: 3 characters)

### Why This Is Important

- **Code readability**: Consistent naming makes code easier to read and understand
- **1C standards compliance**: Follows official 1C development guidelines
- **Maintainability**: Proper naming helps other developers understand the code purpose
- **Professionalism**: Well-named variables indicate quality code

---

## ❌ Error Examples

### Error Message Format

```
Variable name {name} is invalid: {reason}
```

### Error 1: Variable starts with lowercase letter

```
Variable name {name} is invalid: variable name must start with a capital letter
```

#### Noncompliant Code

```bsl
Procedure Test()
    
    // ❌ Explicit variable declaration with lowercase first letter
    Var explicitVariable;
    
    // ❌ Implicit variable with lowercase first letter
    implicitVariable = 2;
    
EndProcedure
```

### Error 2: Variable starts with underscore

```
Variable name {name} is invalid: variable name starts with an underline
```

#### Noncompliant Code

```bsl
Procedure Test()
    
    // ❌ Explicit variable starting with underscore
    Var _ExplicitVariable;
    
    // ❌ Implicit variable starting with underscore
    _ImplicitVariable = 2;
    
EndProcedure
```

### Error 3: Variable name too short

```
Variable name {name} is invalid: variable length is less than {minLength}
```

#### Noncompliant Code

```bsl
Procedure Test()
    
    // ❌ Variable name too short (less than 3 characters)
    Var EV;
    
    // ❌ Short implicit variable
    IV = 2;
    
EndProcedure
```

---

## ✅ Compliant Solution

### Correct Variable Names

```bsl
Procedure Test()
    
    // ✅ Correct: starts with capital letter, proper length
    Var ExplicitVariable;
    
    // ✅ Correct: starts with capital letter
    ImplicitVariable = 2;
    
    // ✅ Correct: CamelCase naming
    Var CustomerName;
    DocumentNumber = "000001";
    
    // ✅ Correct: descriptive names
    Var TotalAmount;
    ItemCount = 0;
    
EndProcedure
```

### Loop Iterators Exception

The check has a special exception for **loop iterators** - short variable names are allowed in `For` loops:

```bsl
Procedure ProcessArray()
    
    ContractorArray = New Array();
    
    // ✅ Allowed: short iterator in For loop
    For i = 0 To ContractorArray.UBound() Do
        // Process array
    EndDo;
    
    // ✅ Allowed: short element variable in For Each
    For Each El In ContractorArray Do
        // Process element
    EndDo;
    
EndProcedure
```

### Method Parameters

Method parameters are also validated:

```bsl
// ✅ Correct parameter name
Procedure ProcessContractors(Contractors)
EndProcedure

// ❌ Incorrect: parameter starts with underscore
Procedure ProcessContractors2(_Contractors)
EndProcedure
```

---

## 📖 Variable Naming Convention Reference

### General Rules

| Rule | Description | Example |
|------|-------------|---------|
| Start with capital | First letter must be uppercase | `CustomerName` ✅, `customerName` ❌ |
| No underscore prefix | Cannot start with `_` | `Amount` ✅, `_Amount` ❌ |
| Minimum length | At least 3 characters (configurable) | `Sum` ✅, `Sm` ❌ |
| CamelCase | Use CamelCase for multi-word names | `TotalAmount` ✅ |

### Good Naming Practices

```bsl
// ✅ Descriptive and clear names
Var CustomerReference;
Var DocumentDate;
Var TotalQuantity;
Var IsProcessed;
Var ErrorMessage;

// ✅ Boolean variables should suggest yes/no
Var IsActive;
Var HasErrors;
Var CanEdit;

// ✅ Collections should be plural or have Collection/List/Array suffix
Var Customers;
Var DocumentList;
Var ItemArray;
```

### Avoid These Patterns

```bsl
// ❌ Too short (unless in loop)
Var X;
Var Nm;

// ❌ Starts with lowercase
Var customerName;
Var totalAmount;

// ❌ Starts with underscore
Var _InternalVariable;
Var _TempValue;

// ❌ Non-descriptive
Var Temp;
Var Data;
Var Value;
```

---

## ⚙️ Check Parameters

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `minNameLength` | Integer | `3` | Minimum allowed length for variable names |

### Adjusting Minimum Length

You can configure the minimum length in your check settings:

- **Default (3)**: Variables must be at least 3 characters (`Sum` is OK, `Sm` is not)
- **Set to 2**: Allow 2-character variable names
- **Set to 4+**: Require longer, more descriptive names

---

## 🔧 How to Fix

### Step 1: Identify the issue

Read the error message to understand what's wrong:
- "starts with an underline" → Remove underscore prefix
- "must start with a capital letter" → Capitalize first letter
- "length is less than" → Use a longer, more descriptive name

### Step 2: Rename the variable

**Underscore prefix:**
```bsl
// Before
Var _InternalVariable;
// After
Var InternalVariable;
```

**Lowercase first letter:**
```bsl
// Before
customerName = "Test";
// After
CustomerName = "Test";
```

**Too short:**
```bsl
// Before
Cnt = 0;
// After
Counter = 0;
// Or more descriptive:
ItemCount = 0;
```

### Step 3: Update all usages

After renaming a variable, update all places where it's used:

```bsl
// Before
cnt = 0;
For Each Item In Items Do
    cnt = cnt + 1;
EndDo;
Message(cnt);

// After
ItemCount = 0;
For Each Item In Items Do
    ItemCount = ItemCount + 1;
EndDo;
Message(ItemCount);
```

---

## 📁 File Structure

This check applies to:

| File Type | Description |
|-----------|-------------|
| `*.bsl` | Any BSL module file |
| Common modules | Shared modules |
| Object modules | Catalog, Document modules |
| Manager modules | Manager modules |
| Form modules | Form module code |
| Command modules | Command modules |

---

## 🔍 Technical Details

### What Is Checked

1. **Explicit variable declarations** (`Var VariableName;`)
2. **Implicit variable declarations** (`VariableName = Value;`)
3. **Method parameters** (`Procedure Test(ParameterName)`)

### What Is NOT Checked

1. **For loop iterators** - Short names allowed in `For` loops
2. **Variables with null name** - Syntax errors handled separately

### Check Implementation Class

```
com.e1c.v8codestyle.bsl.check.VariableNameInvalidCheck
```

### Location in v8-code-style

```
bundles/com.e1c.v8codestyle.bsl/src/com/e1c/v8codestyle/bsl/check/VariableNameInvalidCheck.java
```

---

## 📚 References

- [1C:Enterprise Development Standards - Standard 454](https://its.1c.ru/db/v8std/content/454/hdoc)
- [1C:Enterprise Development Standards - Naming Conventions](https://its.1c.ru/db/v8std)
