# Variable has value type

## 📋 General Information

| Parameter | Value |
|----------|----------|
| **Check ID** | `variable-value-type` |
| **Severity** | Major |
| **Type** | Code style |
| **Category** | Strict Types |

## 🎯 What This Check Does

Strict typing system checks that each variable has value type. Variables should be typed through assignment or type annotation.

## ❌ Error Examples

### Example 1 - Untyped variable

```bsl
// @strict-types

Var Result;  // ← ERROR: Variable has no value type
Result = SomeFunction();
```

### Example 2 - Variable without initial value

```bsl
// @strict-types

Var Counter;  // ← ERROR: No type for variable "Counter"
If Condition Then
    Counter = 0;
EndIf;
```

## ✅ Compliant Solutions

### Example 1 - Variable with type annotation

```bsl
// @strict-types

// @type: Structure
Var Result;
Result = GetData();
```

### Example 2 - Initialized variable

```bsl
// @strict-types

Counter = 0;  // Type inferred from value
If Condition Then
    Counter = Counter + 1;
EndIf;
```

### Example 3 - Local variable with immediate assignment

```bsl
// @strict-types

Result = New Structure;  // Type: Structure
Result.Insert("Name", UserName);
```

## 🔧 How to Fix

1. Initialize variable with typed value
2. Use type annotation comment `// @type: TypeName`
3. Avoid declaring variables without initialization
4. Assign correct type from first use

### Type annotation format:
```bsl
// @type: Structure
Var Result;
```

### Or use direct initialization:
```bsl
Result = New Structure;  // Type inferred
```

## 🔍 Technical Details

- **Java class**: `VariableTypeCheck`
- **Location**: `com.e1c.v8codestyle.bsl.strict.check`
- **Error message**: "Variable has no value type"
- **Applies when**: Module has `@strict-types` annotation

## 📚 References

- [Code typification](https://its.1c.ru/db/metod8dev#content:5930:hdoc)
