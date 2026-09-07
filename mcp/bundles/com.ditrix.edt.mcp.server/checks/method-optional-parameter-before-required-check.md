# method-optional-parameter-before-required-check

## 📋 General Information

| Parameter | Value |
|-----------|-------|
| **Check ID** | `method-optional-parameter-before-required-check` |
| **Title** | Optional parameter before required |
| **Description** | Checks that optional parameters don't precede required parameters |
| **Severity** | `MAJOR` |
| **Type** | `ERROR` |
| **Complexity** | `NORMAL` |
| **Default State** | Enabled |

---

## 🎯 What This Check Does

This check ensures that **optional parameters** (those with default values) don't appear before **required parameters** in procedure and function signatures. This is a fundamental principle of parameter ordering.

### Why This Is Important

- **Usability**: Required parameters should come first logically
- **Calling convention**: You can't skip optional parameters to reach required ones
- **Code clarity**: Makes method signatures understandable
- **Standards compliance**: Follows standard programming practices

---

## ❌ Error Example

### Error Message

```
Optional parameter before required
```

### Noncompliant Code Example

```bsl
// ❌ Optional parameter before required
Procedure ProcessData(Format = "XML", Data) Export
    // Format has default value, but Data is required
    // Caller cannot skip Format to provide Data
EndProcedure

// ❌ Multiple optional before required
Function Calculate(Precision = 2, RoundingMode = 0, Value) Export
    // Value is required but comes last
    Return Round(Value, Precision, RoundingMode);
EndFunction

// ❌ Mixed order
Procedure SaveDocument(UseTransaction = True, Document, Validate = False) Export
    // Document is required between two optional
EndProcedure
```

### Problem Illustration

```bsl
// How would you call this?
Procedure ProcessData(Format = "XML", Data)

// ❌ This doesn't work - can't skip Format
ProcessData(MyData);

// ❌ Must always specify Format even when default is fine
ProcessData("XML", MyData);
```

---

## ✅ Compliant Solution

### Correct Parameter Order

```bsl
// ✅ Required parameters first, optional after
Procedure ProcessData(Data, Format = "XML") Export
    // Data is required, Format has default
EndProcedure

// ✅ Correct order
Function Calculate(Value, Precision = 2, RoundingMode = 0) Export
    Return Round(Value, Precision, RoundingMode);
EndFunction

// ✅ All required first, then all optional
Procedure SaveDocument(Document, UseTransaction = True, Validate = False) Export
    If Validate Then
        ValidateDocument(Document);
    EndIf;
    
    If UseTransaction Then
        BeginTransaction();
    EndIf;
    
    Document.Write();
    
    If UseTransaction Then
        CommitTransaction();
    EndIf;
EndProcedure
```

### Calling Examples

```bsl
// ✅ Now calling is intuitive
Procedure ProcessData(Data, Format = "XML")

ProcessData(MyData);           // Uses default Format
ProcessData(MyData, "JSON");   // Specifies Format
```

---

## 📋 Parameter Ordering Rules

### Correct Order

1. **Required parameters** (no default value)
2. **Optional parameters** (with default value)

### Examples

```bsl
// ✅ Correct: Required → Optional
Procedure Example1(Required1, Required2, Optional1 = 0)
EndProcedure

// ✅ Correct: All required
Procedure Example2(Required1, Required2, Required3)
EndProcedure

// ✅ Correct: All optional
Procedure Example3(Optional1 = 0, Optional2 = "", Optional3 = True)
EndProcedure

// ❌ Wrong: Optional → Required
Procedure Example4(Optional1 = 0, Required1)
EndProcedure

// ❌ Wrong: Mixed
Procedure Example5(Required1, Optional1 = 0, Required2)
EndProcedure
```

---

## 📖 Understanding Optional Parameters

### What Makes a Parameter Optional

```bsl
// Required - no default value
Procedure Method1(RequiredParam)

// Optional - has default value
Procedure Method2(OptionalParam = DefaultValue)

// Mixed
Procedure Method3(Required, Optional = Default)
```

### Common Default Values

```bsl
// Numeric defaults
Procedure Calculate(Value, Precision = 2)

// String defaults
Procedure FormatText(Text, Format = "")

// Boolean defaults
Procedure Save(Data, Validate = True)

// Undefined as default
Procedure Process(Data, Handler = Undefined)
```

---

## 🔧 How to Fix

### Step 1: Identify the issue

Find parameters with default values that precede parameters without.

### Step 2: Reorder parameters

Move required parameters to the front.

### Step 3: Update all call sites

```bsl
// Before
Procedure Process(Format = "XML", Data)
// Called as:
Process("XML", MyData);
Process("JSON", OtherData);

// After
Procedure Process(Data, Format = "XML")
// Update calls:
Process(MyData);           // or Process(MyData, "XML")
Process(OtherData, "JSON");
```

### Step 4: Use Find Usages

Search for all method calls and update them accordingly.

---

## ⚠️ Special Considerations

### Multiple Optional Parameters

```bsl
// All optional is fine - any order
Procedure Configure(Option1 = 1, Option2 = 2, Option3 = 3) Export
EndProcedure

// Can skip from the end
Configure();           // All defaults
Configure(10);         // Only Option1 specified
Configure(10, 20);     // Option1 and Option2 specified
Configure(10, 20, 30); // All specified
```

### Using Undefined for Optional

```bsl
// Common pattern for optional object references
Procedure ProcessOrder(Order, Customer = Undefined) Export
    If Customer = Undefined Then
        Customer = Order.Customer;
    EndIf;
    // ...
EndProcedure
```

---

## 🔍 Technical Details

### What Is Checked

1. All procedure and function declarations
2. Parameter order analysis
3. Identifies optional before required patterns

### Check Implementation Class

```
com.e1c.v8codestyle.bsl.check.MethodOptionalParameterBeforeRequiredCheck
```

### Location in v8-code-style

```
bundles/com.e1c.v8codestyle.bsl/src/com/e1c/v8codestyle/bsl/check/
```

---

## 📚 References

- [Method Too Many Parameters Check](method-too-many-params-check.md)
