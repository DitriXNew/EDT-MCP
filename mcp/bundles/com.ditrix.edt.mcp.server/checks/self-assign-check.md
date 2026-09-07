# self-assign-check

## 📋 General Information

| Parameter | Value |
|-----------|-------|
| **Check ID** | `self-assign-check` |
| **Title** | Self assign |
| **Description** | Checks for variables that are assigned to themselves |
| **Severity** | `MAJOR` |
| **Type** | `ERROR` |
| **Complexity** | `NORMAL` |
| **Default State** | Enabled |

---

## 🎯 What This Check Does

This check identifies statements where a **variable is assigned to itself**. Self-assignment is usually a programming error that has no effect.

### Why This Is Important

- **Bug detection**: Usually indicates a typo or logic error
- **No-op code**: Self-assignment does nothing useful
- **Code clarity**: Confuses readers about intent
- **Potential errors**: May mask intended assignment

---

## ❌ Error Example

### Error Message

```
This variable self assign
```

### Noncompliant Code Example

```bsl
// ❌ Variable assigned to itself
Procedure ProcessData() Export
    Value = 10;
    Value = Value; // ❌ Self-assignment - does nothing
EndProcedure

// ❌ Property self-assignment
Procedure UpdateObject(Object) Export
    Object.Name = Object.Name; // ❌ No change
EndProcedure

// ❌ Likely typo
Procedure CalculateTotal(Price, Quantity) Export
    Total = Price * Quantity;
    Total = Total; // ❌ Probably meant different calculation
EndProcedure

// ❌ Copy-paste error
Procedure CopyValues(Source, Target) Export
    Target.Field1 = Source.Field1;
    Target.Field2 = Source.Field2;
    Target.Field3 = Target.Field3; // ❌ Should be Source.Field3
EndProcedure
```

---

## ✅ Compliant Solution

### Correct Assignments

```bsl
// ✅ Meaningful assignments
Procedure ProcessData() Export
    Value = 10;
    Value = Value + 1; // ✅ Incrementing
    Value = Value * 2; // ✅ Calculation
EndProcedure

// ✅ Assign from different source
Procedure UpdateObject(Object, NewName) Export
    Object.Name = NewName; // ✅ Different source
EndProcedure

// ✅ Correct calculation
Procedure CalculateTotal(Price, Quantity, Discount) Export
    Total = Price * Quantity;
    Total = Total - Discount; // ✅ Applying discount
EndProcedure

// ✅ Correct copy
Procedure CopyValues(Source, Target) Export
    Target.Field1 = Source.Field1;
    Target.Field2 = Source.Field2;
    Target.Field3 = Source.Field3; // ✅ From Source
EndProcedure
```

---

## 📋 Common Causes

### 1. Copy-Paste Errors

```bsl
// Copied line, forgot to change
Target.A = Source.A;
Target.B = Source.B;
Target.C = Target.C; // ❌ Forgot to change Target to Source
```

### 2. Typos

```bsl
// Similar variable names
NewValue = NewValue; // ❌ Meant OldValue?
```

### 3. Incomplete Refactoring

```bsl
// Variable was renamed but assignment wasn't updated
Result = Calculate();
Result = Result; // ❌ Leftover from refactoring
```

### 4. Debugging Remnants

```bsl
// Debug code left behind
DebugValue = DebugValue; // ❌ Meaningless debug line
```

---

## 🔧 How to Fix

### Step 1: Identify the intent

Ask: What was this assignment supposed to do?

### Step 2: Fix the assignment

```bsl
// Common fixes:

// Wrong source
Target = Target; // ❌
Target = Source; // ✅

// Missing calculation
Value = Value; // ❌
Value = Value + 1; // ✅

// Remove if unnecessary
Value = Value; // ❌ Just delete
```

### Step 3: Review context

Check surrounding code for similar errors.

---

## 📖 Similar Patterns

### Compound Self-Assignment (Valid)

```bsl
// These are NOT self-assignment - they modify the value
Value = Value + 1;
Value = Value * 2;
Text = Text + " suffix";
Array.Add(Array.Count()); // Different operation
```

### True Self-Assignment (Invalid)

```bsl
// These ARE self-assignment - no modification
Value = Value;
Object.Property = Object.Property;
Array[0] = Array[0];
```

---

## 📋 When Self-Assignment Might Occur

### In Loops

```bsl
// ❌ Condition should use different variable
For Each Item In Items Do
    Item = Item; // Makes no sense
EndDo;
```

### In Conditional Branches

```bsl
// ❌ Both branches assign same value
If Condition Then
    Result = CalculatedValue;
Else
    Result = Result; // ❌ Should be different value
EndIf;
```

### In Method Calls

```bsl
// ❌ Return value ignored or same
Result = ProcessData(Result);
Result = Result; // ❌ Useless if ProcessData returns same
```

---

## 🔍 Technical Details

### What Is Checked

1. Assignment statements
2. Left and right side comparison
3. Identifies identical operands

### Check Implementation Class

```
com.e1c.v8codestyle.bsl.check.SelfAssignCheck
```

### Location in v8-code-style

```
bundles/com.e1c.v8codestyle.bsl/src/com/e1c/v8codestyle/bsl/check/
```

---

## 📚 References

- [Module Unused Local Variable Check](module-unused-local-variable-check.md)
