# Method parameter has type

## 📋 General Information

| Parameter | Value |
|----------|----------|
| **Check ID** | `method-param-value-type` |
| **Severity** | Major |
| **Type** | Code style |
| **Category** | Strict Types |

## 🎯 What This Check Does

Strict typing system checks that each method parameter has value type. Type should be specified in documenting comment.

## ❌ Error Examples

### Example 1 - Parameter without type

```bsl
// @strict-types

// Processes data.
//
// Parameters:
//  Data  ← ERROR: Parameter has no value type
//
Procedure ProcessData(Data)
```

### Example 2 - Missing type in parameters

```bsl
// @strict-types

// Saves user settings.
//
// Parameters:
//  User - String - user name
//  Settings  ← ERROR: Missing type for "Settings" parameter
//
Procedure SaveSettings(User, Settings)
```

## ✅ Compliant Solutions

### Example 1 - Parameter with type

```bsl
// @strict-types

// Processes data.
//
// Parameters:
//  Data - Structure - data to process
//
Procedure ProcessData(Data)
```

### Example 2 - All parameters typed

```bsl
// @strict-types

// Saves user settings.
//
// Parameters:
//  User - String - user name
//  Settings - Structure - user settings
//
Procedure SaveSettings(User, Settings)
```

## 🔧 How to Fix

1. Add type for each parameter in documentation comment
2. Format: `//  ParamName - Type - description`
3. Use concrete types, avoid Arbitrary when possible
4. Required when using `@strict-types` annotation

### Parameter format:
```
// Parameters:
//  ParamName - Type - description
```

## 🔍 Technical Details

- **Java class**: `MethodParamTypeCheck`
- **Location**: `com.e1c.v8codestyle.bsl.strict.check`
- **Error message**: "Method param has no value type"
- **Applies when**: Module has `@strict-types` annotation

## 📚 References

- [Code typification](https://its.1c.ru/db/metod8dev#content:5930:hdoc)
- [Standard #453 - Commenting code](https://its.1c.ru/db/v8std#content:453:hdoc)
