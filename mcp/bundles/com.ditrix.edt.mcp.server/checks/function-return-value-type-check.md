# Function returns typed value

## 📋 General Information

| Parameter | Value |
|----------|----------|
| **Check ID** | `function-return-value-type` |
| **Severity** | Major |
| **Type** | Code style |
| **Category** | Strict Types |

## 🎯 What This Check Does

Strict typing system checks that each function returns typed value. Type should be computed from code or declared in documenting comment.

## ❌ Error Examples

### Example 1 - Function without return type

```bsl
// @strict-types

Function GetValue()  // ← ERROR: Function has no return value type
    If Condition Then
        Return Value1;
    Else
        Return Value2;
    EndIf;
EndFunction
```

### Example 2 - Untyped return

```bsl
// @strict-types

Function ProcessData(Data)
    Result = SomeOperation();  // ← ERROR: Return type unknown
    Return Result;
EndFunction
```

## ✅ Compliant Solutions

### Example 1 - With documentation comment type

```bsl
// @strict-types

// Gets configuration value.
//
// Returns:
//  String - configuration value
//
Function GetValue()
    If Condition Then
        Return Value1;
    Else
        Return Value2;
    EndIf;
EndFunction
```

### Example 2 - Explicit typed return

```bsl
// @strict-types

// Processes data.
//
// Parameters:
//  Data - Structure - input data
//
// Returns:
//  Boolean - processing result
//
Function ProcessData(Data)
    // ...processing...
    Return True;
EndFunction
```

### Example 3 - Type from context

```bsl
// @strict-types

Function GetUserName()
    // Type inferred from return statement
    Return CurrentUser().Description;  // Returns String
EndFunction
```

## 🔧 How to Fix

1. Add `// Returns:` section with type in documentation comment
2. Ensure all return paths return same type
3. Use explicit type declarations
4. Avoid returning Undefined without declaring it

### Required format:
```
// Returns:
//  Type - description
```

## 🔍 Technical Details

- **Java class**: `FunctionReturnTypeCheck`
- **Location**: `com.e1c.v8codestyle.bsl.strict.check`
- **Applies when**: Module has `@strict-types` annotation

## 📚 References

- [Code typification](https://its.1c.ru/db/metod8dev#content:5930:hdoc)
- [Standard #453 - Commenting code](https://its.1c.ru/db/v8std#content:453:hdoc)
