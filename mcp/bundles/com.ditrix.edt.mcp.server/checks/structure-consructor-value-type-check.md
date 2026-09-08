# Structure constructor value typing

## 📋 General Information

| Parameter | Value |
|----------|----------|
| **Check ID** | `structure-consructor-value-type` |
| **Severity** | Major |
| **Type** | Code style |
| **Category** | Strict Types |

## 🎯 What This Check Does

Checks string literal in structure constructor that each key has typed value. All structure keys should be initialized with values of known types.

## ❌ Error Examples

### Example 1 - Key without initializer

```bsl
// @strict-types

Result = New Structure("Name, Age");  // ← ERROR: Keys have no default value
```

### Example 2 - Empty type initialization

```bsl
// @strict-types

Result = New Structure;
Result.Insert("Name", );  // ← ERROR: Value initialized with empty type
```

### Example 3 - Undefined without type

```bsl
// @strict-types

Result = New Structure("Value", Undefined);  // ← ERROR: Key initialized with Undefined only
```

## ✅ Compliant Solutions

### Example 1 - Keys with initializers

```bsl
// @strict-types

Result = New Structure("Name, Age", "", 0);
```

### Example 2 - Explicit values

```bsl
// @strict-types

Result = New Structure;
Result.Insert("Name", "");       // String type
Result.Insert("Age", 0);         // Number type
Result.Insert("Active", True);   // Boolean type
```

### Example 3 - Typed with potential Undefined

```bsl
// @strict-types

// Using typed variable
DefaultValue = "";  // String
Result = New Structure("Value", DefaultValue);
```

## 🔧 How to Fix

1. Always provide default values for structure keys
2. Use typed literals (not just Undefined)
3. Initialize keys with values of intended type
4. Use constructor function pattern for complex structures

### Recommended pattern:
```bsl
Function NewUserData()
    Result = New Structure;
    Result.Insert("Name", "");
    Result.Insert("Age", 0);
    Result.Insert("Active", False);
    Return Result;
EndFunction
```

## 🔍 Technical Details

- **Java class**: `StructureCtorValueTypeCheck`
- **Location**: `com.e1c.v8codestyle.bsl.strict.check`
- **Error messages**: 
  - "Structure key has no default value initializer"
  - "Structure key value initialized with empty types"
- **Applies when**: Module has `@strict-types` annotation

## 📚 References

- [Code typification](https://its.1c.ru/db/metod8dev#content:5930:hdoc)
- [Standard #640 - Constructor functions](https://its.1c.ru/db/v8std#content:640:hdoc)
