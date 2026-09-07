# Statement changes type

## 📋 General Information

| Parameter | Value |
|----------|----------|
| **Check ID** | `statement-type-change` |
| **Severity** | Major |
| **Type** | Code style |
| **Category** | Strict Types |

## 🎯 What This Check Does

Strict typing system checks that statement (value assignment line) does not change variable type. In strictly typed code variable should maintain its type.

## ❌ Error Examples

### Example 1 - Type change in assignment

```bsl
// @strict-types

Value = 100;        // Number
Value = "text";     // ← ERROR: Type changed from Number to String
```

### Example 2 - Type change in loop

```bsl
// @strict-types

Result = 0;
For Each Item In Collection Do
    Result = Result + Item.Amount;
EndDo;
Result = "Done";   // ← ERROR: Type changed from Number to String
```

### Example 3 - Undefined assignment

```bsl
// @strict-types

Data = GetData();   // Structure
Data = Undefined;   // ← ERROR: Type changed to Undefined
```

## ✅ Compliant Solutions

### Example 1 - Consistent types

```bsl
// @strict-types

Value = 100;
Value = 200;        // OK: Still Number
```

### Example 2 - Separate variables

```bsl
// @strict-types

Result = 0;
For Each Item In Collection Do
    Result = Result + Item.Amount;
EndDo;
Status = "Done";    // Use different variable
```

### Example 3 - Allow Undefined in type

```bsl
// @strict-types

// Returns:
//  Structure, Undefined - data or Undefined
//
Function GetData()
```

## 🔧 How to Fix

1. Use separate variables for different types
2. Keep variable type consistent throughout code
3. Declare Undefined in union type if needed
4. Initialize variables with correct type

### Configurable options:
- `allowLocalVariableResetToUndefined` - Allow resetting local vars to Undefined

## 🔍 Technical Details

- **Java class**: `SimpleStatementTypeCheck`
- **Location**: `com.e1c.v8codestyle.bsl.strict.check`
- **Error message**: "Value type changed"
- **Applies when**: Module has `@strict-types` annotation

## 📚 References

- [Code typification](https://its.1c.ru/db/metod8dev#content:5930:hdoc)
