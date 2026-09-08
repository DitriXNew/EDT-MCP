# Object property has return type

## 📋 General Information

| Parameter | Value |
|----------|----------|
| **Check ID** | `property-return-type` |
| **Severity** | Major |
| **Type** | Code style |
| **Category** | Strict Types |

## 🎯 What This Check Does

Strict typing system checks that dynamic object property has return value type. This ensures typing when accessing object properties.

## ❌ Error Examples

### Example 1 - Property without return type

```bsl
// @strict-types

Data = GetData();
Value = Data.SomeProperty;  // ← ERROR: Property has no return type
```

### Example 2 - Dynamic property access

```bsl
// @strict-types

// Returns:
//  Structure  ← ERROR: Structure fields not typed
//
Function GetSettings()
    Return Settings;
EndFunction

Result = GetSettings();
Timeout = Result.Timeout;  // ← Property type unknown
```

## ✅ Compliant Solutions

### Example 1 - Typed structure property

```bsl
// @strict-types

// Returns:
//  Structure:
//      * SomeProperty - String - property value
//
Function GetData()
    Return Data;
EndFunction

Data = GetData();
Value = Data.SomeProperty;  // Type known: String
```

### Example 2 - Complete property typing

```bsl
// @strict-types

// Returns:
//  Structure:
//      * Timeout - Number - timeout in seconds
//      * Server - String - server address
//
Function GetSettings()
    Return Settings;
EndFunction

Result = GetSettings();
Timeout = Result.Timeout;  // Type known: Number
```

## 🔧 How to Fix

1. Add complete type definition in documentation comments
2. For structures, define all field types
3. Use `See FunctionName` for shared type definitions
4. Avoid untyped property access

### Structure typing format:
```
// Returns:
//  Structure:
//      * PropertyName - Type - description
```

## 🔍 Technical Details

- **Java class**: `DynamicFeatureAccessTypeCheck`
- **Location**: `com.e1c.v8codestyle.bsl.strict.check`
- **Error message**: "Feature access has no return type"
- **Applies when**: Module has `@strict-types` annotation

## 📚 References

- [Code typification](https://its.1c.ru/db/metod8dev#content:5930:hdoc)
