# Parameter definition in multiline description

## 📋 General Information

| Parameter | Value |
|----------|----------|
| **Check ID** | `doc-comment-parameter-in-description-suggestion` |
| **Severity** | Minor |
| **Type** | Code style |

## 🎯 What This Check Does

Detects cases when method parameter definition is accidentally specified in description section instead of "Parameters" section. This is a common mistake when writing documenting comments.

## ❌ Error Examples

### Example 1 - Parameter in description

```bsl
// Saves user data.
// User - String - user name  ← ERROR: Looks like parameter definition
// Settings - Structure - user settings
//
Procedure SaveUserData(User, Settings)
```

### Example 2 - Mixed format

```bsl
// Processes order.
// Accepts:
// Order - DocumentRef - order reference  ← ERROR: Should be in Parameters section
//
Procedure ProcessOrder(Order)
```

## ✅ Correct Solutions

### Example 1 - Parameters in correct section

```bsl
// Saves user data.
//
// Parameters:
//  User - String - user name
//  Settings - Structure - user settings
//
Procedure SaveUserData(User, Settings)
```

### Example 2 - Properly structured

```bsl
// Processes order.
//
// Parameters:
//  Order - DocumentRef.Order - order reference
//
Procedure ProcessOrder(Order)
```

## 🔧 How to Fix

1. Move parameter definitions to `// Parameters:` section
2. Keep description section for general explanation
3. Use proper format for parameter definitions

### Correct structure:
```
// Description here (plain text about what method does)
//
// Parameters:
//  ParamName - Type - description
```

## 🔍 Technical Details

- **Java class**: `MultilineDescriptionParameterSuggestionCheck`
- **Location**: `com.e1c.v8codestyle.bsl.comment.check`
- **Error message**: "Probably method parameter is defined in description"

## 📚 References

- [Standard #453 - Commenting code](https://its.1c.ru/db/v8std#content:453:hdoc)
